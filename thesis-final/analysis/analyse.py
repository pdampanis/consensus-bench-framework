#!/usr/bin/env python3
"""
analyse.py - post-campaign analysis

Reads the results/ tree, computes summary statistics, runs the Mann-Whitney
U test (correct choice for independent samples, not paired Wilcoxon), and
produces a fixed budget of plots.

Inputs  : results/<s>/<scenario>/<run_id>/{manifest.json,throughput.csv,latency.csv}
          results/<s>/leader_election/<run_id>/trial.txt
Outputs : analysis/out/
            summary.csv         aggregate stats per (system, scenario, metric)
            comparison.csv      wide-format table for thesis body
            pairwise_tests.csv  Mann-Whitney U per pair of systems
            leader_election.csv per-trial failover times
            plots/              5 hero + up to 8 appendix PNGs
            report.md           markdown summary
            methodology.md      methodology alerts for the thesis chapter
"""
from __future__ import annotations

import argparse
import json
import logging
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from scipy import stats

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
log = logging.getLogger(__name__)

# Systems with known methodology limitations that affect statistical validity.
# Each entry documents the limitation and which metrics are affected.
METHODOLOGY_ALERTS = {
    "hotstuff": {
        "lat_avg_us": "mean-only (asonnino harness reports only mean end-to-end latency)",
        "lat_p50_us": "set equal to mean (asonnino harness does not report percentiles)",
        "lat_p95_us": "set equal to mean (asonnino harness does not report percentiles)",
        "lat_p99_us": "set equal to mean (asonnino harness does not report percentiles)",
        "lat_p99_9_us": "set equal to mean (asonnino harness does not report percentiles)",
        "throughput": "synthetically back-filled from final mean (asonnino reports only summary TPS)",
        "correctness": "no durability verification available (asonnino client has no read API)",
    },
    "etcd": {
        "throughput": "approximated from total (etcd benchmark put emits only summary rate; "
                      "Prometheus metrics provide per-second resolution but were not collected for all runs)",
    },
    "paxos": {
        "correctness": "sampled 18 keys only (Paxi has no scan API; durability check is spot-sample)",
    },
    "epaxos": {
        "correctness": "sampled 18 keys only (Paxi has no scan API; durability check is spot-sample)",
    },
}

# Metrics whose variance is known to be zero/near-zero for specific systems
# due to data collection methodology, not lack of real variance.
ZERO_VARIANCE_SYSTEM_METRICS = {
    ("hotstuff", "lat_p50_us"),
    ("hotstuff", "lat_p95_us"),
    ("hotstuff", "lat_p99_us"),
    ("hotstuff", "lat_p99_9_us"),
}


# ---------------------------------------------------------------------------
# Loading
# ---------------------------------------------------------------------------

def load_runs(results_dir: Path) -> pd.DataFrame:
    """One row per run with throughput, latency percentiles, correctness."""
    rows = []
    manifests = sorted(set(
        list(results_dir.glob("*/*/*/manifest.json")) +
        list(results_dir.glob("*_n*/*/*/manifest.json"))
    ))
    for manifest in manifests:
        try:
            m = json.loads(manifest.read_text())
            status = m.get("status", "unknown")
            scenario = m.get("scenario", "unknown")
            system = m["system"]

            is_double_kill = scenario == "double_kill"
            is_incomplete = status != "complete"

            # Include double_kill cells (zero throughput is expected).
            # Include other cells with status "complete" only.
            # Log skipped cells so the researcher knows what was excluded.
            if is_incomplete and not is_double_kill:
                log.info("skip incomplete: %s/%s/%s (status=%s)",
                         system, scenario, m["run_id"], status)
                continue

            row = {
                "system": system,
                "scenario": scenario,
                "run_id": m["run_id"],
                "cluster_size": m.get("cluster_size", 3),
                "correctness": m.get("correctness", "SKIP"),
                "status": status,
            }
            tpath = manifest.parent / "throughput.csv"
            if tpath.exists():
                tdf = pd.read_csv(tpath, names=["t_off", "ops"], header=None, on_bad_lines="skip")
                tdf = tdf[pd.to_numeric(tdf["t_off"], errors="coerce").notna()]
                tdf["ops"] = pd.to_numeric(tdf["ops"], errors="coerce")
                tdf = tdf.dropna(subset=["ops"])
                # Filter to measurement window only (skip warmup: first 180s)
                if len(tdf) and tdf["t_off"].max() > 180:
                    tdf = tdf[tdf["t_off"] >= 180]
                if len(tdf):
                    row["throughput"] = float(tdf["ops"].mean())
                    row["throughput_std"] = float(tdf["ops"].std(ddof=1)) if len(tdf) > 1 else 0.0
                    row["throughput_nsamples"] = len(tdf)
                else:
                    row["throughput"] = 0.0
                    row["throughput_std"] = 0.0
                    row["throughput_nsamples"] = 0
            else:
                row["throughput"] = 0.0
                row["throughput_std"] = 0.0
                row["throughput_nsamples"] = 0
            lpath = manifest.parent / "latency.csv"
            if lpath.exists():
                ldf = pd.read_csv(lpath, on_bad_lines="skip")
                for metric in ("avg", "p50", "p95", "p99", "p99_9", "max"):
                    v = ldf[ldf["metric"] == metric]["value_us"]
                    row[f"lat_{metric}_us"] = float(v.iloc[0]) if len(v) and pd.notna(v.iloc[0]) else None

            # Tag rows from systems with known methodology limitations
            row["methodology_alert"] = system in METHODOLOGY_ALERTS

            rows.append(row)
        except Exception as e:
            log.warning("skip corrupt %s: %s", manifest, e)
    df = pd.DataFrame(rows)
    if df.empty:
        return df
    # Ensure standard columns exist even if no runs produced them
    for col in ["throughput_std", "throughput_nsamples", "methodology_alert"]:
        if col not in df.columns:
            df[col] = 0.0 if "throughput" in col else False
    return df


def load_leader_election(results_dir: Path) -> pd.DataFrame:
    """One row per leader-election trial across all systems."""
    rows = []
    for p in sorted(results_dir.glob("*/leader_election/*/trial.txt")):
        text = p.read_text().strip()
        parts = text.split(",")
        if len(parts) != 3:
            continue
        sys_, run_id, failover = parts
        f = float(failover) if failover != "timeout" else float("nan")
        rows.append({"system": sys_, "run_id": run_id, "failover_s": f})
    return pd.DataFrame(rows)


# ---------------------------------------------------------------------------
# Statistics
# ---------------------------------------------------------------------------

def bootstrap_ci(values: np.ndarray, n: int = 2000, ci: float = 0.95):
    """Bootstrap 95% CI for the sample mean. Deterministic (seeded)."""
    if len(values) < 2:
        return (float("nan"), float("nan"))
    rng = np.random.default_rng(42)
    idx = rng.integers(0, len(values), size=(n, len(values)))
    means = values[idx].mean(axis=1)
    alpha = (1 - ci) / 2
    return float(np.quantile(means, alpha)), float(np.quantile(means, 1 - alpha))


def summarise(df: pd.DataFrame) -> pd.DataFrame:
    """Per (system, scenario, metric) summary statistics."""
    out = []
    metrics = ["throughput", "lat_avg_us", "lat_p95_us", "lat_p99_us", "lat_p99_9_us"]
    for (system, scenario), g in df.groupby(["system", "scenario"]):
        for metric in metrics:
            if metric not in g.columns:
                continue
            vals = g[metric].dropna().to_numpy()
            if len(vals) == 0:
                continue

            is_zero_var = (system, metric) in ZERO_VARIANCE_SYSTEM_METRICS
            lo, hi = bootstrap_ci(vals)
            std_val = float(np.std(vals, ddof=1)) if len(vals) > 1 else 0.0

            out.append({
                "system": system,
                "scenario": scenario,
                "metric": metric,
                "n": len(vals),
                "mean": float(np.mean(vals)),
                "median": float(np.median(vals)),
                "std": std_val,
                "ci95_low": lo,
                "ci95_high": hi,
                "zero_variance": is_zero_var,
            })
    return pd.DataFrame(out)


def mannwhitney_pairs(df: pd.DataFrame,
                      scenario: str = "baseline",
                      metric: str = "throughput") -> pd.DataFrame:
    """
    Pairwise Mann-Whitney U test.

    The runs of different systems are INDEPENDENT samples (different cluster
    builds, different absolute timestamps, not paired by any experimental
    unit). The correct test for independent samples is Mann-Whitney U, NOT
    paired Wilcoxon.

    Zero-variance data (e.g. HotStuff latency percentiles filled from mean,
    etcd throughput approximated from total rate) is detected and flagged.
    Pairwise tests involving zero-variance data are still computed but
    annotated with `reliable=False` since the test assumes continuous
    distributions and ties from synthetic data violate that assumption.
    """
    systems = sorted(df["system"].unique())
    rows = []
    for i, a in enumerate(systems):
        for b in systems[i + 1:]:
            va = df[(df["system"] == a) & (df["scenario"] == scenario)][metric].dropna().to_numpy()
            vb = df[(df["system"] == b) & (df["scenario"] == scenario)][metric].dropna().to_numpy()
            if len(va) < 3 or len(vb) < 3:
                continue

            # Check if either side has zero variance (synthetic data)
            zero_a = (a, metric) in ZERO_VARIANCE_SYSTEM_METRICS or (
                len(va) >= 2 and np.std(va, ddof=1) == 0)
            zero_b = (b, metric) in ZERO_VARIANCE_SYSTEM_METRICS or (
                len(vb) >= 2 and np.std(vb, ddof=1) == 0)
            reliable = not (zero_a or zero_b)

            # Mann-Whitney U with exact p-value for small samples
            try:
                if zero_a and zero_b:
                    u, p = float("nan"), float("nan")
                elif len(va) <= 20 and len(vb) <= 20:
                    u, p = stats.mannwhitneyu(va, vb, alternative="two-sided", method="exact")
                else:
                    u, p = stats.mannwhitneyu(va, vb, alternative="two-sided")
            except Exception:
                u, p = float("nan"), float("nan")

            mean_a, mean_b = float(va.mean()), float(vb.mean())
            effect = 100 * (mean_a - mean_b) / mean_b if mean_b else float("nan")

            # Vargha-Delaney A: probability that a random value from A exceeds
            # a random value from B. A = U / (n_a * n_b). A=0.5 = no effect,
            # A>0.56 small, A>0.64 medium, A>0.71 large. Sample-size-independent.
            a_effect = (u / (len(va) * len(vb))) if not np.isnan(u) else float("nan")

            rows.append({
                "a": a, "b": b,
                "scenario": scenario, "metric": metric,
                "n_a": len(va), "n_b": len(vb),
                "mean_a": mean_a, "mean_b": mean_b,
                "effect_pct": effect,
                "vargha_delaney_a": a_effect,
                "u_statistic": u, "p_value": p,
                "reliable": reliable,
            })
    return pd.DataFrame(rows)


# ---------------------------------------------------------------------------
# Plots
# ---------------------------------------------------------------------------

def _style():
    plt.rcParams.update({
        "figure.dpi": 120,
        "savefig.dpi": 150,
        "figure.facecolor": "white",
        "axes.facecolor": "white",
        "axes.grid": True,
        "grid.alpha": 0.3,
        "font.size": 10,
    })


def fig01_throughput(df: pd.DataFrame, out: Path):
    """Hero: throughput bar chart, baseline, all systems, 95% CI error bars."""
    sub = df[df["scenario"] == "baseline"]
    if sub.empty:
        return
    systems = sorted(sub["system"].unique())
    means, errs_lo, errs_hi = [], [], []
    for s in systems:
        vals = sub[sub["system"] == s]["throughput"].dropna().to_numpy()
        m = float(vals.mean())
        lo, hi = bootstrap_ci(vals)
        means.append(m)
        errs_lo.append(m - lo)
        errs_hi.append(hi - m)
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.bar(systems, means,
           yerr=[errs_lo, errs_hi],
           capsize=6, color="#38bdf8", edgecolor="#0f172a")
    ax.set_ylabel("Throughput (ops/s)")
    ax.set_title("Throughput at baseline (mean ± 95% CI)")
    fig.tight_layout()
    fig.savefig(out / "fig01_throughput.png")
    plt.close(fig)


def fig02_latency_cdf(df: pd.DataFrame, out: Path):
    """Hero: p99 latency CDF across runs, one curve per system.

    HotStuff latency percentiles are mean-only (documented limitation).
    The CDF for HotStuff will be a vertical line at the mean; systems
    with true percentile data will show genuine CDF curves.
    A methodology annotation is written alongside the plot.
    """
    sub = df[df["scenario"] == "baseline"]
    if sub.empty or "lat_p99_us" not in sub.columns:
        return
    fig, ax = plt.subplots(figsize=(6, 4))
    for s in sorted(sub["system"].unique()):
        vals = sub[sub["system"] == s]["lat_p99_us"].dropna().to_numpy() / 1000.0
        if len(vals) < 2:
            continue
        vals = np.sort(vals)
        y = np.arange(1, len(vals) + 1) / len(vals)
        label = s
        if s == "hotstuff":
            label = s + " (mean-only)"
        ax.plot(vals, y, marker="o", label=label, linewidth=1.8)
    ax.set_xlabel("p99 write latency (ms)")
    ax.set_ylabel("CDF across runs")
    ax.set_title("p99 latency CDF (baseline)")
    ax.set_xscale("log")
    ax.legend()
    ax.annotate("HotStuff reports mean-only latency;\npercentiles are not measured.",
                xy=(0.02, 0.02), xycoords="axes fraction",
                fontsize=8, fontstyle="italic", color="gray",
                bbox=dict(boxstyle="round,pad=0.3", facecolor="white", alpha=0.7))
    fig.tight_layout()
    fig.savefig(out / "fig02_latency_cdf.png")
    plt.close(fig)


def fig03_leader_election(le: pd.DataFrame, out: Path):
    """Hero: leader-election CDF. One curve per system."""
    if le.empty:
        return
    fig, ax = plt.subplots(figsize=(6, 4))
    for s in sorted(le["system"].unique()):
        vals = le[le["system"] == s]["failover_s"].dropna().to_numpy()
        if len(vals) == 0:
            continue
        vals = np.sort(vals)
        y = np.arange(1, len(vals) + 1) / len(vals)
        ax.plot(vals, y, label=f"{s} (n={len(vals)})", linewidth=1.8)
    ax.set_xlabel("Failover time (seconds)")
    ax.set_ylabel("CDF")
    ax.set_title("Leader-election failover time")
    ax.set_xscale("log")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out / "fig03_leader_election.png")
    plt.close(fig)


def fig04_under_fault(df: pd.DataFrame, out: Path):
    """Hero: grouped bar chart of throughput under each scenario, per system."""
    if df.empty:
        return
    pivot = df.groupby(["scenario", "system"])["throughput"].mean().unstack()
    if pivot.empty:
        return
    fig, ax = plt.subplots(figsize=(7, 4))
    pivot.plot(kind="bar", ax=ax, edgecolor="#0f172a")
    ax.set_ylabel("Mean throughput (ops/s)")
    ax.set_title("Throughput under each fault scenario")
    ax.legend(title="system")
    plt.xticks(rotation=30, ha="right")
    fig.tight_layout()
    fig.savefig(out / "fig04_under_fault.png")
    plt.close(fig)


def fig05_kraft_vs_zk(df: pd.DataFrame, out: Path):
    """Hero: direct KRaft vs ZK head-to-head across scenarios."""
    sub = df[df["system"].isin(["kraft", "zk"])]
    if sub.empty:
        return
    grouped = sub.groupby(["scenario", "system"])["throughput"].mean().unstack()
    if "kraft" not in grouped.columns or "zk" not in grouped.columns:
        return
    fig, ax = plt.subplots(figsize=(7, 4))
    grouped.plot(kind="bar", ax=ax,
                 color=["#38bdf8", "#a78bfa"], edgecolor="#0f172a")
    ax.set_ylabel("Mean throughput (ops/s)")
    ax.set_title("Kafka KRaft vs Kafka+ZK across scenarios")
    ax.legend(title="mode")
    plt.xticks(rotation=30, ha="right")
    fig.tight_layout()
    fig.savefig(out / "fig05_kraft_vs_zk.png")
    plt.close(fig)


def fig06_scalability(df: pd.DataFrame, out: Path):
    """Hero: scalability curve. Throughput vs cluster size for kraft + etcd."""
    sub = df[(df["scenario"] == "baseline") &
             (df["system"].isin(["kraft", "etcd"]))]
    if sub.empty:
        return
    grouped = (sub.groupby(["system", "cluster_size"])["throughput"]
                 .mean().reset_index())
    if grouped.empty:
        return
    fig, ax = plt.subplots(figsize=(6, 4))
    for s in sorted(grouped["system"].unique()):
        g = grouped[grouped["system"] == s].sort_values("cluster_size")
        if len(g) == 0:
            continue
        ax.plot(g["cluster_size"], g["throughput"],
                marker="o", label=s, linewidth=2)
    ax.set_xlabel("Cluster size (nodes)")
    ax.set_ylabel("Throughput (ops/s)")
    ax.set_title("Throughput vs cluster size (baseline)")
    ax.set_xticks([3, 5, 7])
    ax.legend()
    fig.tight_layout()
    fig.savefig(out / "fig06_scalability.png")
    plt.close(fig)


def appendix_plots(df: pd.DataFrame, out: Path):
    """Per-scenario box plots (throughput + p99 latency)."""
    count = 0
    for scenario in sorted(df["scenario"].unique()):
        sub = df[df["scenario"] == scenario]
        if sub.empty:
            continue
        count += 1
        fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(10, 3.5))
        sub.boxplot(column="throughput", by="system", ax=ax1)
        ax1.set_title(f"Throughput - {scenario}")
        ax1.set_ylabel("ops/s")
        if "lat_p99_us" in sub.columns:
            sub.boxplot(column="lat_p99_us", by="system", ax=ax2)
            ax2.set_title(f"p99 latency - {scenario}")
            ax2.set_ylabel("microseconds")
        plt.suptitle("")
        fig.tight_layout()
        fig.savefig(out / f"appendix_{count:02d}_{scenario}.png")
        plt.close(fig)


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

def write_report(df: pd.DataFrame, summary: pd.DataFrame,
                 mw: pd.DataFrame, le: pd.DataFrame, out: Path):
    lines = []
    lines.append("# Campaign summary\n")
    lines.append(f"- Systems: {sorted(df['system'].unique().tolist())}")
    lines.append(f"- Scenarios: {sorted(df['scenario'].unique().tolist())}")
    lines.append(f"- Total valid runs: **{len(df)}**")
    if not le.empty:
        lines.append(f"- Leader-election trials: **{len(le)}** across "
                     f"{le['system'].nunique()} systems")
    lines.append("")
    lines.append("## Throughput at baseline\n")
    base = summary[(summary["scenario"] == "baseline") &
                   (summary["metric"] == "throughput")]
    if not base.empty:
        lines.append(base.to_markdown(index=False, floatfmt=".1f"))
    lines.append("")
    lines.append("## Pairwise Mann-Whitney U (baseline throughput)\n")
    lines.append("Two-sided H0: distributions are equal. "
                 "p < 0.05 suggests a real difference.\n")
    if not mw.empty:
        # Flag unreliable rows
        mw_display = mw.copy()
        mw_display["note"] = mw_display["reliable"].apply(
            lambda r: "" if r else "UNRELIABLE (zero-variance data)")
        lines.append(mw_display.to_markdown(index=False, floatfmt=".4f"))
    lines.append("")
    lines.append("## Methodology alerts\n")
    for system, alerts in METHODOLOGY_ALERTS.items():
        if system in df["system"].values:
            lines.append(f"- **{system}**: {', '.join(f'{m}: {d}' for m, d in alerts.items())}")
    (out / "report.md").write_text("\n".join(lines) + "\n")


def write_methodology(df: pd.DataFrame, summary: pd.DataFrame, out: Path):
    """Emit methodology.md — a ready-to-use methodology section for the thesis."""
    lines = []
    lines.append("# Experimental methodology — auto-generated alerts\n")
    lines.append("This file enumerates known methodology limitations that affect "
                 "the statistical validity of specific metrics. Include it "
                 "in the thesis methodology chapter as a table or section.\n")
    lines.append("## Per-system limitations\n")
    for system, alerts in METHODOLOGY_ALERTS.items():
        if system not in df["system"].values:
            continue
        lines.append(f"### {system}\n")
        for metric, description in alerts.items():
            lines.append(f"- **{metric}**: {description}")
        lines.append("")
    lines.append("## Zero-variance metrics\n")
    lines.append("The following (system, metric) pairs have zero or near-zero "
                 "variance due to data collection methodology, not due to "
                 "lack of real performance variance. Statistical tests "
                 "(Mann-Whitney U, bootstrap CI) on these pairs are marked "
                 "as `reliable=False` and should not be interpreted as "
                 "evidence for or against any hypothesis.\n")
    for sys_met in sorted(ZERO_VARIANCE_SYSTEM_METRICS):
        lines.append(f"- `{sys_met[0]}` / `{sys_met[1]}`")
    lines.append("")
    lines.append("## Skipped runs\n")
    lines.append("The analysis excludes runs with `status != 'complete'` "
                 "except for `double_kill` scenario runs which expect "
                 "zero throughput. Review `results/*/batch.log` for "
                 "details on any failed cells.\n")
    (out / "methodology.md").write_text("\n".join(lines) + "\n")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--results-dir", type=Path, default=Path("results"))
    parser.add_argument("--out", type=Path, default=Path("analysis/out"))
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "plots").mkdir(exist_ok=True)

    df = load_runs(args.results_dir)
    le = load_leader_election(args.results_dir)

    if df.empty:
        print(f"No valid runs found in {args.results_dir}")
        return

    _style()

    summary = summarise(df)
    summary.to_csv(args.out / "summary.csv", index=False)

    # Wide-format comparison table for the thesis body
    comparison = (summary[summary["metric"].isin(["throughput", "lat_p99_us"])]
                  .pivot_table(index=["scenario", "metric"],
                               columns="system", values="mean")
                  .reset_index())
    comparison.to_csv(args.out / "comparison.csv", index=False)

    mw = mannwhitney_pairs(df, "baseline", "throughput")
    mw.to_csv(args.out / "pairwise_tests.csv", index=False)

    if not le.empty:
        le.to_csv(args.out / "leader_election.csv", index=False)

    fig01_throughput(df, args.out / "plots")
    fig02_latency_cdf(df, args.out / "plots")
    fig03_leader_election(le, args.out / "plots")
    fig04_under_fault(df, args.out / "plots")
    fig05_kraft_vs_zk(df, args.out / "plots")
    fig06_scalability(df, args.out / "plots")
    appendix_plots(df, args.out / "plots")

    write_report(df, summary, mw, le, args.out)
    write_methodology(df, summary, args.out)

    print(f"Done. {len(df)} runs, {len(le)} leader-election trials.")
    print(f"Output: {args.out}")


if __name__ == "__main__":
    main()
