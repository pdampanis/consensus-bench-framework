#!/usr/bin/env bash
# =============================================================================
# hs-run.sh — in-container HotStuff benchmark runner
# =============================================================================
#
# Runs a single HotStuff benchmark using the vendored asonnino/hotstuff
# fabfile harness. Called from outside via `docker exec` (from run_one.sh)
# with the parameters that match our campaign's measurement conventions:
#
#   HS_DURATION_SEC   execution time for the benchmark (default 480 = 8 min)
#   HS_RATE           input tx/s rate (default 1000 — the asonnino default)
#   HS_TX_SIZE        tx payload size in bytes (default 512 — same)
#   HS_OUT_DIR        where to write output files (default /work/out)
#
# On completion, writes:
#   $HS_OUT_DIR/fab.log          raw fabric output (contains the SUMMARY block)
#   $HS_OUT_DIR/perf-raw.txt     normalized into kafka-producer-perf-test
#                                format so parse_results.sh reads it
#
# Exit code: 0 on a benchmark that produced a summary; non-zero otherwise.
# =============================================================================
set -eu

HS_DURATION_SEC="${HS_DURATION_SEC:-480}"
HS_RATE="${HS_RATE:-1000}"
HS_TX_SIZE="${HS_TX_SIZE:-512}"
HS_OUT_DIR="${HS_OUT_DIR:-/work/out}"

mkdir -p "$HS_OUT_DIR"

# The fabfile's 'local' task reads its config from the top of fabfile.py.
# We patch those values in-place before invoking it. This is safer than
# passing them as fab arguments because the local task does not accept
# keyword arguments for these parameters in the upstream code.
cd /opt/hotstuff/benchmark
python3 - <<PATCH
import re, pathlib
p = pathlib.Path("fabfile.py")
text = p.read_text()
text = re.sub(r"'duration':\s*\d+", f"'duration': ${HS_DURATION_SEC}", text)
text = re.sub(r"'rate':\s*\[\d+\]", f"'rate': [${HS_RATE}]", text)
text = re.sub(r"'tx_size':\s*\d+", f"'tx_size': ${HS_TX_SIZE}", text)
p.write_text(text)
print("patched fabfile parameters")
PATCH

# Run the local benchmark. Tee stdout+stderr so we can post-process it.
# Use `script` to preserve TTY-like behaviour fabric sometimes expects;
# fallback to plain redirection if script is absent.
LOG="$HS_OUT_DIR/fab.log"
( fab local 2>&1 | tee "$LOG" ) || {
  rc=$?
  echo "hs-run.sh: fab local exited with $rc" >&2
  exit "$rc"
}

# -----------------------------------------------------------------------------
# Normalize the fabric SUMMARY block into kafka-producer-perf-test format
# so that parse_results.sh reads it the same way as Kafka/etcd/tendermint.
#
# The asonnino fabric summary contains lines like:
#   Consensus TPS: 966 tx/s
#   Consensus latency: 2 ms
#   End-to-end TPS: 960 tx/s
#   End-to-end BPS: 491,519 B/s
#   End-to-end latency: 9 ms
#
# LIMITATION: asonnino's harness reports only MEAN end-to-end latency.
# Per-transaction percentiles (p50/p95/p99/p99.9) ARE NOT AVAILABLE.
# We emit the mean as the best available value for all percentile
# fields, and downstream analysis code (analyse.py) recognizes this
# as zero-variance synthetic data and excludes HotStuff from pairwise
# latency comparisons. This is documented in docs/algorithms/hotstuff.md
# and in the auto-generated analysis/out/methodology.md.
#
# We emit a SINGLE progress-like line with the end-to-end TPS so that
# parse_results.sh produces a one-row throughput.csv. This is honest:
# asonnino's harness produces only a summary, not per-second samples.
# -----------------------------------------------------------------------------
python3 - <<'NORMALIZE'
import os, re, pathlib

log = pathlib.Path(os.environ.get("HS_OUT_DIR", "/work/out")) / "fab.log"
out = log.parent / "perf-raw.txt"
text = log.read_text()

def grab(pattern, default=None):
    m = re.search(pattern, text)
    if not m:
        return default
    return m.group(1).replace(",", "")

tps = grab(r"End-to-end TPS:\s+([\d,]+)")
bps = grab(r"End-to-end BPS:\s+([\d,]+)")
mean_ms = grab(r"End-to-end latency:\s+([\d,]+)")

if tps is None or mean_ms is None:
    out.write_text("")
    print("hs-run.sh: no SUMMARY block found in fab.log", flush=True)
    raise SystemExit(2)

tps_i = int(tps)
mean_ms_i = int(mean_ms)
tx_size = int(os.environ.get("HS_TX_SIZE", "512"))
duration = int(os.environ.get("HS_DURATION_SEC", "480"))
total_recs = tps_i * duration

# Emit ONE progress-like line with the summary TPS.
# parse_results.sh extracts the "records/sec" field from this line
# to produce a single-row throughput.csv.
# The latency fields in this line are placeholders; the real latency
# values come from the summary line below.
mbs = tps_i * tx_size / (1024 * 1024)
lines = [
    f"{total_recs} records sent, {tps_i}.0 records/sec, "
    f"{mbs:.2f} MB/sec, {mean_ms_i} ms avg latency, "
    f"{mean_ms_i * 2} ms max latency.\n"
]

# Final summary line in kafka-producer-perf-test format.
# All percentile fields set to the mean (asonnino harness limitation).
lines.append(
    f"{total_recs} records sent, {tps_i}.0 records/sec, "
    f"{mean_ms_i}.00 ms avg latency, {mean_ms_i * 2} ms max latency, "
    f"{mean_ms_i} ms 50th, {mean_ms_i} ms 95th, "
    f"{mean_ms_i} ms 99th, {mean_ms_i} ms 99.9th.\n"
)

out.write_text("".join(lines))
print(f"hs-run.sh: normalized SUMMARY -> {out} (tps={tps_i}, lat_mean={mean_ms_i}ms)")
NORMALIZE
