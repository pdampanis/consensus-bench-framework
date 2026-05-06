# etcd

## What it is

etcd is a distributed key-value store, written in Go, that uses Raft for consensus. It is the canonical production Raft implementation: it backs Kubernetes, CoreDNS, Rook, and a long list of cloud-native infrastructure. If you want to know what a well-engineered Raft looks like in practice, read etcd's source.

The client model is simple: put, get, delete, range scan, and watch. Keys and values are byte strings; etcd does not interpret them. A typical etcd cluster is 3 or 5 nodes (odd numbers because Raft needs a majority). Every write goes through Raft consensus; every read can optionally be linearizable (goes through Raft) or serializable (served from the local node's snapshot, potentially stale).

## Why etcd is in the thesis

etcd serves as the **reference Raft implementation**. The thesis compares KRaft (Kafka's Raft) against etcd (generic Raft) to show how the Kafka-specific design choices in KRaft — event-driven metadata topic, combined mode, sub-second controller failover — compare against the abstract Raft baseline. This is an apples-to-oranges comparison at the workload level (Kafka is a log-streaming system, etcd is a key-value store) but an apples-to-apples comparison at the consensus-algorithm level. Both run Raft.

etcd's numbers are well-documented in the literature: benchmarks routinely report 10k-50k writes per second and sub-millisecond p99 latency on proper hardware. The thesis will not beat those numbers — the laptop is thermally constrained — but the *shape* of the curves should match.

## What we test

Same four scenarios and four metrics as described in `raft.md` and `kraft.md`. etcd-specific things:

- **The `benchmark` tool** ships inside the etcd Docker image at `/usr/local/bin/benchmark`. We run it from a sidecar container on the same Docker network as the cluster. It produces throughput and latency percentiles in a format that `parse_results.sh` normalizes into the shared `throughput.csv` / `latency.csv`.
- **Leader detection** uses `etcdctl endpoint status -w simple`; the fifth comma-separated field of each endpoint's line is `true` for exactly one endpoint at a time. `leader_kill.sh` walks the three etcd containers and kills the one that reports as leader.
- **Throughput per-second resolution** is unavailable from the benchmark tool — it only reports a total rate. Our `parse_results.sh` distributes the total evenly across the measurement window, which is a lossy approximation. If you need true per-second throughput for a specific etcd run, query Prometheus directly: `rate(etcd_server_proposals_committed_total[10s])`.

## How to read the results

The etcd curves should sit below the Kafka curves on the throughput axis but above them on the latency axis. Reasoning:

- etcd is a key-value store designed for coordination metadata, not for high-throughput log streaming. Its Raft layer commits every single write, one at a time, with no batching at the consensus level.
- Kafka batches aggressively: a single Raft commit carries hundreds of user records. That is why Kafka's per-record throughput is much higher even though both use Raft.
- Conversely, etcd's per-operation p99 latency is typically tighter than Kafka's at baseline. Small, coordination-shaped workloads suit it.

Under leader_kill, etcd's failover should be the fastest of the three systems we test — typically 100-500 ms. This is etcd's production reputation and the thesis should confirm it. If you see failover times above 1 second for etcd, there is probably a configuration issue (session timeout, heartbeat interval).

## How to write about it

The etcd chapter is short in the body of the thesis. It exists to establish the reference point. The paragraph structure:

> "etcd (CoreOS, now CNCF) is the most widely deployed production Raft implementation. We include it as a reference point against which the specialized Raft variant in Kafka (KRaft) is compared."

> "At baseline, etcd sustained X ops/s with p99 latency of Y ms — consistent with published figures for small-cluster etcd deployments (etcd maintainers report 10-50 k ops/s on cloud VMs, scaling with disk speed). Our laptop-bound numbers are one order of magnitude lower, reflecting the hardware budget documented in the methodology chapter."

> "Under leader-kill, etcd recovered in median X ms. This matches the documented Raft leader-election timeline driven by the 100 ms heartbeat interval plus the randomized election timeout."

## Papers to cite

- **`atc14paperongaro.pdf`** — Ongaro and Ousterhout, the Raft paper. etcd is a direct implementation of what that paper describes.
- The etcd project itself does not have a canonical academic paper; cite the public documentation at `https://etcd.io/docs/` and the CNCF graduation milestone.

## Common pitfalls in running these experiments

**The benchmark tool is not a client simulator.** It is a saturation-throughput measurement tool. It does not simulate user workloads with think-time or zipfian key distributions. This is fine for the thesis — we want raw consensus throughput — but note it explicitly in the methodology chapter to head off the "why didn't you use YCSB" question.

**Snapshot compaction.** etcd automatically snapshots its Raft log when it grows past a threshold (default 10000 entries). On a short benchmark run this usually doesn't trigger, but if you see sudden throughput dips mid-run, check whether a snapshot was taken. The `etcd_disk_backend_snapshot_duration_seconds` metric tells you.

**Disk sync semantics.** etcd fsyncs on every commit. On the laptop's NVMe this is fast (sub-millisecond) but still the dominant cost. If you see suspiciously high throughput (>100k ops/s), verify that fsync is actually happening — some older kernel/filesystem combos silently defer it.

**Single-endpoint probe.** The leader-election probe connects to a single etcd endpoint for simplicity; if that endpoint is the one we kill, the probe client times out trying to reconnect rather than failing over transparently. The `etcd3` Python client handles endpoint failover only when given multiple endpoints at construction time. Our probe uses only one endpoint — the first in the list — which means if we kill etcd1 in a trial, the probe has to time-out and retry through its internal reconnect logic. This produces slightly pessimistic failover numbers for etcd (the probe's reconnect time is part of the measurement). In the thesis, flag this as a measurement artifact in the etcd subsection; the real server-side failover is faster than what we report.
