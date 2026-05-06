#!/usr/bin/env python3
"""
cmt_bench.py - CometBFT benchmark driver

CometBFT has no equivalent of kafka-producer-perf-test.sh or `etcd benchmark`.
This driver fills the gap: it drives parallel writes against one or more
validators via HTTP RPC (broadcast_tx_commit), measuring throughput and
per-operation latency.

Usage:
  python3 cmt_bench.py --rpc-url http://tm1:26657 \
                       --duration-sec 480 \
                       --clients 6 \
                       --val-size 1024 \
                       --out-raw /out/perf-raw.txt

Output format is chosen to be parseable by parse_results.sh. The raw file
contains lines of progress during the run plus a summary line at the end
with avg/p50/p95/p99/p99.9/max latency.
"""
import argparse
import json
import random
import string
import sys
import threading
import time
import urllib.parse
import urllib.request


class Stats:
    """Thread-safe accumulator of per-operation latency samples."""

    def __init__(self):
        self.latencies_us = []
        self.ops = 0
        self.errors = 0
        self._lock = threading.Lock()

    def record(self, lat_us, ok):
        with self._lock:
            if ok:
                self.latencies_us.append(lat_us)
                self.ops += 1
            else:
                self.errors += 1


def worker(rpc_url, stop_event, stats, val_size, worker_id):
    """One client thread. Sends broadcast_tx_commit in a tight loop."""
    counter = 0
    while not stop_event.is_set():
        tx_bytes = f"w{worker_id}c{counter}:".encode() + b"x" * val_size
        tx_hex = tx_bytes.hex()
        url = f"{rpc_url}/broadcast_tx_commit?tx=0x{tx_hex}"

        t0 = time.perf_counter_ns()
        ok = False
        try:
            with urllib.request.urlopen(url, timeout=10) as r:
                body = r.read()
                resp = json.loads(body)
                if resp.get("result", {}).get("check_tx", {}).get("code", -1) == 0 \
                   and resp.get("result", {}).get("deliver_tx", {}).get("code", -1) == 0:
                    ok = True
        except Exception:
            ok = False
        t1 = time.perf_counter_ns()

        lat_us = (t1 - t0) // 1000
        stats.record(lat_us, ok)
        counter += 1


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--rpc-url", required=True)
    p.add_argument("--duration-sec", type=int, required=True)
    p.add_argument("--clients", type=int, default=6)
    p.add_argument("--val-size", type=int, default=1024)
    p.add_argument("--out-raw", required=True)
    args = p.parse_args()

    stats = Stats()
    stop = threading.Event()

    # Progress reporter: every 5 seconds, print an OMB-style progress line.
    # parse_results.sh ignores CometBFT progress lines (it only reads the
    # summary line) but having them in the raw file is useful for debugging.
    def progress():
        t_start = time.time()
        last_ops = 0
        next_tick = t_start + 5
        with open(args.out_raw, "w") as f:
            while not stop.is_set():
                now = time.time()
                if now >= next_tick:
                    ops_now = stats.ops
                    delta = ops_now - last_ops
                    # Emit in the exact format kafka-producer-perf-test.sh
                    # uses for its 5-second progress lines, so
                    # parse_results.sh reads it with the existing parser.
                    #   "N records sent, R records/sec, M MB/sec, L ms avg latency, X ms max latency."
                    # The exact token parse_results.sh keys on is "records/sec,"
                    # (with the trailing comma).
                    rate = delta / 5.0
                    mbs = rate * args.val_size / 1_048_576
                    line = (f"{ops_now} records sent, {rate:.1f} records/sec, "
                            f"{mbs:.2f} MB/sec, 0 ms avg latency, 0 ms max latency.\n")
                    f.write(line)
                    f.flush()
                    last_ops = ops_now
                    next_tick += 5
                time.sleep(0.5)

    prog = threading.Thread(target=progress, daemon=True)
    prog.start()

    threads = [
        threading.Thread(target=worker,
                         args=(args.rpc_url, stop, stats,
                               args.val_size, i),
                         daemon=True)
        for i in range(args.clients)
    ]
    for t in threads:
        t.start()

    time.sleep(args.duration_sec)
    stop.set()
    for t in threads:
        t.join(timeout=5)

    # Compute final statistics and append the summary line in the same
    # format kafka-producer-perf-test.sh uses, so parse_results.sh can
    # read it with the existing parser.
    lat = sorted(stats.latencies_us)
    n = len(lat)
    if n == 0:
        summary = "0 records sent, 0 records/sec, 0 ms avg latency, 0 ms max latency, 0 ms 50th, 0 ms 95th, 0 ms 99th, 0 ms 99.9th.\n"
    else:
        avg_ms = sum(lat) / n / 1000
        def pct(p):
            i = min(n - 1, int(n * p / 100))
            return lat[i] / 1000
        summary = (
            f"{stats.ops} records sent, {stats.ops / args.duration_sec:.1f} records/sec, "
            f"{avg_ms:.2f} ms avg latency, {lat[-1] / 1000:.0f} ms max latency, "
            f"{pct(50):.0f} ms 50th, {pct(95):.0f} ms 95th, "
            f"{pct(99):.0f} ms 99th, {pct(99.9):.0f} ms 99.9th.\n"
        )

    with open(args.out_raw, "a") as f:
        f.write(summary)

    print(f"cmt_bench: ops={stats.ops} errors={stats.errors} "
          f"throughput={stats.ops / args.duration_sec:.1f} ops/s",
          file=sys.stderr)


if __name__ == "__main__":
    main()
