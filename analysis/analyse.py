#!/usr/bin/env python3
"""analyse.py — v2 foundation (F15's in-repo successor; grows into M6.4).

Walks a collected results tree (the harness's CSV/manifest contract),
aggregates per-run rows into per-cell summaries, and writes analysis CSVs.
Estimation-first per the methodology: median/IQR + mean with a bootstrap CI
(10,000 resamples, fixed seed); Mann-Whitney/Holm and the eight figures
join later on top of these tables.

HONESTY RULES BUILT IN (DATA_ANALYSIS_METHODOLOGY):
 * percentiles are NEVER averaged across runs — per-run percentiles are
   reported as a spread; pooled-distribution numbers come only from merging
   the per-run .hlog histograms, which needs the optional `hdrhistogram`
   package (`pip install hdrhistogram`). Without it this script REFUSES to
   print pooled percentiles rather than fake them.
 * environment=local runs are EXCLUDED by default (laptop numbers are never
   thesis data) — every exclusion is listed with its reason in excluded.csv,
   never silent (the no-silent-outlier-removal rule).
 * a run with status != complete is excluded and listed, never averaged in.

Usage:
  python3 analyse.py <results_root> [--out DIR] [--include-local]
  python3 analyse.py --selftest        # synthetic tree end-to-end check
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import statistics
import sys
from pathlib import Path

BOOTSTRAP_N = 10_000
SEED = 42


# ---------------------------------------------------------------- loading

def load_runs(root: Path, include_local: bool):
    """Yield (run_dict, excluded_reason|None) for every manifest under root."""
    for manifest in sorted(root.rglob("manifest.json")):
        d = manifest.parent
        try:
            m = json.loads(manifest.read_text())
        except (OSError, json.JSONDecodeError) as e:
            yield {"dir": str(d)}, f"unreadable manifest: {e}"
            continue
        run = {
            "dir": str(d),
            "system": m.get("system"),
            "scenario": m.get("scenario"),
            "cluster_size": m.get("cluster_size"),
            "conflict_ratio": m.get("conflict_ratio", 0.0),
            "rate_ops_s": m.get("rate_ops_s"),
            "run_id": m.get("run_id"),
            "environment": m.get("environment"),
            "status": m.get("status"),
            "error_rate": m.get("error_rate"),
            "failover_ms": m.get("failover_ms"),
            "ops_after_warmup": m.get("ops_after_warmup"),
            "duration_secs": m.get("duration_secs"),
            "warmup_secs": m.get("warmup_secs"),
        }
        if run["status"] != "complete":
            yield run, f"status={run['status']}"
            continue
        if run["environment"] == "local" and not include_local:
            yield run, "environment=local (laptop numbers are never thesis data)"
            continue
        run["latency_us"] = read_latency(d)
        run["throughput"] = read_throughput(d, run)
        yield run, None


def read_latency(run_dir: Path):
    """latency.csv -> {metric: value_us}; absent file -> {} (HotStuff runs)."""
    f = run_dir / "latency.csv"
    if not f.exists():
        return {}
    out = {}
    with f.open() as fh:
        for row in csv.DictReader(fh):
            out[row["metric"]] = float(row["value_us"])
    return out


def read_throughput(run_dir: Path, run):
    """Post-warmup mean ops/s + CoV from throughput.csv; {} when absent."""
    f = run_dir / "throughput.csv"
    if not f.exists():
        return {}
    warmup = run.get("warmup_secs") or 0
    duration = run.get("duration_secs")
    vals = []
    with f.open() as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            t, ops = line.split(",")
            t = int(t)
            if t >= warmup and (duration is None or t < duration):
                vals.append(float(ops))
    if not vals:
        return {}
    mean = statistics.fmean(vals)
    cov = (statistics.pstdev(vals) / mean) if mean > 0 else float("inf")
    return {"mean_ops_s": mean, "cov": cov, "seconds": len(vals)}


# ---------------------------------------------------------------- stats

def bootstrap_ci(values, rng=None, n=BOOTSTRAP_N):
    """95% bootstrap CI of the mean. n<2 -> the honest (v, v) degenerate CI."""
    if len(values) == 1:
        return values[0], values[0]
    rng = rng or random.Random(SEED)
    means = sorted(
        statistics.fmean(rng.choices(values, k=len(values))) for _ in range(n))
    return means[int(0.025 * n)], means[int(0.975 * n)]


def summarize_cell(runs):
    """One results row per cell; percentiles reported as a per-run spread."""
    tp = [r["throughput"]["mean_ops_s"] for r in runs if r.get("throughput")]
    lat_mean = [r["latency_us"]["avg"] for r in runs if r.get("latency_us")]
    p50s = [r["latency_us"]["p50"] for r in runs if r.get("latency_us")]
    p99s = [r["latency_us"]["p99"] for r in runs if r.get("latency_us")]
    failovers = [r["failover_ms"] for r in runs if r.get("failover_ms") is not None]
    row = {"n": len(runs)}
    if tp:
        lo, hi = bootstrap_ci(tp)
        row.update(tp_mean=round(statistics.fmean(tp), 1),
                   tp_median=round(statistics.median(tp), 1),
                   tp_ci_lo=round(lo, 1), tp_ci_hi=round(hi, 1))
    if lat_mean:
        lo, hi = bootstrap_ci(lat_mean)
        row.update(lat_mean_us=round(statistics.fmean(lat_mean), 1),
                   lat_mean_ci_lo=round(lo, 1), lat_mean_ci_hi=round(hi, 1))
    if p50s:
        # A SPREAD, deliberately not an average (methodology §3).
        row.update(p50_spread_us="|".join(str(int(v)) for v in sorted(p50s)),
                   p99_spread_us="|".join(str(int(v)) for v in sorted(p99s)))
    if failovers:
        row.update(failover_ms_all="|".join(str(int(v)) for v in sorted(failovers)),
                   failover_median_ms=statistics.median(failovers))
    return row


# ---------------------------------------------------------------- main

def analyse(root: Path, out: Path, include_local: bool) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    included, excluded = [], []
    for run, reason in load_runs(root, include_local):
        (excluded if reason else included).append((run, reason))

    cells = {}
    for run, _ in included:
        key = (run["system"], run["scenario"], run["cluster_size"],
               run["conflict_ratio"], run["rate_ops_s"])
        cells.setdefault(key, []).append(run)

    write_csv(out / "runs.csv",
              [flat_run(r) for r, _ in included])
    write_csv(out / "excluded.csv",
              [{"dir": r.get("dir"), "reason": reason} for r, reason in excluded])
    cell_rows = []
    for key, runs in sorted(cells.items(), key=lambda kv: str(kv[0])):
        row = {"system": key[0], "scenario": key[1], "size": key[2],
               "conflict": key[3], "rate": key[4]}
        row.update(summarize_cell(runs))
        cell_rows.append(row)
    write_csv(out / "cells.csv", cell_rows)

    print(f"analysed {len(included)} runs into {len(cell_rows)} cells "
          f"({len(excluded)} excluded — see excluded.csv)")
    print("NOTE: pooled-histogram percentiles need `pip install hdrhistogram` "
          "(refusing to average per-run percentiles; spreads are in cells.csv)")
    return {"included": len(included), "excluded": len(excluded),
            "cells": len(cell_rows)}


def flat_run(r):
    keep = ["dir", "system", "scenario", "cluster_size", "conflict_ratio",
            "rate_ops_s", "run_id", "environment", "error_rate", "failover_ms",
            "ops_after_warmup"]
    row = {k: r.get(k) for k in keep}
    if r.get("throughput"):
        row["tp_mean_ops_s"] = round(r["throughput"]["mean_ops_s"], 1)
        row["tp_cov"] = round(r["throughput"]["cov"], 4)
    for m in ("avg", "p50", "p95", "p99", "max"):
        if r.get("latency_us", {}).get(m) is not None:
            row[f"lat_{m}_us"] = r["latency_us"][m]
    return row


def write_csv(path: Path, rows):
    fields = []
    for row in rows:
        for k in row:
            if k not in fields:
                fields.append(k)
    with path.open("w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=fields or ["empty"])
        w.writeheader()
        w.writerows(rows)


# ---------------------------------------------------------------- selftest

def selftest() -> int:
    """Synthetic tree end-to-end: known inputs -> asserted outputs."""
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        root, out = Path(td) / "results", Path(td) / "out"
        mk_run(root / "etcd/baseline/size3/r01", "etcd", "hetzner", "complete",
               tps=[300, 305, 295], p50=2000, avg=2500)
        mk_run(root / "etcd/baseline/size3/r02", "etcd", "hetzner", "complete",
               tps=[310, 300, 290], p50=2100, avg=2600)
        mk_run(root / "etcd/baseline/size3/r03", "etcd", "local", "complete",
               tps=[999], p50=1, avg=1)      # laptop — must be excluded
        mk_run(root / "etcd/baseline/size3/r04", "etcd", "hetzner", "failed",
               tps=[1], p50=1, avg=1)        # failed — must be excluded
        stats = analyse(root, out, include_local=False)
        assert stats == {"included": 2, "excluded": 2, "cells": 1}, stats
        cells = list(csv.DictReader((out / "cells.csv").open()))
        assert len(cells) == 1 and cells[0]["n"] == "2"
        assert cells[0]["p50_spread_us"] == "2000|2100", cells[0]
        tp_mean = float(cells[0]["tp_mean_us"] if "tp_mean_us" in cells[0]
                        else cells[0]["tp_mean"])
        assert abs(tp_mean - 300.0) < 1.0, tp_mean  # hand-computed
        excluded = (out / "excluded.csv").read_text()
        assert "environment=local" in excluded and "status=failed" in excluded
        print("selftest OK — exclusion rules, cell grouping, spreads, and the "
              "hand-computed mean all hold")
    return 0


def mk_run(d: Path, system, env, status, tps, p50, avg):
    d.mkdir(parents=True)
    (d / "manifest.json").write_text(json.dumps({
        "system": system, "scenario": "baseline", "cluster_size": 3,
        "conflict_ratio": 0.0, "rate_ops_s": 300, "run_id": d.name,
        "environment": env, "status": status, "error_rate": 0.0,
        "failover_ms": None, "ops_after_warmup": sum(tps),
        "duration_secs": len(tps), "warmup_secs": 0}))
    (d / "throughput.csv").write_text(
        "".join(f"{i},{v}\n" for i, v in enumerate(tps)))
    (d / "latency.csv").write_text(
        "metric,value_us\n" + f"avg,{avg}\np50,{p50}\np95,{p50 * 2}\n"
        + f"p99,{p50 * 3}\np99_9,{p50 * 4}\nmax,{p50 * 5}\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", nargs="?", help="collected results tree")
    ap.add_argument("--out", default="analysis-out")
    ap.add_argument("--include-local", action="store_true",
                    help="include environment=local runs (NEVER for thesis figures)")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        sys.exit(selftest())
    if not a.root:
        ap.error("results_root is required (or use --selftest)")
    analyse(Path(a.root), Path(a.out), a.include_local)


if __name__ == "__main__":
    main()
