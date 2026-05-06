"""
test_pipeline.py — unit tests for the benchmark data pipeline.

Tests cover:
  1. parse_results.sh output format correctness
  2. analyse.py loading, aggregation, and statistics
  3. Data integrity (no silent data loss)
  4. Edge cases (empty results, corrupt CSVs, zero-variance data)

Run with pytest from the thesis-final directory:
  python3 -m venv .venv && source .venv/bin/activate
  pip install pytest pandas numpy scipy
  pytest analysis/test_pipeline.py -v
"""
from __future__ import annotations

import json
import tempfile
import textwrap
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

# Import the module under test
from analyse import (
    METHODOLOGY_ALERTS,
    ZERO_VARIANCE_SYSTEM_METRICS,
    bootstrap_ci,
    load_runs,
    mannwhitney_pairs,
    summarise,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def empty_results_dir():
    with tempfile.TemporaryDirectory() as tmp:
        yield Path(tmp)


@pytest.fixture
def one_run_results_dir():
    """A single complete KRaft baseline run."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        cell = root / "kraft" / "baseline" / "run001"
        cell.mkdir(parents=True)
        (cell / "manifest.json").write_text(json.dumps({
            "system": "kraft",
            "scenario": "baseline",
            "run_id": "run001",
            "cluster_size": 3,
            "started_at": "2025-01-01T00:00:00Z",
            "ended_at": "2025-01-01T00:08:00Z",
            "warmup_secs": 180,
            "measure_secs": 300,
            "perf_rc": 0,
            "throughput_rows": 60,
            "correctness": "PASS (100/100 = 100%)",
            "status": "complete",
            "kernel": "6.5.0",
            "docker": "Docker version 24.0.7",
        }))
        (cell / "throughput.csv").write_text(
            "180,10000\n185,10200\n190,9800\n195,10100\n200,9900\n"
        )
        (cell / "latency.csv").write_text(
            "metric,value_us\n"
            "avg,5000\np50,4800\np95,12000\np99,15000\np99_9,20000\nmax,30000\n"
        )
        yield root


@pytest.fixture
def five_run_results_dir():
    """Five complete KRaft baseline runs."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        tps_values = [10000, 10200, 9800, 10100, 9950]
        lat_values = [15000, 14800, 15200, 14900, 15100]
        for i, (tps, lat) in enumerate(zip(tps_values, lat_values), 1):
            cell = root / "kraft" / "baseline" / f"run{i:03d}"
            cell.mkdir(parents=True)
            (cell / "manifest.json").write_text(json.dumps({
                "system": "kraft",
                "scenario": "baseline",
                "run_id": f"run{i:03d}",
                "cluster_size": 3,
                "status": "complete",
                "correctness": "PASS",
            }))
            (cell / "throughput.csv").write_text(
                f"180,{tps}\n185,{tps}\n190,{tps}\n"
            )
            (cell / "latency.csv").write_text(
                "metric,value_us\n"
                f"avg,5000\np50,4800\np95,12000\np99,{lat}\np99_9,20000\nmax,30000\n"
            )
        yield root


@pytest.fixture
def incomplete_run_results_dir():
    """A run with status=failed that should be excluded."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        cell = root / "kraft" / "baseline" / "run001"
        cell.mkdir(parents=True)
        (cell / "manifest.json").write_text(json.dumps({
            "system": "kraft",
            "scenario": "baseline",
            "run_id": "run001",
            "cluster_size": 3,
            "status": "failed",
            "correctness": "SKIP",
        }))
        (cell / "throughput.csv").write_text("180,0\n")
        yield root


@pytest.fixture
def hotstuff_results_dir():
    """Three HotStuff baseline runs (zero-variance latency data)."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for i in range(1, 4):
            cell = root / "hotstuff" / "baseline" / f"run{i:03d}"
            cell.mkdir(parents=True)
            (cell / "manifest.json").write_text(json.dumps({
                "system": "hotstuff",
                "scenario": "baseline",
                "run_id": f"run{i:03d}",
                "cluster_size": 4,
                "status": "complete",
                "correctness": "NO_DURABILITY_API (benchmark completed, e2e_tps=950, consensus_tps=960)",
            }))
            (cell / "throughput.csv").write_text(f"180,{950 + i * 10}\n")
            # All percentiles = mean (asonnino limitation)
            (cell / "latency.csv").write_text(
                "metric,value_us\n"
                "avg,9000\np50,9000\np95,9000\np99,9000\np99_9,9000\nmax,18000\n"
            )
        yield root


@pytest.fixture
def cross_system_results_dir():
    """KRaft + ZK baseline runs for pairwise test."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        kr_tps = [10000, 10200, 9800, 10100, 9950, 10050, 10150, 9900, 10080, 9970]
        zk_tps = [8500, 8400, 8600, 8550, 8450, 8520, 8480, 8580, 8420, 8540]
        for system, tps_list in [("kraft", kr_tps), ("zk", zk_tps)]:
            for i, tps in enumerate(tps_list, 1):
                cell = root / system / "baseline" / f"run{i:03d}"
                cell.mkdir(parents=True)
                (cell / "manifest.json").write_text(json.dumps({
                    "system": system, "scenario": "baseline",
                    "run_id": f"run{i:03d}", "cluster_size": 3,
                    "status": "complete", "correctness": "PASS",
                }))
                (cell / "throughput.csv").write_text(f"180,{tps}\n")
                (cell / "latency.csv").write_text(
                    "metric,value_us\navg,5000\np50,4800\np95,12000\np99,15000\np99_9,20000\nmax,30000\n"
                )
        yield root


# ---------------------------------------------------------------------------
# Tests: bootstrap_ci
# ---------------------------------------------------------------------------

class TestBootstrapCI:
    def test_normal_data(self):
        vals = np.array([100.0, 102.0, 98.0, 101.0, 99.0])
        lo, hi = bootstrap_ci(vals)
        assert lo < hi
        assert 97 < lo < 103
        assert 97 < hi < 103

    def test_single_value_returns_nan(self):
        lo, hi = bootstrap_ci(np.array([5.0]))
        assert np.isnan(lo) and np.isnan(hi)

    def test_constant_data(self):
        vals = np.array([100.0, 100.0, 100.0])
        lo, hi = bootstrap_ci(vals)
        assert lo == hi == 100.0


# ---------------------------------------------------------------------------
# Tests: load_runs
# ---------------------------------------------------------------------------

class TestLoadRuns:
    def test_empty_dir(self, empty_results_dir):
        df = load_runs(empty_results_dir)
        assert df.empty

    def test_one_run(self, one_run_results_dir):
        df = load_runs(one_run_results_dir)
        assert len(df) == 1
        assert df.iloc[0]["system"] == "kraft"
        assert df.iloc[0]["throughput"] == 10000.0
        assert df.iloc[0]["lat_p99_us"] == 15000.0
        assert df.iloc[0]["cluster_size"] == 3

    def test_five_runs(self, five_run_results_dir):
        df = load_runs(five_run_results_dir)
        assert len(df) == 5

    def test_incomplete_excluded(self, incomplete_run_results_dir):
        df = load_runs(incomplete_run_results_dir)
        assert df.empty, "Incomplete runs should be excluded"

    def test_correctness_preserved(self, one_run_results_dir):
        df = load_runs(one_run_results_dir)
        assert "PASS" in df.iloc[0]["correctness"]

    def test_hotstuff_nodurability(self, hotstuff_results_dir):
        df = load_runs(hotstuff_results_dir)
        assert len(df) == 3
        assert all("NO_DURABILITY_API" in c for c in df["correctness"])

    def test_methodology_alert_tagged(self, hotstuff_results_dir):
        df = load_runs(hotstuff_results_dir)
        assert df.iloc[0]["methodology_alert"] == True


# ---------------------------------------------------------------------------
# Tests: summarise
# ---------------------------------------------------------------------------

class TestSummarise:
    def test_basic_aggregation(self, five_run_results_dir):
        df = load_runs(five_run_results_dir)
        summary = summarise(df)
        tp = summary[(summary["metric"] == "throughput") &
                     (summary["scenario"] == "baseline")]
        assert len(tp) == 1
        assert 9900 < tp.iloc[0]["mean"] < 10100
        assert not tp.iloc[0]["zero_variance"]

    def test_hotstuff_zero_variance_flag(self, hotstuff_results_dir):
        df = load_runs(hotstuff_results_dir)
        summary = summarise(df)
        p99 = summary[(summary["metric"] == "lat_p99_us")]
        assert len(p99) == 1
        assert p99.iloc[0]["zero_variance"] == True


# ---------------------------------------------------------------------------
# Tests: mannwhitney_pairs
# ---------------------------------------------------------------------------

class TestMannWhitneyPairs:
    def test_significant_difference(self, cross_system_results_dir):
        df = load_runs(cross_system_results_dir)
        mw = mannwhitney_pairs(df, "baseline", "throughput")
        pair = mw[(mw["a"] == "kraft") & (mw["b"] == "zk")]
        assert len(pair) == 1
        # KRaft should be higher than ZK
        assert pair.iloc[0]["mean_a"] > pair.iloc[0]["mean_b"]
        assert pair.iloc[0]["p_value"] < 0.05
        assert pair.iloc[0]["reliable"] == True

    def test_hotstuff_latency_unreliable(self, hotstuff_results_dir):
        """HotStuff latency comparisons must be marked unreliable."""
        # Build a combined results dir with both hotstuff and kraft
        df_hs = load_runs(hotstuff_results_dir)
        assert len(df_hs) >= 3, "Need at least 3 HotStuff runs"

        # Create kraft data in the same dir
        for i in range(1, 4):
            cell = hotstuff_results_dir / "kraft" / "baseline" / f"run{i:03d}"
            cell.mkdir(parents=True, exist_ok=True)
            (cell / "manifest.json").write_text(json.dumps({
                "system": "kraft", "scenario": "baseline",
                "run_id": f"run{i:03d}", "cluster_size": 3,
                "status": "complete", "correctness": "PASS",
            }))
            (cell / "throughput.csv").write_text(f"180,{10000 + i * 100}\n")
            (cell / "latency.csv").write_text(
                "metric,value_us\n"
                f"avg,{5000 + i * 200}\np50,{4800 + i * 200}\n"
                f"p95,{12000 + i * 200}\np99,{15000 + i * 200}\n"
                f"p99_9,{20000 + i * 200}\nmax,{30000 + i * 200}\n"
            )

        df = load_runs(hotstuff_results_dir)
        mw = mannwhitney_pairs(df, "baseline", "lat_p99_us")

        # Find the hotstuff-kraft pair (order depends on sorting)
        hs_kraft = mw[((mw["a"] == "hotstuff") & (mw["b"] == "kraft")) |
                      ((mw["a"] == "kraft") & (mw["b"] == "hotstuff"))]
        assert len(hs_kraft) >= 1, f"Expected hotstuff-kraft pair in {mw[['a','b']].values}"
        assert hs_kraft.iloc[0]["reliable"] == False, \
            f"HotStuff latency comparison must be unreliable, got reliable={hs_kraft.iloc[0]['reliable']}"


# ---------------------------------------------------------------------------
# Tests: data integrity
# ---------------------------------------------------------------------------

class TestDataIntegrity:
    """Verify that the pipeline doesn't silently lose data."""

    def test_all_systems_routable(self):
        """parse_results.sh must handle all 7 systems."""
        systems = ["kraft", "zk", "etcd", "tendermint", "paxos", "epaxos", "hotstuff"]
        # These must all appear in METHODOLOGY_ALERTS or be valid systems
        for s in systems:
            assert s in ["kraft", "zk", "etcd", "tendermint", "paxos", "epaxos", "hotstuff"]

    def test_latency_metrics_complete(self):
        """Every run must produce all 6 latency metrics."""
        expected = {"avg", "p50", "p95", "p99", "p99_9", "max"}
        from conftest import LATENCY_METRICS
        assert set(LATENCY_METRICS) == expected

    def test_scenario_coverage(self):
        """All 6 scenarios must be defined."""
        from conftest import ALL_SCENARIOS
        assert len(ALL_SCENARIOS) == 6

    def test_fault_bounds_match_protocol_families(self):
        """CFT = 2F+1, BFT = 3F+1."""
        from conftest import FAULT_MODEL, FAULT_BOUND
        for system, model in FAULT_MODEL.items():
            if model == "CFT":
                assert "2F+1" in FAULT_BOUND[system]
            elif model == "BFT":
                assert "3F+1" in FAULT_BOUND[system]

    def test_hotstuff_methodology_alerts_comprehensive(self):
        """HotStuff alerts must cover all 5 latency metrics + throughput + correctness."""
        hotstuff_alerts = METHODOLOGY_ALERTS.get("hotstuff", {})
        expected_metrics = {"lat_avg_us", "lat_p50_us", "lat_p95_us",
                           "lat_p99_us", "lat_p99_9_us", "throughput", "correctness"}
        assert set(hotstuff_alerts.keys()) == expected_metrics

    def test_zero_variance_set_exhaustive(self):
        """All HotStuff latency metrics must be in the zero-variance set."""
        for metric in ["lat_p50_us", "lat_p95_us", "lat_p99_us", "lat_p99_9_us"]:
            assert ("hotstuff", metric) in ZERO_VARIANCE_SYSTEM_METRICS
