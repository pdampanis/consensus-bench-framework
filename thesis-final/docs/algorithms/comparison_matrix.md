# Cross-algorithm comparison matrix

This is the unified analytical table that the thesis's "comparative analysis" chapter uses. It collects, in one place, the properties of every algorithm covered in the thesis — experimentally measured or literature-only — indexed by the dimensions that the thesis title explicitly calls out (latency, throughput, fault tolerance, scalability) plus a few others that are essential to the evolution narrative.

Use this table directly in the thesis as Table 1 of the comparative-analysis chapter. Every algorithm row cites the corresponding knowledge doc in `docs/algorithms/` for deeper discussion, and the per-row references cite the paper or implementation we got the values from.

## Table 1 — Protocol comparison

| Property | Paxos | Raft | ZAB | EPaxos | Tendermint | HotStuff |
|---|---|---|---|---|---|---|
| **Year introduced** | 1989 / 1998 | 2014 | 2010 | 2013 | 2014 / 2016 | 2019 |
| **Fault model** | Crash | Crash | Crash | Crash | Byzantine | Byzantine |
| **Fault bound** | F failures in 2F+1 | F failures in 2F+1 | F failures in 2F+1 | F failures in 2F+1 | F Byzantine in 3F+1 | F Byzantine in 3F+1 |
| **Leader model** | Single stable leader (Multi-Paxos) | Single leader | Single leader per epoch | Leaderless (any replica) | Deterministic rotating proposer | Rotating leader with pacemaker |
| **Commit path (common case)** | 1 round-trip (leader → majority) | 1 round-trip (leader → majority) | 1 round-trip (leader → majority) | 1 round-trip (fast path, non-conflicting) | 3 phases: Propose + Prevote + Precommit | 1 round-trip (pipelined via QC) |
| **Commit path (worst case)** | 2 round-trips (Prepare + Accept) | 2 round-trips (with Pre-Vote) | Epoch-change protocol | 2 round-trips (slow path, conflict) | Multi-round on timeout | View change via pacemaker |
| **Message complexity per decision** | O(N) | O(N) | O(N) | O(N) fast path, O(N²) slow | O(N²) per phase | O(N) via vote aggregation |
| **Latency to commit (theoretical)** | 2 × network RTT (steady leader) | 2 × RTT | 2 × RTT | 2 × RTT (fast path) | ≥4 × RTT (three phases + commit) | 2 × RTT + pipelining |
| **Published throughput (single DC)** | ~10³–10⁴ ops/s (implementation-dependent) | ~10⁴–10⁵ ops/s (etcd, KRaft) | ~10⁴ ops/s (ZK metadata) | ~10⁴ ops/s (fast path dominant) | ~10²–10³ tx/s | ~10⁴–10⁵ tx/s (large cluster) |
| **Scales well with cluster size** | Yes (leader amortizes) | Yes | Limited (epoch change grows) | Not above ~5 (conflict frequency) | Poor (O(N²) messages) | Yes (linear voting) |
| **Production implementations** | Chubby (Google), Spanner | etcd, Kafka KRaft, Consul | Apache ZooKeeper | Research (efficient/epaxos, Paxi) | CometBFT, Cosmos SDK | Diem/Aptos (fork), research |
| **Reference paper in bundle** | 3299869_3319893.pdf (benchmarks) + 279227_279229.pdf (original) | atc14paperongaro.pdf | (ZAB paper not in corpus — cite Junqueira et al. DSN 2011) | 3299869_3319893.pdf | Buchman_Ethan_201606_Msater_thesis.pdf | HotStuff_BFT_Consensus.pdf |
| **Our experimental target** | Paxi (paxi-paxos.yml) | etcd (etcd.yml), Kafka KRaft (kafka-kraft.yml) | Kafka+ZK (kafka-zk.yml) | Paxi (paxi-epaxos.yml) | CometBFT (tendermint.yml) | asonnino/hotstuff (hotstuff.yml) |
| **Knowledge doc** | `paxos.md` | `raft.md` + `kraft.md` + `etcd.md` | `zab.md` | `epaxos.md` | `tendermint.md` | `hotstuff.md` |

## Table 2 — Measured metrics matrix (filled in post-campaign)

After the campaign completes, `analysis/out/summary.csv` contains the numbers that populate this table. This is the form you paste directly into the thesis:

| System | Baseline throughput (ops/s) | p99 latency (ms) | Leader-kill median failover (s) | Under packet-loss (% of baseline) | Under slow-node (% of baseline) | Cluster sizes tested |
|---|---|---|---|---|---|---|
| KRaft | (from summary.csv) | (from summary.csv) | (from leader_election.csv) | (from comparison.csv) | (from comparison.csv) | 3, 5, 7 |
| Kafka+ZK | (from summary.csv) | (from summary.csv) | (from leader_election.csv) | (from comparison.csv) | (from comparison.csv) | 3 |
| etcd | (from summary.csv) | (from summary.csv) | (from leader_election.csv) | (from comparison.csv) | (from comparison.csv) | 3, 5, 7 |
| Paxi-Paxos | (from summary.csv) | (from summary.csv) | (from leader_election.csv) | (from comparison.csv) | (from comparison.csv) | 3 |
| Paxi-EPaxos | (from summary.csv) | (from summary.csv) | (from leader_election.csv) | (from comparison.csv) | (from comparison.csv) | 3 |
| CometBFT | (from summary.csv) | (from summary.csv) | (from leader_election.csv) | (from comparison.csv) | (from comparison.csv) | 4 |
| HotStuff (asonnino) | (from summary.csv) | mean-only (see hotstuff.md) | (from leader_election.csv) | (from comparison.csv) | (from comparison.csv) | 4 |

## How to use these tables in the thesis

The thesis "comparative analysis" chapter has a standard structure, and the two tables above map onto it directly:

**Section 1 — Evolution narrative** (uses Table 1 rows in order). Tell the story chronologically: Paxos established the template; ZAB adapted it for ZooKeeper's session semantics; Raft re-specified it for understandability and production adoption; EPaxos questioned the single-leader assumption; Tendermint extended it to Byzantine fault tolerance; HotStuff closed the performance gap between BFT and CFT. This section is pure literature with the Table 1 rows for reference.

**Section 2 — Theoretical properties** (uses Table 1 columns). Organize by property: first discuss fault models across all protocols (CFT vs BFT), then commit paths, then message complexity. Each subsection covers one row of the Table 1 transpose. Reference the Algorithm docs (`docs/algorithms/*.md`) for deeper per-protocol discussion.

**Section 3 — Experimental comparison** (uses Table 2 plus the hero figures `fig01`..`fig06`). This is where the measured data goes. Every cell in Table 2 is a claim that needs to be defended with a reference to `summary.csv` or a figure. The Mann-Whitney U test results in `pairwise_tests.csv` answer "is this difference statistically significant".

**Section 4 — Synthesis**. Which protocol is best? The honest thesis answer is "it depends on the deployment model". Crash-only, operator-controlled single-DC: Raft (via etcd or KRaft) has the best engineering. Crash-only, geo-distributed: EPaxos's no-leader design has theoretical advantages we could not fully test on a single host. Byzantine-tolerant: HotStuff if you need scale, Tendermint if you need simpler operations. High-throughput log: KRaft. Low-latency coordination metadata: etcd. This section synthesizes the above tables into a decision framework.

## Discussion: What the "evolution" claim really means

The thesis summary commits to studying "the evolution of consensus protocols". The evolution story this matrix supports is:

1. **Paxos proved consensus is solvable.** The mathematical achievement was enormous; the specification was notoriously hard to understand.
2. **ZAB showed consensus could be productized** for a specific system (ZooKeeper) with strong workload-specific optimizations.
3. **Raft showed consensus could be re-specified** understandably, which enabled the widespread production adoption we see today (etcd, Kafka KRaft, Consul, CockroachDB, TiKV, ...).
4. **EPaxos showed the single-leader assumption was not essential** and could be relaxed for better latency/throughput on low-conflict workloads.
5. **Tendermint extended consensus to Byzantine settings** for blockchain applications with untrusted participants.
6. **HotStuff showed Byzantine consensus could be made nearly as fast as crash-fault consensus** through linear communication and pipelining.

Each step solved a problem the previous step left open. This is the evolution narrative the thesis title commits to, and the tables above are its empirical and theoretical scaffold.
