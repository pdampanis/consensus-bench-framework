#!/usr/bin/env bash
# =============================================================================
# parse_results.sh - normalize perf-tool output into canonical CSVs
# =============================================================================
#
# Produces two CSVs regardless of which tool ran:
#
#   throughput.csv  time_offset_sec,ops_per_sec    (per-second samples)
#   latency.csv     metric,value_us                (one row per percentile)
#
# The metrics in latency.csv are always these, in this order:
#   avg, p50, p95, p99, p99_9, max
#
# This is the "same metrics across systems" contract. Downstream code
# (analyse.py, the visualizer) does not need to know which tool produced
# the data.
# =============================================================================
set -u

SYSTEM="${1:?usage: $0 <s> <out_dir>}"
OUT="${2:?}"
RAW="$OUT/perf-raw.txt"

if [ ! -f "$RAW" ]; then
  echo "parse_results: missing $RAW" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Kafka: parse kafka-producer-perf-test.sh output.
#
# Per-second lines look like this (one line every 5 seconds by default):
#   45678 records sent, 9135.6 records/sec, 8.92 MB/sec, 12.34 ms avg latency, ...
#
# The summary line at the end of the run looks like this:
#   100000 records sent, 9876.5 records/sec, 9.64 MB/sec,
#     12.34 ms avg latency, 45.67 ms max latency,
#     7 ms 50th, 22 ms 95th, 38 ms 99th, 44 ms 99.9th.
# ---------------------------------------------------------------------------
if [ "$SYSTEM" = "kraft" ] || [ "$SYSTEM" = "zk" ] || [ "$SYSTEM" = "tendermint" ] || [ "$SYSTEM" = "paxos" ] || [ "$SYSTEM" = "epaxos" ] || [ "$SYSTEM" = "hotstuff" ]; then
  # throughput.csv: extract "X records/sec" from every progress line
  awk '
    /records\/sec/ && !/ms 50th/ {
      # Find "records/sec" field; the number before it is the rate
      for (i = 1; i <= NF; i++) {
        if ($i == "records/sec,") print (NR-1)*5 "," $(i-1)
      }
    }
  ' "$RAW" > "$OUT/throughput.csv"

  # latency.csv: parse the summary line. Only one such line per run, at
  # the end. All values in ms — convert to us by multiplying by 1000.
  python3 - <<PY > "$OUT/latency.csv"
import re, sys
text = open("$RAW").read()
# Summary line contains "ms 50th" and is typically the last line
m = re.search(
    r"([\d.]+)\s*ms\s*avg\s*latency,\s*"
    r"([\d.]+)\s*ms\s*max\s*latency,\s*"
    r"(\d+)\s*ms\s*50th,\s*"
    r"(\d+)\s*ms\s*95th,\s*"
    r"(\d+)\s*ms\s*99th,\s*"
    r"(\d+)\s*ms\s*99\.9th",
    text,
)
if not m:
    print("metric,value_us")
    sys.exit(0)
avg, mx, p50, p95, p99, p999 = [float(x) * 1000 for x in m.groups()]
print("metric,value_us")
print(f"avg,{avg:.0f}")
print(f"p50,{p50:.0f}")
print(f"p95,{p95:.0f}")
print(f"p99,{p99:.0f}")
print(f"p99_9,{p999:.0f}")
print(f"max,{mx:.0f}")
PY
fi

# ---------------------------------------------------------------------------
# etcd: parse `benchmark put` output.
#
# The summary looks like this:
#   Summary:
#     Total:        60.1234 secs.
#     Slowest:      0.0456 secs.
#     Fastest:      0.0001 secs.
#     Average:      0.0012 secs.
#     Stddev:       0.0023 secs.
#     Requests/sec: 8321.4567
#
#   Response time histogram:
#     0.0001 [1]     |
#     ...
#
#   Latency distribution:
#     10% in 0.0005 secs.
#     25% in 0.0008 secs.
#     50% in 0.0011 secs.
#     75% in 0.0015 secs.
#     90% in 0.0020 secs.
#     95% in 0.0024 secs.
#     99% in 0.0035 secs.
#     99.9% in 0.0089 secs.
#
# For throughput we want per-second samples, which the tool does not emit
# directly. We approximate by dividing the total into one bucket with the
# overall rate. This is a known limitation of the etcd benchmark tool;
# per-second resolution is only available via Prometheus metrics, which
# export_metrics.sh pulls separately.
# ---------------------------------------------------------------------------
if [ "$SYSTEM" = "etcd" ]; then
  python3 - <<PY > "$OUT/latency.csv"
import re, sys
text = open("$RAW").read()

def find_secs(label):
    m = re.search(rf"{label}:\s*([\d.]+)\s*secs?", text)
    return float(m.group(1)) if m else None

def find_pct(p):
    m = re.search(rf"{p}%\s*in\s*([\d.]+)\s*secs?", text)
    return float(m.group(1)) if m else None

avg = find_secs("Average")
mx  = find_secs("Slowest")
p50 = find_pct("50")
p95 = find_pct("95")
p99 = find_pct("99")
p999 = find_pct("99.9")

print("metric,value_us")
for name, val in (("avg", avg), ("p50", p50), ("p95", p95),
                  ("p99", p99), ("p99_9", p999), ("max", mx)):
    if val is not None:
        print(f"{name},{int(val * 1_000_000)}")
    else:
        print(f"{name},")
PY

  # Approximate per-second throughput from the total run.
  # The etcd benchmark tool emits only a summary rate, not per-second
  # samples. Each row in throughput.csv repeats the same mean rate.
  # Between-run variance (across the 5 repeated runs) is genuine;
  # within-run variance is zero by construction. analyse.py uses
  # between-run variance for summary statistics.
  python3 - <<PY > "$OUT/throughput.csv"
import re, sys
text = open("$RAW").read()
total_m = re.search(r"Total:\s*([\d.]+)\s*secs?", text)
rate_m = re.search(r"Requests/sec:\s*([\d.]+)", text)
if not (total_m and rate_m):
    sys.exit(0)
total_s = int(float(total_m.group(1)))
rate = float(rate_m.group(1))
# Emit one row per second; all rows have the same rate (summary only)
for t in range(total_s):
    print(f"{t},{rate:.1f}")
PY
fi

echo "parsed $RAW -> throughput.csv, latency.csv"
