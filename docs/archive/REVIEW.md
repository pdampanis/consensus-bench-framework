# Benchmark Bundle Review — Deep Scrutiny

## CRITICAL BUG: parse_results.sh Breaks 4 of 7 Systems

`parse_results.sh` only has parsing blocks for `kraft|zk` (Kafka format) and `etcd`. There is **no parsing block** for `tendermint`, `paxos`, `epaxos`, or `hotstuff`. These systems' benchmark probes (`cmt_bench.py`, `paxi_bench.py`, `hs-run.sh`) all write output in kafka-perf-test format — which is correct — but `parse_results.sh` never processes them because the conditional on line 40 only matches `kraft` or `zk`.

**Impact**: `throughput.csv` and `latency.csv` are never created for 4 of 7 systems. In `run_one.sh`, the subsequent `wc -l < "$OUT/throughput.csv"` returns 0, so `STATUS="failed"` is written to every manifest. `analyse.py` then skips all runs with `status != complete`. The entire analysis pipeline produces results for only Kafka and etcd.

**Fix**: Change the Kafka-format parser condition to be the default, keeping the etcd parser as the exception:

```bash
# Replace line 40:
#   if [ "$SYSTEM" = "kraft" ] || [ "$SYSTEM" = "zk" ]; then
# With:
if [ "$SYSTEM" != "etcd" ]; then
```

This works because every non-etcd system now produces kafka-perf-test format output. The etcd block (line 112) remains separate because etcd's benchmark tool has a different output format.

---

## What the Benchmark Gets Right

**Standardized output contract.** All seven systems produce the same two CSVs (`throughput.csv`, `latency.csv`) with identical schemas. This is the right approach — it means `analyse.py` is genuinely system-agnostic. The decision to have the custom probes (`cmt_bench.py`, `paxi_bench.py`, `hs-run.sh`) emit kafka-perf-test format upstream rather than writing separate parsers downstream is clean engineering.

**Warm-up exclusion.** 180 seconds of warm-up before measurement, and `analyse.py` line 62 explicitly filters `t_off >= 180` from throughput data. This avoids cold-start JVM compilation (Kafka), TCP slow-start, and initial leader-election transients contaminating results.

**Statistical approach.** Mann-Whitney U (not paired Wilcoxon) is the correct test for independent samples. Bootstrap CIs with a fixed seed give reproducible intervals. Effect size as relative mean difference is more informative than p-values at n=5.

**Leader detection per system.** `leader_kill.sh` correctly identifies the actual leader (not a hardcoded node) for each system: KRaft via `kafka-metadata-quorum.sh`, ZK via the `/controller` znode, etcd via `endpoint status`, CometBFT via proposer address matching, Paxos via `/state`. EPaxos correctly kills a non-probe replica since there is no leader.

**Correctness checks.** Baseline runs verify data durability: Kafka via end offsets, etcd via key prefix scan, CometBFT via block height. The 95% threshold for PASS accommodates in-flight uncommitted batches at producer exit. Sound.

**Idempotent campaign.** Completed cells (status=complete in manifest) are skipped on re-run. Interrupted campaigns resume without data loss. Essential for 39-hour campaigns.

**Fault injection model.** Six scenarios cover the thesis evaluation criteria well: baseline (steady-state), leader_kill (failover), double_kill (quorum loss — liveness boundary), packet_loss and network_partition (degraded network), slow_node (asymmetric performance). The HotStuff process-level injection via `hs-inject.sh` is methodologically equivalent to Pumba container-level injection for the other systems.

---

## Issues Requiring Fixes or Documentation

### 1. Transaction Size Mismatch: HotStuff 512B vs Everything Else 1024B

`run_one.sh` line 207: `HS_TX_SIZE=512`. Every other system uses 1024-byte values (Kafka `--record-size 1024`, etcd `--val-size=1024`, CometBFT `--val-size 1024`, Paxi `--val-size 1024`).

Half the payload means lower serialization overhead, lower network cost per operation, and potentially higher TPS and lower latency. This makes HotStuff's numbers not directly comparable to other systems on an ops/s basis.

**Fix options** (pick one):
- Change `HS_TX_SIZE=512` to `HS_TX_SIZE=1024` in `run_one.sh` line 207. This makes the comparison fair.
- Keep 512 and document it explicitly. Normalize throughput to bytes/s in addition to ops/s so the comparison has a common denominator.

Recommendation: change to 1024 for consistency. The asonnino benchmark handles any tx_size.

### 2. Load Model Mismatch: HotStuff Rate-Limited vs Others Open-Loop

Kafka, etcd, CometBFT, and Paxi all run open-loop: 6 concurrent clients sending as fast as possible (`--throughput -1` for Kafka, tight-loop workers for the probes). HotStuff runs rate-limited at `HS_RATE=1000` tx/s. If HotStuff can handle more than 1000 tx/s, the benchmark caps it artificially. If the published reference is ~966 tx/s at rate=1000, the system is near saturation, so the impact may be small — but the methodological difference should be documented.

**For the thesis**: Note that HotStuff uses a rate-limited client at 1000 tx/s (the asonnino default), while other systems use open-loop saturation. The measured throughput is therefore a lower bound on HotStuff's maximum capacity.

### 3. etcd Throughput: Synthetic Flat Line

`parse_results.sh` lines 142–153 generate etcd's `throughput.csv` by repeating the overall `Requests/sec` value for every second of the run. This produces zero intra-run variance — every 1-second bucket has the same value. Consequence: `throughput_std` for etcd is always 0, and the bootstrap CI collapses to a point. The bar chart error bars for etcd in `fig01` will be misleadingly narrow.

The code acknowledges this on line 110: "known limitation of the etcd benchmark tool; per-second resolution is only available via Prometheus metrics." `export_metrics.sh` exists but is not integrated into the analysis pipeline.

**For the thesis**: Document that etcd's per-run throughput variance is approximated from Prometheus metrics rather than the native benchmark tool. Alternatively, integrate `export_metrics.sh` output into the throughput.csv for etcd runs, which would give real per-second samples.

### 4. HotStuff Latency Percentiles Are Synthetic

`hs-run.sh` lines 129–136 fill p50/p95/p99/p99.9 all with the mean value because asonnino's SUMMARY only reports mean latency. This is honestly documented in `hotstuff.md`, but has two downstream consequences:

- `fig02_latency_cdf.png` will show HotStuff as a vertical line (all percentiles identical).
- `pairwise_tests.csv` comparisons on `lat_p99_us` will compare HotStuff's mean against other systems' actual p99. This is apples-to-oranges.

**For the thesis**: Exclude HotStuff from the p99 latency CDF figure or plot it separately with a clear annotation. In `pairwise_tests.csv`, skip HotStuff rows for latency metrics. Report HotStuff latency as "mean end-to-end latency" only.

### 5. HotStuff Throughput: Synthetic Flat Line (Same as etcd)

`hs-run.sh` lines 117–126 back-fill uniform 5-second progress lines from the summary TPS. Like etcd, the throughput.csv has zero intra-run variance. Same caveat applies.

### 6. EPaxos Single-Endpoint Driver Understates Its Advantage

`paxi_bench.py` connects all 6 workers to `paxi1:8080`. For EPaxos, this means every command goes through paxi1's fast path. EPaxos's key advantage is that **any** replica can commit locally on non-conflicting commands without forwarding — the leaderless design eliminates the single-leader bottleneck. Routing all traffic through one replica negates this advantage.

**Fix**: Distribute workers across replicas (2 workers each to paxi1, paxi2, paxi3). This would show EPaxos's true multi-leader throughput advantage over Paxos.

**For the thesis**: If the single-endpoint setup is kept, document that EPaxos throughput represents a conservative measurement that does not exercise the protocol's multi-leader capability. The theoretical advantage (documented in `epaxos.md` and the Moraru et al. paper) is understated by this setup.

### 7. CPU Allocation Is Not Uniform Across Systems

| System | CPUs per container | Containers | Total CPUs |
|---|---|---|---|
| KRaft | 1.0 | 3 | 3.0 |
| Kafka+ZK | 1.0 (brokers) + ZK | 3+1 | 3.0+ |
| etcd | 0.5 | 3 | 1.5 |
| CometBFT | 0.5 | 4 | 2.0 |
| Paxi | 0.5 | 3 | 1.5 |
| HotStuff | 4.0 | 1 | 4.0 |

Kafka gets 2× the CPU of etcd and CometBFT. HotStuff gets the most. This reflects realistic per-system resource profiles (JVM systems need more) but is a confound for absolute throughput comparisons.

**For the thesis**: Document the resource allocation per system. Frame the comparison as "systems running with typical resource profiles on a shared host" rather than "systems under identical resources." Throughput per CPU core could be a useful normalized metric.

### 8. network_partition.sh Missing HotStuff Case

`network_partition.sh` line 22 has no `hotstuff)` case — it would hit `*) echo "unknown system"`. HotStuff partitions are handled by `hs-inject.sh` inside the container (called from `run_one.sh` line 234), so this script is never invoked for HotStuff. No bug in practice, but the missing case would cause a confusing error if someone ran the script directly.

---

## Metrics: What We Measure and Whether It Is Correct

### Throughput (ops/s)

| System | Source | What it actually measures |
|---|---|---|
| KRaft / ZK | `kafka-producer-perf-test --throughput -1 --acks all` | Records/sec committed to all ISR replicas. Correct: `acks=all` means the consensus quorum acknowledged. |
| etcd | `benchmark put --total=10M --val-size=1024` | Requests/sec to the Raft leader with majority acknowledgment. Correct. |
| CometBFT | `cmt_bench.py broadcast_tx_commit` | Transactions/sec committed to a finalized block. Correct: `broadcast_tx_commit` waits for consensus. |
| Paxi | `paxi_bench.py PUT` | Writes/sec that return HTTP 200 after Paxos/EPaxos consensus round. Correct. |
| HotStuff | asonnino's `fab local` SUMMARY | End-to-end TPS from the asonnino client. Correct: measures committed transactions. |

All systems measure **committed** operations, not just acknowledged. This is the right approach for a consensus-protocol comparison.

### Latency

| System | Source | Granularity |
|---|---|---|
| KRaft / ZK | `kafka-producer-perf-test` summary | avg, p50, p95, p99, p99.9, max — full percentile set |
| etcd | `benchmark put` summary | avg, p50, p95, p99, p99.9, max — full percentile set |
| CometBFT | `cmt_bench.py` per-op timer | avg, p50, p95, p99, p99.9, max — full percentile set |
| Paxi | `paxi_bench.py` per-op timer | avg, p50, p95, p99, p99.9, max — full percentile set |
| HotStuff | asonnino SUMMARY | **mean only** — all percentile fields filled with mean |

Five of seven systems produce real percentile distributions. HotStuff does not. This is a known and documented limitation.

**What latency includes**: For Kafka, it is producer-to-ISR-ack time. For etcd, client-to-majority-ack time. For CometBFT, client-to-block-commit time (includes block interval, typically 1–3 seconds — expect much higher latency than Kafka/etcd). For Paxi, HTTP round-trip through consensus. For HotStuff, client-to-commit including mempool batching.

**Important caveat for CometBFT latency**: `broadcast_tx_commit` includes the time waiting for the transaction to be included in the next block. CometBFT's block time (default 1 second) dominates the latency measurement. This means CometBFT latency is fundamentally different from etcd/Kafka latency, which do not batch into fixed-interval blocks. Document this: CometBFT's higher latency partly reflects its block-interval design, not just its BFT message complexity.

### Leader-Election Failover Time

Measured via `le_probe.py` at 50 Hz: time from kill signal to first successful write after the kill. This correctly measures **observed client-side recovery time**, which includes leader detection, election, and client reconnection — the full failover experience.

For EPaxos, the measurement is "time to recover from arbitrary replica loss" rather than "leader election time" since EPaxos has no leader. This is documented in `leader_kill.sh` and should be noted in the thesis.

For HotStuff, the measurement uses an in-container probe that spawns fresh 4-validator testbeds per trial. Different methodology from the external-probe approach used for other systems, but measures the same concept: time from fault to first committed transaction.

---

## Recommendations for Thesis Sections

### Methodology Chapter — Required Disclosures

1. Single-host Docker testbed: all systems share one i7 CPU. Resource allocations differ by system. Results reflect relative performance under typical profiles, not absolute capacity.
2. HotStuff latency is mean-only; percentile comparisons exclude HotStuff.
3. HotStuff and etcd throughput per-run variance is synthetic (constant within a run). Cross-run variance (5 runs) is real.
4. EPaxos throughput is conservative (single-endpoint driver).
5. CometBFT latency includes block-interval wait time (not just consensus message delay).
6. Transaction size: 1024 bytes for all systems (or document 512 for HotStuff if unchanged).
7. HotStuff fault injection is process-level (hs-inject.sh) rather than container-level (Pumba). Equivalent in effect.

### Results Chapter — What Each Figure Shows

| Figure | Claim | Statistical basis |
|---|---|---|
| fig01 throughput bars | Relative throughput at baseline | Mean ± bootstrap 95% CI across 5 runs |
| fig02 latency CDF | Tail latency characteristics | p99 across runs (exclude HotStuff) |
| fig03 LE CDF | Failover speed comparison | 200 trials per system, percentile plot |
| fig04 under fault | Degradation under failure | Mean throughput per (system, scenario) |
| fig05 kraft vs zk | ZAB→Raft evolution story | Paired scenarios, Mann-Whitney U |
| fig06 scalability | Raft throughput vs cluster size | 3/5/7 nodes for kraft + etcd |

---

## Summary of Required Actions

| Priority | Issue | Action |
|---|---|---|
| **CRITICAL** | `parse_results.sh` missing 4 systems | Change line 40 to `if [ "$SYSTEM" != "etcd" ]; then` |
| **HIGH** | HotStuff tx_size=512 vs 1024 | Change `HS_TX_SIZE=512` to `1024` in run_one.sh line 207 |
| **MEDIUM** | EPaxos single-endpoint | Distribute workers across replicas, or document the limitation |
| **MEDIUM** | etcd/HotStuff flat throughput | Document in methodology, or integrate Prometheus data |
| **LOW** | HotStuff latency percentiles | Exclude from latency CDF, or annotate clearly |
| **LOW** | CPU allocation differences | Document per-system resource allocation in methodology |
| **LOW** | network_partition.sh missing hotstuff case | Add no-op case or note that hs-inject.sh handles it |
