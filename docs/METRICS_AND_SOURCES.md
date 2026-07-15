# How Metrics Are Measured — Per System Reference

This document explains exactly what each number means for each system, so you can defend your methodology in the thesis and during the oral examination.

> **UPDATED 2026-07-15.** Sections 1–3 originally described the **retired v6
> probe stack** (per-system tools, 6 blocking Python threads, urllib) — the
> measurement mechanics the harness was built to replace. The per-system
> *consensus semantics* (what `acks=all` or `broadcast_tx_commit` commits)
> remain correct and are the study material; the *tool mechanics* are kept
> only where a tool survives as a **G1/G3 cross-validation oracle**, and are
> labeled so. The current measurement path is §0 below. Authority order as
> always: live code > plan + methodology > this file.

---

## 0. How measurement works now — the harness (primary instrument)

Every system is driven by **one load generator** (`WorkloadEngine`): an
open-loop schedule (or saturation mode) with a bounded in-flight window,
identical for all systems; only the transport differs per `ConsensusDriver`.
Latency is recorded against the *intended* send time (coordinated-omission
correction) into HdrHistogram (3 significant digits; true mean; full
post-warmup histogram persisted as `latency.hlog` for pooled analysis).
Throughput is real per-second committed buckets for every system — no
synthetic series anywhere. Failover time comes from `EventLog`: every
completion is timestamped on one clock, the fault mark is stamped by the
injector, and failover = first successful commit at-or-after the mark
(sub-second resolution, ≥30 trials, reported as ECDFs). Workload: K = 1000
reused keys (Paxi Table 3), 1024 B values, conflict-ratio knob for the Paxi
pair (D7). Each run writes a v2 manifest (params, environment, image digest,
harness version, config hash, fault/failover ms) and is gated by the six
validity checks (methodology §4).

The retired per-system tools below matter for two reasons: they document
what the old numbers meant, and three of them return at **Gate G1/G3** as
independent oracles — the harness must agree with `kafka-producer-perf-test`,
etcd's `benchmark`, and Paxi's own benchmarker within 15% on the same
cluster, or explain why.

---

## 1. Throughput (ops/s)

**Definition**: The number of write operations per second that were committed by the consensus mechanism — meaning a majority (CFT) or supermajority (BFT) of replicas acknowledged the write.

### Kafka KRaft / Kafka+ZK

**Now**: `KafkaDriver` (P2.2) — a real `kafka-clients` producer with
`acks=all`, commit timed in the send callback, driven by the shared engine.

**Retired probe / G1+G3 oracle**: `kafka-producer-perf-test.sh` (ships inside
`apache/kafka:3.9.0`). The driver must agree with it within 15% (the flaw-B
regression gate).

**Configuration** (oracle invocation; the driver uses the same broker-side
settings): `--throughput -1` (open-loop, no rate limit), `--record-size 1024` (1 KB payloads), `--producer-props acks=all` (wait for all ISR replicas to acknowledge). Replication factor 3, min.insync.replicas 2, 6 partitions.

**What `acks=all` means for consensus**: The producer does not receive a success response until all replicas in the In-Sync Replica (ISR) set have written the record to their local log. With `min.insync.replicas=2` and RF=3, at least 2 of 3 brokers must acknowledge. In KRaft mode, the Raft quorum for metadata is also majority-based. This means every "record sent" in the perf output represents a **consensus-committed** write.

**Granularity**: the harness buckets committed ops per second directly — the
old path (perf-test 5-second progress lines scraped by `parse_results.sh`)
is retired.

**What to say in the thesis**: "Throughput is measured as records committed per second with `acks=all`, ensuring each counted operation has been replicated to a quorum of brokers (at least 2 of 3 in the ISR set). This configuration exercises the full Raft consensus path for KRaft and the full ZAB replication path for Kafka+ZooKeeper."

### etcd

**Now**: `EtcdDriver` (P2.1, done) — jetcd's native async gRPC put with a 5 s
per-op deadline; a completed put passed the full Raft commit path (majority
replication + WAL fsync + apply). `EtcdHttpDriver` (v3 JSON gateway) is the
fallback and the same-cluster cross-check.

**Retired probe / G3 oracle**: `benchmark put` from
`quay.io/coreos/etcd:v3.5.15` (`--conns=6 --clients=6 --val-size=1024`).
Each completed PUT is a Raft-committed write — that semantic claim still
holds and is why it works as an oracle.

**Granularity**: the old tool reported only an aggregate `Requests/sec`, so
v6's throughput.csv was *synthetically generated* by repeating that value —
a documented limitation then, **fixed now**: the harness produces a real
per-second committed series for etcd like every other system.

### CometBFT (Tendermint)

**Now**: `CometBftDriver` (P2.3) — pooled async `broadcast_tx_commit` with an
in-flight window ≥ 200. Its acceptance test (sustained > 300 tx/s) is the
regression gate for **flaw A**: the retired probe below capped measurable
throughput at ~6 tx/s.

**Retired probe**: `cmt_bench.py` (6 *blocking* threads on
`broadcast_tx_commit`; with a ~1 s block interval the ceiling is
clients ÷ block time ≈ 6 tx/s — it measured the client's thread count, not
the protocol). Not an oracle; it is the bug the harness exists to fix.

**What `broadcast_tx_commit` means for consensus**: This RPC does not return until the transaction has been included in a **committed block** — meaning ≥2/3 of validators have pre-committed and committed the block in the Tendermint consensus round (Propose → Prevote → Precommit → Commit). A returned 200 means the full BFT consensus cycle completed for that transaction.

**Block-interval effect on throughput**: CometBFT batches transactions into blocks at ~1-second intervals. Throughput is therefore bounded by `(max_tx_per_block / block_interval)`. This is fundamentally different from Kafka/etcd, which commit individual operations as they arrive. The measured throughput reflects the protocol's batching design, not just the consensus message complexity.

### Paxi (Paxos / EPaxos)

**Now**: `PaxiDriver` — pooled async HTTP (java.net.http), integer key in the
URL path, round-robin over **all** replica endpoints for EPaxos (leaderless
design actually exercised), one endpoint for Paxos (forwarding is internal).
Paxi's own benchmarker returns at G3 as the cross-validation oracle.

**Retired probe**: `paxi_bench.py` (6 blocking threads; `urllib` opened a
new TCP connection per PUT — **flaw B**: a handshake inside every latency
sample, all traffic through paxi1 only).

**Paxos path**: The PUT goes to paxi1. If paxi1 is the leader, it runs Phase 2 (Accept) to a majority and responds. If not, Paxi forwards to the leader internally. Each 200 response means the value was committed by majority quorum.

**EPaxos path**: The PUT goes to paxi1, which acts as the command leader for that key. On the fast path (no conflict with concurrent commands), paxi1 collects a "fast quorum" of ⌊3N/4⌋ responses and commits in one round-trip. On the slow path (conflict detected), a second round-trip resolves the dependency. Either way, a 200 response means the value is committed.

**Single-endpoint limitation — fixed**: the retired probe sent all traffic
through paxi1, which for EPaxos never exercised the leaderless
multi-entry-point design (throughput conservatively understated). The
harness round-robins EPaxos writes across every replica; Paxos keeps one
endpoint by design. The D7 conflict knob (0/2/10% of ops on a designated
shared key) is what lets EPaxos's fast path actually differ from Paxos.

### HotStuff (asonnino)

**Still the boundary**: HotStuff is the one system the engine does not drive
directly — asonnino's `fab` benchmark client remains the load source and the
harness parses its SUMMARY block (P2.5); its client is already open-loop
rate-limited, which the engine now matches for everyone else.

**Configuration**: `HS_RATE` (target input rate), and **the tx size MUST be
configured to 1024 B for the campaign** — the old `HS_TX_SIZE=512` default
mismatched the 1024 B contract every other system uses (methodology §1).
This is a P2.5 configuration requirement, not an accepted asymmetry.

**What it measures**: The asonnino client submits transactions at the target rate to the 4-validator cluster. The SUMMARY block reports "End-to-end TPS" — the number of transactions that achieved consensus commitment, including mempool batching, proposal, and the 2-chain QC pipeline. This is the client-observed committed throughput.

**Rate-limiting effect**: HotStuff's client caps the *input* rate, so a
measured throughput at a given `HS_RATE` is a lower bound if the system could
handle more — the saturation search must raise `HS_RATE` until TPS plateaus,
mirroring what the engine's saturation mode does for the other systems.
(Historical: ~966 tx/s at rate=1000 on a local cluster suggested
near-saturation there.)

---

## 2. Latency (microseconds)

**Definition**: The time from when the client issues a write to when the client receives confirmation that the write is committed by consensus.

### What each system's latency includes

| System | Includes | Does NOT include |
|---|---|---|
| Kafka | Producer → Leader receive → ISR replication → ACK back to producer | Consumer-side delivery |
| etcd | Client → Raft leader → Majority append → ACK back to client | Read latency |
| CometBFT | Client → Proposer → Prevote → Precommit → Commit → Block finalize → ACK | **Includes block-interval wait** (~1s). This dominates. |
| Paxi | Client HTTP → Leader/command-leader → Quorum → HTTP response | Internal forwarding is included |
| HotStuff | Client → Mempool → Propose → 2-chain QC → Commit → ACK | Only mean is available |

**Critical note for the thesis**: CometBFT latency is structurally higher than Kafka/etcd latency because it includes the time waiting for the transaction to be batched into a block. A CometBFT p99 of 2000ms does not mean the consensus messages take 2 seconds — it means the transaction waited up to one block interval plus three consensus phases. This distinction matters when comparing CFT and BFT protocols.

### Percentile computation

For every harness-driven system (all except HotStuff): one code path —
HdrHistogram at 3 significant digits (every recorded value within 0.1%),
percentiles from the post-warmup histogram only, plus the true mean.
Per-run histograms are persisted (`latency.hlog`) and **merged** for pooled
distributions; percentiles are never averaged across runs (methodology §3).
Latency is charged against the *intended* send time, so a stall inflates
the tail instead of silently not being sampled (coordinated omission).

The retired probes computed percentiles per-tool (perf-test summary lines,
etcd's internal histogram, Python sort-and-index) — one reason cross-system
comparison was apples-to-oranges.

For HotStuff: only mean is available from the SUMMARY output. All percentile
fields contain the mean. Do not compare HotStuff percentile columns with
other systems — every figure it appears in says so.

---

## 3. Leader-Election Failover Time (seconds)

**Definition**: Time from leader kill to the first successful client write after recovery.

**Measurement method (harness, P1.4)**: failover events ride the main
workload — no separate probe lane, so the measurement doesn't perturb the
load it measures. Every completion is timestamped into `EventLog`
(lock-free, preallocated); the injector stamps the fault mark on the same
`System.nanoTime` clock; failover = first successful commit at-or-after the
mark. Resolution is per-completion (sub-millisecond clock, bounded by the
op rate), repeated over ≥30 trials and reported as a full ECDF. A run with
no post-fault commit reports **no number**, never a fabricated one.
(Retired method: a separate `le_probe.py` container writing at 50 Hz —
a second traffic class with 20 ms resolution.)

**What this measures**: The complete client-visible recovery, including leader detection timeout (typically 1–10s depending on election timeout configuration), candidate election, new leader establishment, and client reconnection. This is the metric that matters for application-level SLA compliance.

### Per-system leader-election mechanisms being measured

**KRaft**: Raft election timeout (randomized 5–10s by default in Kafka), RequestVote round, new leader catches up from latest committed offset.

**Kafka+ZK**: ZooKeeper session timeout (default 18s), controller failover via ZK ephemeral node watch, new controller elected from ISR.

**etcd**: Raft election timeout (default 1000ms × 10 = 10s), randomized within [electionTimeout, 2×electionTimeout]. Typically faster than Kafka because etcd's election timeout is much shorter.

**CometBFT**: Tendermint round timeout (default `timeout_propose = 3s`), followed by Prevote/Precommit rounds for the new proposer. The deterministic rotation means the next proposer is already known.

**Paxos**: Paxi's Phase 1 re-election. A new proposer must complete a Prepare round with a higher ballot number to establish itself. Timeout-driven detection of the dead leader.

**EPaxos**: No leader election. The measurement captures "time to route around a dead replica." Since any replica can commit, failover means the client's HTTP connection to the dead replica fails, and traffic shifts to a live one. Expected failover is very fast (HTTP timeout + retry).

**HotStuff**: View-change protocol. The pacemaker detects the leader timeout and triggers a new-view with the next rotating leader. The new leader collects new-view messages from n−f replicas and resumes. Expected: sub-second.

---

## 4. Fault Tolerance Scenarios

### baseline
No fault injected. Measures steady-state performance. This is the primary comparison point.

### leader_kill
Kill the consensus leader (or proposer/primary). Measures failover behavior: does throughput recover, and how fast? Expected: throughput drops to zero briefly, then recovers after election. Exception: EPaxos (no leader; kills a random replica instead).

### double_kill
Kill 2 nodes simultaneously. For 3-node CFT clusters (KRaft, ZK, etcd, Paxos, EPaxos): 2 of 3 dead means no quorum (need ⌈(3+1)/2⌉ = 2, but only 1 remains). Expected: zero throughput, cluster is unavailable. For 4-node BFT clusters (CometBFT, HotStuff): 2 of 4 dead means only 2 remain, below the BFT liveness threshold of 3f+1−f = 3. Expected: zero throughput.

This scenario validates that the system correctly becomes unavailable when quorum is lost, rather than proceeding unsafely.

### packet_loss
Random packet loss (5% default; the percentage is a parameter per F13) on one
node's network interface via netem — executor (hand-rolled `tc` vs Pumba)
decided at P3.3, bound by the golden tests. The remaining quorum is
unaffected. Expected: modest throughput degradation (retransmissions, higher
latency) but continued availability. Systems with longer retry timeouts may
show larger drops.

### network_partition
100% packet loss on one node (full isolation). For CFT systems: the remaining 2 of 3 still form a quorum. For BFT: 3 of 4 remain, still above 2f+1. Expected: throughput continues at a lower level (one fewer replication target), but no unavailability.

### slow_node
CPU stress on one node (Pumba stress or background `yes > /dev/null` for HotStuff). Models an asymmetrically degraded node. Expected: throughput drops slightly if the slow node is the leader; less impact if it is a follower.

---

## 5. Scalability (cluster size)

Only tested for KRaft and etcd at sizes 3, 5, and 7.

**Why these two**: Both are Raft implementations, so the scalability comparison isolates the implementation-level differences (Kafka's JVM overhead, etcd's Go efficiency, Kafka's partition-based parallelism) from the protocol itself.

**What changes with cluster size**: Raft requires a majority quorum: ⌈(N+1)/2⌉. At N=3, quorum=2. At N=5, quorum=3. At N=7, quorum=4. More nodes means the leader must wait for more followers to acknowledge, increasing commit latency and reducing throughput. The degree of degradation measures how well the implementation handles the increased fan-out.

**Expected results**: Throughput decreases with cluster size. etcd likely degrades more gracefully (lighter processes, lower memory, Go's goroutine scheduling) than KRaft (heavier JVM, higher base memory). But KRaft may sustain higher absolute throughput due to Kafka's batching and partition parallelism.

---

## Sources to Study Per Algorithm

### Paxos
- **Primary**: Lamport, "The Part-Time Parliament" (1998) and "Paxos Made Simple" (2001). The latter is the accessible version.
- **In your bundle**: `3299869_3319893.pdf` — Ailidani et al., Paxi framework with Multi-Paxos and EPaxos benchmarks. Read sections on the Paxos implementation and performance evaluation.
- **Key concept to understand**: The distinction between Phase 1 (Prepare — leader establishment) and Phase 2 (Accept — value commitment). In steady state with a stable leader, only Phase 2 runs — one round-trip.

### Raft
- **Primary**: `atc14paperongaro.pdf` — Ongaro & Ousterhout, USENIX ATC 2014. Read the entire paper; it is designed for understandability. Focus on §5 (Raft basics), §5.2 (leader election), §5.3 (log replication), §5.4 (safety).
- **Secondary**: `2723872_2723876.pdf` — Howard et al., Raft refloated. Contains independent evaluation and analysis of Raft's correctness and performance characteristics, including leader election timing analysis.
- **Key concept**: Strong leadership — all log entries flow from leader to followers. Leader election uses randomized timeouts to break ties.

### ZAB
- **Primary**: `279227_279229.pdf` — Junqueira, Reed, Serafini, "Zab: High-performance broadcast for primary-backup systems." Read §3 (protocol specification) and §4 (properties).
- **Key concept**: ZAB separates crash recovery from normal operation (message broadcasting). The epoch-based model: each leader owns an epoch; epoch changes trigger a recovery protocol. This is the design Kafka+ZooKeeper builds on.

### KRaft
- **Primary**: `2023397.pdf` — Wang et al., "Building a Replicated Logging System with Apache Kafka." Covers Kafka's replication design including ISR semantics.
- **Secondary**: KIP-500 (online) — the design document for removing ZooKeeper from Kafka. Explains why KRaft (Kafka's internal Raft) replaces ZAB for metadata management.
- **Key concept**: KRaft does not replace ZAB for data replication — Kafka still uses ISR-based replication for topic partitions. KRaft replaces ZooKeeper's role in controller election and metadata storage.

### EPaxos
- **Primary**: Moraru, Andersen, Kaminsky, "There Is More Consensus in Egalitarian Parliaments" (SOSP 2013). Not in your bundle but cited. The Paxi paper (`3299869_3319893.pdf`) covers the implementation you are using.
- **Key concept**: Leaderless — any replica can propose and commit in one round-trip (fast path) if the command does not conflict with concurrent commands. Conflict detection uses command interference relations. The fast quorum is ⌊3N/4⌋, larger than Paxos's ⌈(N+1)/2⌉.

### Tendermint
- **Primary**: `Buchman_Ethan_201606_Msater_thesis.pdf` — Buchman's master thesis. Read Chapter 4 (Tendermint consensus algorithm) thoroughly. This is the reference description.
- **Secondary**: `Accountable_Tendermint___DSN_2022.pdf` — extends Tendermint with accountability (detecting which validators misbehaved).
- **Key concepts**: (1) Three-phase voting: Propose → Prevote → Precommit, with 2/3 supermajority required at each phase. (2) Lock mechanism: once a validator precommits a block, it is "locked" on that block for the current round. (3) Deterministic proposer rotation: no election needed, just round-robin. (4) Synchrony-bound: round timeouts govern progress, so latency includes timer waits even when the network is fast.

### HotStuff
- **Primary**: `HotStuff_BFT_Consensus_with_Linearity_and_Responsi.pdf` — Yin, Malkhi, Reiter, Gueta, Abraham, PODC 2019. Read §4 (Basic HotStuff phases) and §5 (Chained HotStuff with pipelining).
- **Secondary**: `1803_05069v6.pdf` — arXiv version with more detail. `2010_11454v10.pdf` — Fast-HotStuff analysis. `2309_17245v1.pdf` — broader BFT protocol comparison.
- **Secondary**: `2103_04234v4.pdf` — PaxiBFT framework paper, implements and compares PBFT, Tendermint, HotStuff, Streamlet under identical conditions. Very relevant to your thesis methodology.
- **Key concepts**: (1) Linearity: O(n) authenticators per decision via QC aggregation, vs O(n²) for PBFT/Tendermint. (2) Responsiveness: leader waits only for n−f actual responses, not a worst-case timeout. (3) Three-chain commit rule: a block is committed when three consecutive QC-chained blocks exist. (4) View-change is identical in structure to normal operation (just pick the highest QC), eliminating the complex view-change protocol of PBFT.
