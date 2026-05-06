#!/usr/bin/env python3
"""
paxi_bench.py - Paxi HTTP benchmark driver

Paxi (github.com/ailidani/paxi) exposes a RESTful HTTP API on each replica
at http://<host>:8080/. We drive it directly from this script instead of
using Paxi's built-in `client` binary because:

  1. Paxi's client output format differs from kafka-producer-perf-test's,
     and rather than write a second parser we produce the same output
     format cmt_bench.py does — which parse_results.sh already handles.
  2. We want nanosecond-precision per-op latency samples for the same
     avg/p50/p95/p99/p99.9 percentile set the other systems produce.
  3. Concurrency and value-size are configurable here so all four systems
     run with matched parameters (6 concurrent clients, 1 KB values).

API:
  PUT a value:  PUT /<key>  body: value
  GET a value:  GET /<key>
  DEL a value:  DELETE /<key>

The script issues PUTs with 6 concurrent workers for `duration_sec`
seconds and writes perf-raw.txt in the kafka-perf-test format so
parse_results.sh reads it with the existing parser.
"""
import argparse
import random
import string
import sys
import threading
import time
import urllib.request


class Stats:
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


def worker(base_url, stop_event, stats, val_size, worker_id):
    """One worker thread; issues PUTs in a tight loop."""
    counter = 0
    payload = b"x" * val_size
    while not stop_event.is_set():
        key = f"w{worker_id}k{counter}"
        url = f"{base_url}/{key}"
        req = urllib.request.Request(url, data=payload, method="PUT")

        t0 = time.perf_counter_ns()
        ok = False
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                r.read()
                ok = True
        except Exception:
            ok = False
        t1 = time.perf_counter_ns()

        lat_us = (t1 - t0) // 1000
        stats.record(lat_us, ok)
        counter += 1


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", required=True,
                   help="Paxi HTTP base URL, e.g. http://paxi1:8080")
    p.add_argument("--duration-sec", type=int, required=True)
    p.add_argument("--clients", type=int, default=6)
    p.add_argument("--val-size", type=int, default=1024)
    p.add_argument("--out-raw", required=True)
    args = p.parse_args()

    stats = Stats()
    stop = threading.Event()

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
                    rate = delta / 5.0
                    mbs = rate * args.val_size / 1_048_576
                    # Kafka-perf-test format so parse_results.sh reads it
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
                         args=(args.base_url, stop, stats,
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

    # Summary line in kafka-perf-test format
    lat = sorted(stats.latencies_us)
    n = len(lat)
    if n == 0:
        summary = ("0 records sent, 0 records/sec, 0 ms avg latency, "
                   "0 ms max latency, 0 ms 50th, 0 ms 95th, 0 ms 99th, "
                   "0 ms 99.9th.\n")
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

    print(f"paxi_bench: ops={stats.ops} errors={stats.errors} "
          f"throughput={stats.ops / args.duration_sec:.1f} ops/s",
          file=sys.stderr)


if __name__ == "__main__":
    main()
