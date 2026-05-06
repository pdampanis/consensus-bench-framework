# Scalability

## What we measure

Throughput and latency at **cluster sizes 3, 5, and 7 nodes** for KRaft and etcd (the two Raft implementations). Kafka+ZK is not included in the scalability sub-matrix because ZK-mode Kafka has additional configuration complexity that would not advance the thesis's scalability argument. Tendermint is fixed at 4 validators (the thesis does not attempt a BFT scalability story).

The measurements are baseline-only: we do not cross-vary cluster size with fault scenarios. The reason is that the cost grows quadratically (more sizes × more scenarios) and the shape of the scalability curve is the interesting finding, not the fault response at each size.

## Why 3, 5, 7 specifically

These are the three odd cluster sizes below the practical deployment ceiling. Even sizes are uncommon in Raft deployments because they do not improve fault tolerance (4 nodes tolerate 1 failure, same as 3). Odd sizes ending at 7 give three data points on the curve, which is the minimum needed to distinguish a linear trend from a non-linear one. Raft theory predicts:

- **Throughput should decrease monotonically** with cluster size, because every commit requires acknowledgment from a majority, and more replicas means more per-commit coordination.
- **p99 latency should increase monotonically** for the same reason.
- **The decrease should be sub-linear**: going from 3 to 5 nodes adds 2 more followers to acknowledge, but the leader can send to all of them in parallel, so the slowdown is small. Going from 5 to 7 adds another 2, still in parallel.

The three-point curve tests this theory directly. If we see linear decay, the theory is wrong. If we see exponential decay, something is wrong with our measurement. If we see sub-linear decay matching the theory, the thesis has its scalability chapter.

## How to read fig06

`analysis/out/plots/fig06_scalability.png` is the hero figure for this chapter. Two curves: one per system (KRaft and etcd). X-axis is cluster size (3, 5, 7). Y-axis is mean baseline throughput.

What to look for:

1. **Both curves should descend as cluster size increases.** If one rises, suspect a measurement artifact — more nodes should not help on a saturating workload.
2. **KRaft should descend more gently than etcd.** KRaft batches aggressively at the log level; the consensus cost per *record* is amortized across thousands of records per Raft commit. etcd commits every operation individually, so every extra replica adds directly to per-operation latency. This is the headline theoretical point of the chapter.
3. **Absolute values at size 3 should match the session 1 and session 2 baseline measurements.** This is a useful sanity check — same system, same config, same scenario, same result.

## How to write about it

The scalability chapter structure:

> "Scalability in consensus systems is bounded by the majority-quorum commit rule: every write requires acknowledgment from a majority of replicas, so larger clusters introduce more per-commit coordination. The Raft paper establishes this bound theoretically but does not measure it directly at different cluster sizes. We do so for two Raft implementations — KRaft and etcd — at cluster sizes 3, 5, and 7."

> "Figure N shows mean baseline throughput as a function of cluster size. KRaft's curve descends from X to Y (an Z% reduction across the 3-to-7 range), while etcd's descends from A to B (a C% reduction). The smaller relative decline for KRaft reflects the amortization of consensus cost across Kafka's record batches: a single Raft commit in KRaft carries hundreds of user records, whereas an etcd put is a single-record Raft commit."

> "Both curves are sub-linear, consistent with the Raft model that a leader broadcasts to followers in parallel. Neither system shows the elbow characteristic of network saturation, suggesting the bottleneck is CPU (consensus bookkeeping per replica) rather than network."

The numerical claims in this paragraph come from `summary.csv` rows where `scenario="baseline"` and `metric="throughput"`, grouped by `cluster_size`. The standard deviation and 95% CI come from the same file.

## Papers to cite

The scalability chapter needs:

- The **Raft paper** (`atc14paperongaro.pdf`) for the majority-quorum theoretical bound.
- The **Kafka paper** (`2023397.pdf`) for Kafka's log-batching architecture.
- The **etcd documentation** (online, not in bundle) for the canonical etcd deployment guidance that recommends 3 or 5 nodes and warns against larger sizes — which our data empirically confirms.
- Optionally, **Pedone 2021** (`Pedone_2021_IEEE_SRDS.pdf`) for general log-replication scalability analysis.

## Resource footprint

| Cluster | Processes | Heap | Total memory | Duration per run |
|---|---|---|---|---|
| KRaft 3 | 3 × broker-controller | 1 GB each | ~4.5 GB | 8 min |
| KRaft 5 | 5 × broker-controller | 1 GB each | ~7.5 GB | 8 min |
| KRaft 7 | 7 × broker-controller | 1 GB each | ~10.5 GB | 8 min |
| etcd 3 | 3 × etcd | 500 MB each | ~1.5 GB | 8 min |
| etcd 5 | 5 × etcd | 500 MB each | ~2.5 GB | 8 min |
| etcd 7 | 7 × etcd | 500 MB each | ~3.5 GB | 8 min |

All configurations fit comfortably in the target 32 GB machine. The 7-node KRaft is the single largest stack; at ~14 GB committed it still leaves half the RAM for observability, Docker overhead, and the OS.

## What can go wrong in our measurement

**Partition count**. At higher replication factors, the six-partition `bench` topic forces each partition to replicate to more brokers. This spreads the load but does not hide the consensus cost because every partition has its own leader that must wait for its own followers. The partition count is held constant across sizes to keep the comparison apples-to-apples.

**Controller election time at larger sizes**. Larger KRaft controller quorums take longer to elect on startup. This is why `run_batch.sh` gives larger clusters 240 seconds (4 minutes) to become healthy instead of the 180 seconds used for 3-node clusters. If you see "did not become healthy within 240s" in the batch log for a 7-node bring-up, the fix is to extend the deadline, not to blame the protocol.

**Single host limitation**. All nodes in every cluster size run on the same physical host. Real production deployments spread them across availability zones. Our numbers capture the *consensus algorithm overhead* at each size, not the *network latency* cost of geographic distribution. Document this in the methodology chapter; cite it as a limitation in the future-work section.
