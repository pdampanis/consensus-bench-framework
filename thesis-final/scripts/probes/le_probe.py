#!/usr/bin/env python3
"""
le_probe.py - external client probe for leader-election timing

Runs in its own container on thesis-net so it survives leader kill. Issues
one PUT per iteration at 50 Hz, records (timestamp_ns, success) for every
attempt. After the run, the caller reads the CSV and computes failover as
"time from kill-marker to next consecutive successful PUT".

Usage:
    python3 le_probe.py --system {kraft,zk,etcd} \
                        --endpoints <list> \
                        --duration-sec 20 \
                        --out /probe/probe.csv

CSV output format:
    timestamp_ns,success
    1729101234567890123,1
    1729101234587890123,0
    ...
"""
import argparse
import csv
import sys
import time


def probe_kafka(endpoints, duration_sec, out_path):
    from confluent_kafka import Producer

    delivery_status = {"success": False}

    def on_delivery(err, msg):
        if err is None:
            delivery_status["success"] = True

    producer = Producer({
        "bootstrap.servers": endpoints,
        "socket.timeout.ms": 2000,
        "request.timeout.ms": 2000,
        "message.timeout.ms": 2000,
        "retries": 0,
        "acks": "all",
    })

    results = []
    deadline = time.monotonic() + duration_sec
    iteration = 0
    while time.monotonic() < deadline:
        t0 = time.time_ns()
        delivery_status["success"] = False
        try:
            producer.produce(
                "bench",
                key=f"p{iteration}".encode(),
                value=b"x" * 128,
                on_delivery=on_delivery,
            )
            remaining = producer.flush(timeout=1.0)
            success = 1 if remaining == 0 and delivery_status["success"] else 0
        except Exception:
            success = 0
        results.append((t0, success))
        iteration += 1
        next_slot = t0 + 20_000_000
        slack = (next_slot - time.time_ns()) / 1e9
        if slack > 0:
            time.sleep(slack)

    _write_csv(results, out_path)


def probe_etcd(endpoints, duration_sec, out_path):
    import etcd3
    # Parse all endpoints and try each. The first one that responds becomes
    # the active connection. If the leader is killed mid-probe, the etcd3
    # client's internal failover handles reconnection to surviving members
    # discovered from the cluster membership.
    ep_list = [ep.strip() for ep in endpoints.split(",")]
    client = None
    last_err = None
    for ep in ep_list:
        try:
            host, port = ep.replace("http://", "").split(":")
            c = etcd3.client(host=host, port=int(port), timeout=1)
            c.status()  # quick connectivity check
            client = c
            break
        except Exception as e:
            last_err = e
            continue
    if client is None:
        raise RuntimeError(f"etcd probe: cannot connect to any endpoint {ep_list}: {last_err}")

    results = []
    deadline = time.monotonic() + duration_sec
    iteration = 0
    while time.monotonic() < deadline:
        t0 = time.time_ns()
        success = 0
        try:
            client.put(f"le/probe/{iteration}", "x" * 128)
            success = 1
        except Exception:
            success = 0
        results.append((t0, success))
        iteration += 1
        next_slot = t0 + 20_000_000
        slack = (next_slot - time.time_ns()) / 1e9
        if slack > 0:
            time.sleep(slack)

    _write_csv(results, out_path)


def probe_tendermint(endpoints, duration_sec, out_path):
    """
    Probes CometBFT via broadcast_tx_commit. endpoints is a comma-separated
    list like "http://tm1:26657,http://tm2:26657,...". On failure, rotates
    to the next endpoint so the probe survives leader-kill even if the
    killed validator was the one being queried.
    """
    import urllib.request

    ep_list = [ep.strip() for ep in endpoints.split(",")]
    active_ep = 0
    rpc_url = ep_list[active_ep]

    results = []
    deadline = time.monotonic() + duration_sec
    iteration = 0
    consecutive_failures = 0
    while time.monotonic() < deadline:
        t0 = time.time_ns()
        success = 0
        try:
            tx_hex = f"le{iteration}".encode().hex() + "00" * 32
            url = f"{rpc_url}/broadcast_tx_commit?tx=0x{tx_hex}"
            with urllib.request.urlopen(url, timeout=2) as r:
                r.read()
                success = 1
            consecutive_failures = 0
        except Exception:
            success = 0
            consecutive_failures += 1
            # Rotate endpoint after 3 consecutive failures
            if consecutive_failures >= 3:
                active_ep = (active_ep + 1) % len(ep_list)
                rpc_url = ep_list[active_ep]
                consecutive_failures = 0
        results.append((t0, success))
        iteration += 1
        next_slot = t0 + 20_000_000
        slack = (next_slot - time.time_ns()) / 1e9
        if slack > 0:
            time.sleep(slack)

    _write_csv(results, out_path)


def probe_paxi(endpoints, duration_sec, out_path):
    """
    Probes Paxi (running either Paxos or EPaxos via -algorithm) via its
    HTTP API. `endpoints` is a comma-separated list of base URLs.
    On failure, rotates to the next replica.

    For Paxos, the leader is one replica; killing it triggers re-election.
    For EPaxos, there is no leader — every replica can commit independently.
    """
    import urllib.request

    ep_list = [ep.strip() for ep in endpoints.split(",")]
    active_ep = 0
    base_url = ep_list[active_ep]
    payload = b"x" * 128

    results = []
    deadline = time.monotonic() + duration_sec
    iteration = 0
    consecutive_failures = 0
    while time.monotonic() < deadline:
        t0 = time.time_ns()
        success = 0
        try:
            req = urllib.request.Request(
                f"{base_url}/le{iteration}",
                data=payload,
                method="PUT",
            )
            with urllib.request.urlopen(req, timeout=2) as r:
                r.read()
                success = 1
            consecutive_failures = 0
        except Exception:
            success = 0
            consecutive_failures += 1
            if consecutive_failures >= 3:
                active_ep = (active_ep + 1) % len(ep_list)
                base_url = ep_list[active_ep]
                consecutive_failures = 0
        results.append((t0, success))
        iteration += 1
        next_slot = t0 + 20_000_000
        slack = (next_slot - time.time_ns()) / 1e9
        if slack > 0:
            time.sleep(slack)

    _write_csv(results, out_path)


def _write_csv(results, out_path):
    with open(out_path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["timestamp_ns", "success"])
        w.writerows(results)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--system", required=True,
                   choices=["kraft", "zk", "etcd", "tendermint", "paxos", "epaxos"])
    p.add_argument("--endpoints", required=True)
    p.add_argument("--duration-sec", type=int, default=20)
    p.add_argument("--out", required=True)
    args = p.parse_args()

    if args.system in ("kraft", "zk"):
        probe_kafka(args.endpoints, args.duration_sec, args.out)
    elif args.system == "etcd":
        probe_etcd(args.endpoints, args.duration_sec, args.out)
    elif args.system == "tendermint":
        probe_tendermint(args.endpoints, args.duration_sec, args.out)
    elif args.system in ("paxos", "epaxos"):
        probe_paxi(args.endpoints, args.duration_sec, args.out)
    else:
        print(f"unknown system: {args.system}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
