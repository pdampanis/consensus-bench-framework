# Thesis Document Outline

## Title
**Evolution and Comparison of Consensus Protocols in Distributed Systems**

---

## Chapter 1: Introduction (3–5 pages)

### 1.1 The distributed systems problem
- From single-machine computation to networks of machines (ARPANET → cloud).
- The fundamental tension: multiple independent nodes must agree on state despite failures and delays.
- Real-world stakes: financial transactions, air traffic control, blockchain ledgers, cloud databases.
- Cite: Lamport's "Time, Clocks, and the Ordering of Events" (1978), Fischer-Lynch-Paterson (1985).

### 1.2 What consensus is and why it's hard
- Definition: a set of processes must agree on a single value, despite crashes and arbitrary message delays.
- FLP impossibility result (Fischer, Lynch, Paterson 1985): in a purely asynchronous system, consensus is impossible with even one crash. This is not a dead end — it defines the boundary that practical protocols work around (partial synchrony, failure detectors, randomized algorithms).
- The two fault models: Crash Fault Tolerance (CFT) vs Byzantine Fault Tolerance (BFT).
- Cite: `fischer_impossibility_1985` in `new-entries.bib`.

### 1.3 Thesis scope and contributions
- Study the evolution of consensus protocols from Paxos (1989) to HotStuff (2019).
- Analyze 7 protocols theoretically: Paxos, Raft, ZAB, KRaft, EPaxos, Tendermint, HotStuff.
- Measure 5 protocols experimentally on a multi-VM cluster under 6 fault scenarios.
- Evaluate 4 criteria: throughput, latency, fault tolerance, scalability.
- Provide a reproducible experimental bundle (Docker + Hetzner cloud).

### 1.4 Thesis structure
- Chapter 2: Historical background and the need for consensus.
- Chapter 3: Theoretical analysis of each protocol.
- Chapter 4: Comparative theoretical analysis across protocols.
- Chapter 5: Experimental methodology and architecture.
- Chapter 6: Experimental results and analysis.
- Chapter 7: Conclusions and future work.

---

## Chapter 2: Historical and Technical Background (8–12 pages)

### 2.1 The emergence of distributed systems
- 1960s–70s: timesharing systems, ARPANET, early network protocols.
- 1980s: workstation clusters, Network File System (NFS), remote procedure calls (RPC).
- The shift from "one machine, one job" to "many machines, one service."
- Cite: Tanenbaum & Van Steen, *Distributed Systems: Principles and Paradigms*.

### 2.2 The consistency problem
- Replication: why we copy data across machines (fault tolerance, locality, throughput).
- The consistency-replication trade-off: more replicas = better availability but harder to keep consistent.
- Strong vs eventual consistency. The CAP theorem (Brewer 2000, Gilbert & Lynch 2002).
- State machine replication (Schneider 1990): the formal model underlying all consensus protocols.

### 2.3 Why consensus became necessary
- Databases needed agreement on commit/abort (two-phase commit, 1970s).
- Distributed file systems needed agreement on metadata (Google GFS, 2003).
- Coordination services needed leader election (Chubby, ZooKeeper).
- Blockchains needed decentralized agreement without trust (Bitcoin, 2008).
- Each generation of distributed systems re-encountered the same problem: how do N nodes agree?

### 2.4 The FLP impossibility and the path forward
- What FLP actually says: deterministic consensus in asynchronous systems with one crash is impossible. It does not say consensus is impossible in practice.
- The escape hatches: partial synchrony (Dwork, Lynch, Stockmeyer 1988), failure detectors (Chandra & Toueg 1996), randomization (Ben-Or 1983).
- Every protocol in this thesis operates under partial synchrony: the system is eventually synchronous, and protocols are safe always, live after GST (Global Stabilization Time).
- Cite: `fischer_impossibility_1985`.

### 2.5 Fault models: CFT vs BFT
- Crash Fault Tolerance (CFT): nodes fail by stopping. Majority quorum (2f+1). Simpler, faster, sufficient for operator-controlled environments.
- Byzantine Fault Tolerance (BFT): nodes may behave arbitrarily (lying, equivocating, signing invalid data). Supermajority quorum (3f+1). Required when participants are mutually untrusted.
- Cite: Lamport, Shostak, Pease 1982 (*The Byzantine Generals Problem*).

---

## Chapter 3: Protocol-by-Protocol Theoretical Analysis (20–30 pages)

For each protocol, cover: **year, authors, fault model, leader model, phases/message flow, commit rule, message complexity, liveness argument, safety argument, key innovation over predecessor, production usage.**

### 3.1 Paxos (Lamport 1989/1998)
- Single-decree Paxos: Prepare/Promise → Accept/Accepted. Two-phase voting with ballot numbers.
- Multi-Paxos: stable leader skips Prepare phase after election. This is what every real system uses.
- The safety invariant: if a value is chosen at ballot b, any future proposer with a higher ballot will re-propose the same value.
- Why it was hard to understand: the original paper was allegorical (the Part-Time Parliament). Paxos Made Simple (2001) clarified it.
- Production: Google Chubby (2006), Google Spanner (2012).
- Reference implementation measured: Paxi (Ailidani et al. 2019, SIGMOD).
- Cite: `lamport_part-time_1998`, `lamport_paxos_2001`.

### 3.2 Raft (Ongaro & Ousterhout 2014)
- Designed explicitly for understandability: "Paxos is hard to understand; Raft is not."
- Three sub-protocols: leader election (randomized timeouts), log replication (AppendEntries), safety (leader completeness).
- Commit rule: a log entry is committed when replicated to a majority and at least one entry from the current leader's term is also committed (the "leader completeness" property).
- Pre-vote: prevents disrupted nodes from disrupting elections with higher terms.
- Production: etcd (CoreOS/CNCF), Consul (HashiCorp), TiKV (PingCAP), CockroachDB, Kafka KRaft (KIP-500, 2019).
- Reference implementation measured: etcd v3.5.15, Kafka KRaft 3.7.0.
- Cite: `atc14-paper-ongaro.pdf` (in corpus).

### 3.3 ZAB — ZooKeeper Atomic Broadcast (Junqueira et al. 2010)
- Consensus protocol purpose-built for ZooKeeper's primary-backup architecture.
- Three phases: Leader Election, Discovery (sync), Broadcast. Epoch-based leader model.
- Difference from Paxos: ZAB allows a new leader to have an incomplete log, then performs explicit state transfer during Discovery. Paxos requires the leader to learn the full history through the Accept phase.
- Production: Apache ZooKeeper (2010–present). Kafka used ZooKeeper for metadata consensus until KIP-500 replaced it with KRaft.
- Reference implementation measured: Kafka 3.7.0 in ZooKeeper mode (confluentinc/cp-kafka).
- Cite: `279227.279229.pdf` (in corpus).

### 3.4 EPaxos — Egalitarian Paxos (Moraru et al. 2013)
- Key innovation: removes the stable leader. Any replica can propose a command at any time.
- Fast path: non-conflicting commands commit in 1 round-trip without contacting a leader.
- Slow path: conflicting commands require an additional Prepare round (2 round-trips).
- Dependency tracking: each command carries a dependency set. Replicas build a conflict graph and execute in topological order.
- Limitation: under moderate-to-high conflict, performance degrades substantially (Tollman et al. 2021). Our experiments test this.
- Reference implementation measured: Paxi (ailidani/paxi, EPaxos mode).
- Cite: `2517349.2517350.pdf` (in corpus), `tollman_epaxos_2021` in `new-entries.bib`.

### 3.5 Tendermint (Buchman 2014/2016, now CometBFT)
- First BFT consensus designed for blockchain applications (Cosmos ecosystem).
- Rounds with deterministic rotating proposer. Each round: Propose → Prevote → Precommit → Commit.
- 2f+1 votes needed for each phase to advance. 3f+1 validators tolerate f Byzantine.
- All-to-all voting: O(n²) messages per decision. This limits scalability.
- Merkle-tree-based state: blocks linked by hashes, like Bitcoin but with BFT finality.
- Reference implementation measured: CometBFT v0.38.12, 4 validators.
- Cite: `Buchman_Ethan_201606_Msater_thesis.pdf` (in corpus), `buchman_latest_2018` in `new-entries.bib`.

### 3.6 HotStuff (Yin et al. 2019, PODC)
- Key innovations: linear communication complexity (O(n) per decision) AND responsiveness (commits at actual network speed, not timeout-bound).
- Three-phase voting pipeline: Prepare → Pre-Commit → Commit. Each phase's Quorum Certificate (QC) is input to the next.
- Three-chain commit rule: a block is committed when three consecutive QC-chained blocks exist.
- Pacemaker: handles view changes. New leader collects QCs from the previous view and proposes.
- Production forks: Diem (Meta, formerly Libra), Aptos, Sui, Flow.
- Reference implementation measured: asonnino/hotstuff (2-chain Diem variant), 4 validators in one container.
- Methodology limitation: harness reports only mean latency, no percentiles. Latency CDF shows mean-only.
- Cite: `HotStuff_BFT_Consensus_with_Linearity_and_Responsi.pdf` (in corpus), `yin_hotstuff_2019` in `Thesis.bib`.

### 3.7 KRaft — Kafka Raft (KIP-500, 2019)
- Replaced ZooKeeper with an embedded Raft implementation inside Kafka brokers.
- Combined broker+controller process: same Kafka binary acts as both data broker and metadata controller.
- KRaft controller quorum manages topic configuration, partition leadership, ISR membership.
- Log-based metadata: the controller log is a partitioned, replicated Raft log.
- Production: Kafka 3.3+ (KRaft production-ready).
- Reference implementation measured: apache/kafka:3.7.0 in KRaft mode (combined broker+controller).
- Cite: KIP-500 (online), `2023-397.pdf` (in corpus).

---

## Chapter 4: Comparative Theoretical Analysis (10–15 pages)

Use `docs/algorithms/comparison_matrix.md` as the source table. Expand each row into a subsection.

### 4.1 Fault models across protocols
- CFT: Paxos, Raft, ZAB, KRaft, EPaxos. Bound: f failures in 2f+1.
- BFT: Tendermint, HotStuff. Bound: f Byzantine in 3f+1.
- The cost of BFT: 3f+1 vs 2f+1 means more replicas, more messages, lower throughput.
- This sets up the headline experimental finding: BFT systems are ~100× slower than CFT systems.

### 4.2 Leader models
- Single stable leader: Paxos, Raft, ZAB, KRaft.
- Rotating deterministic proposer: Tendermint.
- Rotating leader with pacemaker: HotStuff.
- Leaderless (any replica): EPaxos.
- Implications for performance under faults: leader-based systems have a single point of bottleneck (throughput limited by leader's NIC/CPU) but simpler recovery; leaderless systems avoid this but have higher conflict resolution overhead.

### 4.3 Commit paths and message complexity
- CFT protocols: 1 round-trip in common case (Paxos Multi-Paxos, Raft, ZAB). O(n) messages.
- EPaxos fast path: 1 round-trip, O(n) messages. Slow path: 2 round-trips, O(n²) dependency tracking.
- Tendermint: 3 phases per round, O(n²) messages (all-to-all voting).
- HotStuff: 3 phases pipelined, O(n) messages via vote aggregation.

### 4.4 Latency characteristics
- Paxos/Raft: 2× network RTT (leader → majority → leader).
- Tendermint: ≥4× RTT (three phases + commit broadcast). Timer-bound round advancement.
- HotStuff: 2× RTT + pipelining. Responsive — commits at actual network delay, not timeout-bound.

### 4.5 Scalability
- Raft-family: scales sub-linearly (leader amortizes; followers process in parallel).
- EPaxos: degrades under conflict as dependency graphs grow.
- Tendermint: O(n²) messaging limits practical size to ~100 validators.
- HotStuff: linear O(n) voting enables 100+ validator deployments (proven in Diem/Aptos).

### 4.6 Summary table
- Paste Table 1 from `docs/algorithms/comparison_matrix.md` into the thesis.

---

## Chapter 5: Experimental Methodology (10–15 pages)

### 5.1 Systems under test
- 7 systems: KRaft, Kafka+ZK, etcd, CometBFT, Paxi-Paxos, Paxi-EPaxos, HotStuff (asonnino).
- Why these 7: the 5 named in the thesis summary + ZAB (adjacent protocol in the Kafka ecosystem) + KRaft (Raft-variant production system).
- Implementation sources: apache/kafka:3.7.0, quay.io/coreos/etcd:v3.5.15, cometbft/cometbft:v0.38.12, ailidani/paxi (master), asonnino/hotstuff (main).

### 5.2 Deployment architecture
- **Local mode** (development): Docker Compose, single i7/32GB host. 12 compose files. Used for development and correctness testing only.
- **VM mode** (thesis data): 5 Hetzner CPX21 VMs (3 vCPU, 4 GB RAM each). Private network 10.0.0.0/24.
  - node1–node4: consensus nodes (one container per node, `--network host`).
  - loadgen: benchmark orchestration, data collection, Prometheus/Grafana.
  - Inter-node communication via /etc/hosts → private IPs. Typical latency: 0.2–0.5 ms.
- Container placement map: documented in `deploy/lib.sh`.

### 5.3 Experiment design
- **6 scenarios**: baseline, leader_kill, packet_loss (5%), network_partition (one node isolated), double_kill (two nodes down), slow_node (CPU stress on one node).
- **Statistical design**:
  - Baseline: n=10 runs per cell (Mann-Whitney U with n₁=n₂=10, minimum detectable Vargha-Delaney A ≈ 0.78 at α=0.05).
  - Fault scenarios: n=5 runs per cell.
  - Scalability: n=3 runs per cluster size (3, 5, 7 for KRaft and etcd).
  - Leader-election: 200 trials per system.
- **Total**: 257 main cells + 1400 leader-election trials, ~48 hours of unattended execution.

### 5.4 Metrics collection
- **Throughput**: per-second or per-5-second samples from the benchmark client (kafka-producer-perf-test.sh, etcd benchmark, cmt_bench.py, paxi_bench.py, hs-run.sh).
- **Latency**: avg, p50, p95, p99, p99.9, max — recorded client-side, reported in microseconds.
- **Leader failover time**: from kill timestamp to first post-kill successful operation. Measured via external probe container (le_probe.py) surviving the kill.
- **Prometheus metrics**: per-container CPU, memory, network (via cAdvisor). etcd internal metrics (Raft proposals, commit durations). CometBFT consensus metrics.
- **Correctness**: per-run durability check. For baseline: verify ≥95% of sent records are retrievable. For fault scenarios: skip (expected data loss). HotStuff: no read API — reports execution completeness.

### 5.5 Fault injection methodology
- **VM mode**: SSH-based injection (`deploy/faults.sh`).
  - leader_kill: detect leader via protocol-specific query, `docker kill` on correct node.
  - packet_loss: `tc qdisc add dev eth0 root netem loss 5%` on all 4 nodes.
  - network_partition: bidirectional `iptables DROP` on target node + all other nodes.
  - slow_node: `stress-ng --cpu 2` on leader node.
  - double_kill: leader + one other node killed.
- **Local mode**: Pumba (Docker network emulation) for Kraft/ZK/etcd/Tendermint/Paxi. In-container injection for HotStuff.

### 5.6 Warmup and measurement protocol
- Warmup: 180 seconds (leader election, log replay, steady state).
- Measurement: 300 seconds.
- Total run duration: 480 seconds (8 minutes) per cell.
- Settle time between runs: 20 seconds.

### 5.7 Hardware specification
- Recorded in `results/environment.txt` at campaign start.
- Kernel version, CPU model, RAM, Docker version, inter-node ping latency.

---

## Chapter 6: Experimental Results and Analysis (25–35 pages)

### 6.1 Baseline throughput (fig01)
- Bar chart: mean throughput ± 95% bootstrap CI for all 7 systems.
- Expected ranking: KRaft > Kafka+ZK ≈ etcd > Paxi-Paxos > Paxi-EPaxos > CometBFT ≈ HotStuff.
- The BFT cost: HotStuff and CometBFT are ~100× slower than KRaft. This is the cost of Byzantine fault tolerance, quantified.
- Key finding: KRaft throughput advantage over Kafka+ZK (no ZK coordination overhead). Test: Mann-Whitney U, one-sided H₁: KRaft > ZK.
- **Insert fig01_throughput.png**

### 6.2 Latency characteristics (fig02)
- p99 latency CDF across all 7 systems at baseline.
- HotStuff label: "mean-only" — the asonnino harness reports only mean latency, so the CDF is a vertical line.
- Expected: etcd has lowest p99 latency (lightweight, single-operation commits). Kafka has higher p99 (log batching). CometBFT has highest p99 (3-phase voting + round timers).
- **Insert fig02_latency_cdf.png**

### 6.3 Leader-election failover (fig03)
- CDF of failover time across 200 trials per system.
- Expected: etcd fastest (sub-second, single randomized election). CometBFT 1–3 seconds (round-timeout duration). Paxos slower than Raft (split-vote scenarios).
- HotStuff: very fast failover (view-change is one message round).
- **Insert fig03_leader_election.png**

### 6.4 Throughput under fault scenarios (fig04)
- Grouped bar chart: mean throughput per system per scenario.
- Expected: all systems sustain near-baseline throughput under packet_loss. leader_kill causes temporary dip then recovery. network_partition drops throughput for the partition duration. double_kill crosses quorum → zero throughput. slow_node degrades throughput proportionally.
- BFT vs CFT under faults: BFT systems degrade more under network faults (all-to-all voting vs leader-centric commit).
- **Insert fig04_under_fault.png**

### 6.5 KRaft vs Kafka+ZK (fig05)
- Direct head-to-head across all scenarios.
- Expected: KRaft outperforms ZK mode in every scenario (no ZooKeeper coordination overhead).
- This confirms the thesis's "evolution" narrative: KRaft replaced ZK for a measurable reason.
- **Insert fig05_kraft_vs_zk.png**

### 6.6 Scalability (fig06)
- Throughput vs cluster size (3, 5, 7) for KRaft and etcd.
- Expected: both curves descend sub-linearly. Raft theory predicts sub-linear decay because the leader broadcasts to followers in parallel.
- KRaft descends more gently than etcd: record batching amortizes consensus cost.
- **Insert fig06_scalability.png**

### 6.7 Pairwise statistical tests
- Mann-Whitney U + Vargha-Delaney A for all 21 system pairs at baseline.
- Table: system A, system B, n_A, n_B, effect_pct, vargha_delaney_a, p_value, reliable.
- Discuss which differences are statistically significant AND practically meaningful (not just p < 0.05, but A > 0.56).

### 6.8 Correctness results
- Summary of per-run correctness checks.
- Baseline: all systems pass ≥95% durability verification. HotStuff reports NO_DURABILITY_API (methodology limitation).
- Fault scenarios: correctness not checked (data loss expected and protocol-correct).

### 6.9 Discussion
- What the numbers mean, not just what they are.
- The evolution narrative supported by data: each protocol generation solved a measurable problem the previous generation left open.
- BFT is not "worse than CFT" — it serves a different threat model. The throughput gap is the cost of Byzantine tolerance, not an indictment.

---

## Chapter 7: Conclusions and Future Work (5–8 pages)

### 7.1 Summary of contributions
- Theoretical analysis of 7 consensus protocols spanning 30 years of evolution.
- Reproducible experimental framework covering 5 named algorithms under 6 fault scenarios.
- Quantified the BFT-vs-CFT throughput gap (~100× on 3–4 node clusters).
- Confirmed KRaft's throughput advantage over ZooKeeper-based Kafka (evolution narrative).
- Documented all methodology limitations transparently (mean-only HotStuff latency, no Byzantine fault injection).

### 7.2 Which protocol should you use?
- Single-DC, CFT, operator-controlled: Raft (via etcd or KRaft) has the best engineering.
- High-throughput log: KRaft.
- Low-latency metadata coordination: etcd.
- Geo-distributed, CFT: EPaxos's leaderless design has theoretical advantages (not fully testable on single-VM or single-region deployment).
- Untrusted participants, BFT: HotStuff for scale, Tendermint for simpler operations.
- There is no single "best" — the answer depends on the deployment model.

### 7.3 Limitations of this work
- Single-region deployment (no WAN latency evaluation).
- 4 BFT validators (minimum configuration; doesn't show scalability characteristics of BFT).
- HotStuff latency percentiles unavailable (mean-only).
- No Byzantine fault injection (equivocation, invalid signing not tested).
- Spot-sample Paxi correctness (no scan API).
- Co-located containers in local mode (not used for thesis claims; VM deployment avoids this).

### 7.4 Future work
- Add Byzantine fault injection (modified CometBFT/HotStuff binaries that equivocate).
- Extend BFT scalability to 7/13/31 validators (AWS, separate VMs).
- Add WAN deployment (multi-region, cross-datacenter latency).
- Implement full history recording and linearizability checking (Porcupine/Jepsen-style).
- Instrument HotStuff client for per-transaction latency percentiles.

---

## Appendices

### Appendix A: Protocol message-flow diagrams
- Visual diagrams of the message-passing patterns for each protocol's commit path.
- Source: `visualizer/backend/src/main/resources/algorithms/*.json` (the algorithm animation specs).

### Appendix B: Full experimental results
- `summary.csv` in full.
- `pairwise_tests.csv` in full.
- `leader_election.csv` in full.

### Appendix C: Deployment commands
- The exact Hetzner CLI commands, cloud-init.yaml, and deployment scripts.
- Source: `deploy/` directory.

### Appendix D: Methodology limitations register
- Copy of `analysis/out/methodology.md` (auto-generated).
- Every known limitation with its handling in the analysis pipeline.

### Appendix E: Visualizer screenshots
- Results tab: system+scenario drill-down with aggregated stats.
- Compare tab: cross-system side-by-side for baseline throughput.
- Algorithms tab: animated network view for each protocol.
- Grafana dashboard: Prometheus metrics during a representative run.

---

## Bibliography

All references from:
- `Thesis.bib` — primary bibliography (25+ entries)
- `new-entries.bib` — supplementary entries (Fischer 1985, Lamport 1998/2001, Buchman 2018, Zhang 2024, etc.)
- `Consensus Thesis Corpus/` — PDFs of the most-cited papers

Key citations by chapter:
| Chapter | Primary citations |
|---|---|
| 1 (Introduction) | Fischer-Lynch-Paterson 1985, Lamport 1978 |
| 2 (Background) | Tanenbaum, Schneider 1990, Brewer 2000, Dwork-Lynch-Stockmeyer 1988 |
| 3.1 (Paxos) | Lamport 1998, Lamport 2001, Ailidani et al. 2019 |
| 3.2 (Raft) | Ongaro & Ousterhout 2014 |
| 3.3 (ZAB) | Junqueira et al. 2010 |
| 3.4 (EPaxos) | Moraru et al. 2013, Tollman et al. 2021 |
| 3.5 (Tendermint) | Buchman 2016, Buchman et al. 2018 |
| 3.6 (HotStuff) | Yin et al. 2019, Jalalzai et al. 2024 |
| 3.7 (KRaft) | Wang et al. 2015, KIP-500 |
| 5 (Methodology) | Charapko et al. 2021, Alqahtani & Demirbas 2021 |
