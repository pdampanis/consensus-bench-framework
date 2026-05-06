"""
conftest.py — shared benchmark configuration and utilities.

Single source of truth for system names, cluster sizes, scenarios,
metric definitions, and methodology limitations. Imported by
analyse.py and available for any future Python scripts in the bundle.

This replaces scattered string literals across bash scripts and Python
with a single authoritative definition. The bash scripts reference
these values via comments pointing here.
"""

from __future__ import annotations

import os
from typing import Dict, List, Set, Tuple

# ---------------------------------------------------------------------------
# Systems under test
# ---------------------------------------------------------------------------

SYSTEMS: List[str] = [
    "kraft",
    "zk",
    "etcd",
    "tendermint",
    "paxos",
    "epaxos",
    "hotstuff",
]

# Default cluster size per system
DEFAULT_CLUSTER_SIZE: Dict[str, int] = {
    "kraft": 3,
    "zk": 3,
    "etcd": 3,
    "tendermint": 4,
    "paxos": 3,
    "epaxos": 3,
    "hotstuff": 4,
}

# Fault model per system (CFT = crash fault tolerant, BFT = Byzantine)
FAULT_MODEL: Dict[str, str] = {
    "kraft": "CFT",
    "zk": "CFT",
    "etcd": "CFT",
    "tendermint": "BFT",
    "paxos": "CFT",
    "epaxos": "CFT",
    "hotstuff": "BFT",
}

# Fault tolerance bound per system (F failures in N nodes)
FAULT_BOUND: Dict[str, str] = {
    "kraft": "F in 2F+1",
    "zk": "F in 2F+1",
    "etcd": "F in 2F+1",
    "tendermint": "F Byzantine in 3F+1",
    "paxos": "F in 2F+1",
    "epaxos": "F in 2F+1",
    "hotstuff": "F Byzantine in 3F+1",
}

# ---------------------------------------------------------------------------
# Scenarios
# ---------------------------------------------------------------------------

# Main campaign scenarios (sessions 1-3, 7-8)
SCENARIOS_MAIN: List[str] = [
    "baseline",
    "leader_kill",
    "packet_loss",
    "network_partition",
]

# Deep fault scenarios (session 5)
SCENARIOS_DEEP: List[str] = [
    "double_kill",
    "slow_node",
]

ALL_SCENARIOS: List[str] = SCENARIOS_MAIN + SCENARIOS_DEEP

# Scenario descriptions for thesis narrative
SCENARIO_DESCRIPTIONS: Dict[str, str] = {
    "baseline": "No faults; steady-state performance",
    "leader_kill": "Kill the current leader/proposer; measures failover",
    "packet_loss": "5% packet loss on all inter-node links",
    "network_partition": "Isolate one node from the rest",
    "double_kill": "Kill two nodes (crosses majority/quorum boundary)",
    "slow_node": "Stress one node's CPU to ~70%",
}

# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------

LATENCY_METRICS: List[str] = ["avg", "p50", "p95", "p99", "p99_9", "max"]

# Metrics used in summary statistics
SUMMARY_METRICS: List[str] = [
    "throughput",
    "lat_avg_us",
    "lat_p95_us",
    "lat_p99_us",
    "lat_p99_9_us",
]

# ---------------------------------------------------------------------------
# Methodology limitations (documented, not errors)
# ---------------------------------------------------------------------------

METHODOLOGY_ALERTS: Dict[str, Dict[str, str]] = {
    "hotstuff": {
        "lat_avg_us": "mean-only (asonnino harness reports only mean end-to-end latency)",
        "lat_p50_us": "set equal to mean (asonnino harness does not report percentiles)",
        "lat_p95_us": "set equal to mean (asonnino harness does not report percentiles)",
        "lat_p99_us": "set equal to mean (asonnino harness does not report percentiles)",
        "lat_p99_9_us": "set equal to mean (asonnino harness does not report percentiles)",
        "throughput": "summary-only (asonnino reports final TPS, not per-second samples)",
        "correctness": "no durability verification (asonnino client has no read API)",
    },
    "etcd": {
        "throughput": "approximated from total (etcd benchmark put emits only summary rate)",
    },
    "paxos": {
        "correctness": "sampled 18 keys only (Paxi has no scan API)",
    },
    "epaxos": {
        "correctness": "sampled 18 keys only (Paxi has no scan API)",
    },
}

# Metrics with zero/near-zero variance due to data collection methodology.
# The downstream analysis pipeline excludes these from pairwise statistical
# tests and marks them in output CSVs.
ZERO_VARIANCE_SYSTEM_METRICS: Set[Tuple[str, str]] = {
    ("hotstuff", "lat_p50_us"),
    ("hotstuff", "lat_p95_us"),
    ("hotstuff", "lat_p99_us"),
    ("hotstuff", "lat_p99_9_us"),
}

# ---------------------------------------------------------------------------
# Timing constants
# ---------------------------------------------------------------------------

WARMUP_SECS = 180
MEASURE_SECS = 300
TOTAL_SECS = WARMUP_SECS + MEASURE_SECS
LE_PROBE_DURATION = 20
LE_PROBE_BASELINE = 3
LE_TRIALS_PER_SYSTEM = 200
RUNS_PER_CELL = int(os.environ.get("THESIS_RUNS_PER_CELL", "5"))
RUNS_PER_BASELINE_CELL = int(os.environ.get("THESIS_RUNS_BASELINE", "10"))
RUNS_PER_SCALABILITY_CELL = int(os.environ.get("THESIS_RUNS_SCALABILITY", "3"))

# ---------------------------------------------------------------------------
# Output format contract
# ---------------------------------------------------------------------------
# Every run produces:
#   results/<system>/<scenario>/<run_id>/
#     manifest.json       {system, scenario, run_id, cluster_size, times, status,
#                          correctness, kernel, docker}
#     throughput.csv      t_off,ops_per_sec  (one row per sample)
#     latency.csv         metric,value_us    (avg, p50, p95, p99, p99_9, max)
#     correctness.txt     PASS/FAIL/NO_DURABILITY_API/SKIP with detail
#     run.log             combined stdout/stderr
#     perf-raw.txt        original benchmark tool output (for debugging)

# ---------------------------------------------------------------------------
# Docker image names
# ---------------------------------------------------------------------------

CUSTOM_IMAGES = {
    "paxi": "thesis-paxi:latest",
    "hotstuff": "thesis-hotstuff:latest",
    "le_probe": "le-probe:latest",
}

# Third-party images (pulled, not built)
THIRD_PARTY_IMAGES = {
    "kafka": "apache/kafka:3.7.0",
    "etcd": "quay.io/coreos/etcd:v3.5.15",
    "cometbft": "cometbft/cometbft:v0.38.12",
}
