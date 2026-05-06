# Thesis Writing Guide — Section-by-Section with Citations

Each section below lists: **what to write**, **which paper(s) to cite**, **what specific claim or quote from that paper to reference**, and **which file in the bundle provides the data** (for experimental sections). Numbered items are suggested paragraph topics.

---

## Chapter 1: Introduction

### 1.1 The distributed systems problem (1 page)

1. **Open with scale.** "A single computer can process N operations per second. A service that needs 1000×N operations must run on many computers. But many computers cannot share memory, cannot trust each other's clocks, and cannot assume the others are alive." This is the distributed systems problem in one sentence.

2. **Cite Lamport 1978** (`Time, Clocks, and the Ordering of Events`, CACM). Reference the *happens-before* relation. Quote: *"The concept of 'happening before' defines an invariant partial ordering of the events in a distributed multiprocess system."* This is the intellectual foundation — without shared time, ordering must be constructed from causality.

3. **Historical arc.** ARPANET (1969) → workstation clusters (1980s) → web-scale services (2000s) → cloud computing (2010s) → blockchain (2020s). Each generation increased the number of machines per service by an order of magnitude, and each re-encountered the same problem: how do N independent nodes agree on state?

4. **The stakes.** "When a bank processes a transfer across two data centers, the credit and debit must both happen or neither must happen — a consensus problem. When an air traffic control system hands off an aircraft between regions, both controllers must agree on the aircraft's position — a consensus problem. When a blockchain validates a transaction, thousands of mutually untrusted nodes must agree on its inclusion — a consensus problem."

### 1.2 What consensus is and why it's hard (1 page)

1. **Define consensus formally.** Three properties: Agreement (no two correct processes decide differently), Validity (any decided value was proposed by some process), Termination (every correct process eventually decides). Cite **Schneider 1990** (`Implementing Fault-Tolerant Services Using the State Machine Approach`, ACM Computing Surveys). Quote: *"The state machine approach is a general method for implementing a fault-tolerant service by replicating servers and coordinating client interactions with server replicas."*

2. **The FLP impossibility.** Cite **Fischer, Lynch, Paterson 1985** (`Impossibility of Distributed Consensus with One Faulty Process`, JACM). State the result precisely: in a purely asynchronous system, no deterministic protocol can guarantee termination with even one crash failure. Quote: *"No completely asynchronous consensus protocol can tolerate even a single unannounced process death."* Emphasize: this is not a dead end — it defines the boundary that practical protocols work around.

3. **The escape hatches from FLP.**
   - **Partial synchrony**: cite **Dwork, Lynch, Stockmeyer 1988** (`Consensus in the Presence of Partial Synchrony`, JACM). The system is eventually synchronous; protocols are safe always, live after GST (Global Stabilization Time). Every protocol in this thesis operates in this model.
   - **Failure detectors**: cite **Chandra & Toueg 1996** (`Unreliable Failure Detectors for Reliable Distributed Systems`, JACM). The weakest class sufficient for consensus is ◇W (eventually weak). Quote: *"Consensus is solvable using an eventually weak failure detector (◇W), and any failure detector that can be used to solve consensus is at least as strong as ◇W."*
   - **Randomization**: Ben-Or 1983 (not covered in this thesis but acknowledged as a third path).

4. **Two fault models.** CFT (Crash Fault Tolerance): nodes fail by stopping. Majority quorum 2f+1. Simpler, faster. BFT (Byzantine Fault Tolerance): nodes may behave arbitrarily — lying, equivocating, sending contradictory messages. Supermajority quorum 3f+1. Cite **Lamport, Shostak, Pease 1982** (`The Byzantine Generals Problem`, TOPLAS). Quote: *"The reliability required for a computer system to correctly withstand arbitrary malfunctioning of its components."*

### 1.3 The CAP theorem context (0.5 page)

1. Cite **Brewer 2000** (PODC keynote) for the conjecture: a distributed system can provide at most two of Consistency, Availability, Partition tolerance.
2. Cite **Gilbert & Lynch 2002** (`Brewer's Conjecture and the Feasibility of Consistent, Available, Partition-Tolerant Web Services`, SIGACT News) for the formal proof. Quote: *"In a model with asynchronous networks, it is impossible to implement a read/write data object that guarantees both availability and atomic consistency in all executions where messages may be lost."*
3. Consensus protocols choose CP (consistent and partition-tolerant, sacrificing availability during partitions). This is the design trade-off that motivates the entire thesis.

### 1.4 Thesis scope and contributions (1 page)

1. **Theoretical contribution."** Analyze 7 consensus protocols spanning 30 years of evolution: Paxos (1989/1998), ZAB (2010), Raft (2014), EPaxos (2013), KRaft (2019), Tendermint (2014/2016), HotStuff (2019). For each: protocol phases, fault model, message complexity, commit rule, safety/liveness argument."

2. **Experimental contribution.** "Measure 5 of the 7 protocols experimentally (Paxos, Raft, EPaxos, Tendermint, HotStuff — all five named in the thesis summary) under 6 fault scenarios on a 5-node VM cluster. Evaluate throughput, latency, fault tolerance, and scalability."

3. **Reproducibility contribution.** "Provide a complete, open-source experimental bundle: Docker Compose for local development, Hetzner cloud deployment scripts for multi-VM execution, automated analysis pipeline with statistical tests and methodology documentation, and a web-based visualizer for exploring results."

4. **What is NOT in scope.** "This thesis does not implement any consensus protocol from scratch. It evaluates existing implementations. It does not inject Byzantine behavior (equivocation, invalid signatures) — crash faults test BFT liveness bounds, not safety. It does not evaluate geo-distributed (WAN) deployments."

### 1.5 Thesis structure (0.25 page)

- Chapter 2: Historical and technical background.
- Chapter 3: Protocol-by-protocol theoretical analysis.
- Chapter 4: Comparative theoretical analysis across protocols.
- Chapter 5: Experimental methodology and testbed architecture.
- Chapter 6: Experimental results and analysis.
- Chapter 7: Conclusions and future work.

---

## Chapter 2: Historical and Technical Background

### 2.1 The emergence of distributed systems (2 pages)

1. **1960s–70s: timesharing and the first networks.** CTSS (1961), Multics (1965), ARPANET (1969). The NCP protocol (1970) and TCP/IP (1983). These established that independent machines could communicate reliably — the prerequisite for distributed computation.

2. **1980s: workstation clusters.** Sun Microsystems NFS (1984), Apollo Domain (1980s), the V System (1984). Remote Procedure Calls: Birrell & Nelson 1984. These proved that a network of workstations could behave like a single system — but the illusion was fragile.

3. **1990s: the Internet era.** The Web (HTTP 1991, Berners-Lee), distributed objects (CORBA, DCOM), cluster computing (Beowulf, 1994). The scale of systems grew from tens to thousands of nodes.

4. **2000s–2010s: cloud computing and web-scale services.** Amazon Web Services (2006), Google's internal infrastructure (GFS 2003, MapReduce 2004, Bigtable 2006, Spanner 2012). These systems ran on hundreds of thousands of machines across dozens of data centers. Consensus became a production necessity, not a theoretical curiosity.

5. **2020s: blockchain and decentralized systems.** Bitcoin (2008, Nakamoto) introduced decentralized consensus without trusted parties. Ethereum (2015) generalized it to programmable state machines. Cosmos/Tendermint, Diem/Aptos/HotStuff brought BFT consensus to mainstream blockchain engineering.

### 2.2 The consistency problem (2 pages)

1. **Why replicate?** Three reasons: fault tolerance (if one copy fails, others survive), performance (read from nearest copy), and availability (service survives network partitions). Cite the standard distributed systems textbooks.

2. **The consistency-replication tension.** The more copies, the harder to keep them identical. Every write must propagate to every replica. During propagation, different replicas see different states. This is the fundamental trade-off: replication improves availability and fault tolerance but makes consistency harder.

3. **Strong vs eventual consistency.** Strong consistency: all replicas appear to execute the same sequence of operations (linearizability). Eventual consistency: replicas converge given enough time without new writes. The thesis covers only strongly consistent (consensus-based) systems.

4. **The CAP theorem in practice.** Reference Brewer 2000 and Gilbert & Lynch 2002 again. Explain that consensus-based systems are CP: during a network partition, they sacrifice availability (stop accepting writes) to preserve consistency (no divergent states). Eventually-consistent systems are AP: they accept writes during partitions but may diverge.

5. **State machine replication.** Cite **Schneider 1990** in depth. The paradigm: model a service as a deterministic state machine. Replicate the state machine across servers. Use consensus to agree on the sequence of commands each replica executes. If every replica starts in the same state and executes the same commands in the same order, every replica ends in the same state. This is the formal justification for why consensus matters.

### 2.3 Why consensus became necessary (1.5 pages)

Each generation of distributed systems independently discovered it needed consensus:

1. **Databases: two-phase commit (1970s).** Distributed transactions need atomic commitment — all participants agree on commit or abort. 2PC is the simplest solution but blocks on coordinator failure. This motivated Paxos.

2. **Distributed file systems: metadata agreement.** Google GFS (2003) used a single master to avoid consensus. This became a bottleneck, motivating distributed metadata systems that do require consensus (Colossus, HDFS NameNode HA).

3. **Coordination services: leader election.** Google Chubby (2006) used Paxos for leader election and configuration storage. Apache ZooKeeper (2010) used ZAB. etcd (2013) used Raft. These became the backbone of modern cloud infrastructure.

4. **Blockchains: decentralized agreement.** Bitcoin (2008) used proof-of-work — a probabilistic, non-BFT consensus with high latency and energy cost. This motivated deterministic BFT for permissioned and proof-of-stake blockchains. Tendermint (2014), HotStuff (2019), and their derivatives now secure billions of dollars in assets.

### 2.4 Fault models: CFT vs BFT (1 page)

1. **Crash Fault Tolerance.** Formal definition: a faulty process follows the protocol correctly until some point, then stops forever. It never sends messages after crashing. The bound: f crash faults in 2f+1 total processes. Proof sketch: if f processes crash, the remaining f+1 must form a majority of 2f+1. Any two majorities intersect in at least one correct process, ensuring a consistent decision across views. Cite the standard result.

2. **Byzantine Fault Tolerance.** Formal definition: a faulty process may exhibit any behavior, including sending contradictory messages to different peers, lying about its state, or colluding with other faulty processes. The bound: f Byzantine faults in 3f+1 total processes (with oral messages). Cite **Lamport, Shostak, Pease 1982** for the original formulation and proof.

3. **Why BFT costs more.** In CFT, a leader can trust that followers honestly report whether they accepted a proposal. In BFT, the leader must collect and verify a supermajority (2f+1 out of 3f+1) to be certain that at least one correct process endorsed the decision and that no two conflicting decisions can both gather 2f+1 votes. This requires more replicas, more messages, and cryptographic verification.

---

## Chapter 3: Protocol-by-Protocol Analysis

For each protocol, cover in this order: **historical context → fault model → leader model → protocol phases (with a diagram) → commit rule → message complexity → safety argument (informal) → liveness argument (informal) → key innovation → production usage → experimental target in this thesis.**

### 3.1 Paxos (4–5 pages)

**Papers to cite:**
- **Lamport 1998** (`The Part-Time Parliament`, ACM TOCS) — `279227.279229.pdf` in corpus. The original Paxos paper. Quote: *"The Paxon parliament's protocol provides a new way of implementing the state machine approach to the design of distributed systems."*
- **Lamport 2001** (`Paxos Made Simple`, SIGACT News) — in `new-entries.bib`. The accessible re-explanation. Use this paper's terminology (proposers, acceptors, learners) for the protocol description.

**What to write:**

1. **Context.** "Paxos was invented by Leslie Lamport in 1989 and published in 1998. The original paper, written as a fictional account of a parliamentary procedure on the island of Paxos, was considered so impenetrable that it took nearly a decade to be published. Lamport later wrote Paxos Made Simple (2001), which presented the same algorithm in plain English."

2. **Single-decree Paxos.** Describe the two-phase protocol:
   - Phase 1 (Prepare/Promise): Proposer picks ballot number b > any it has seen. Sends Prepare(b) to acceptors. Acceptor responds with Promise(b, v') where v' is the highest-numbered proposal it has accepted, or nil. If the proposer receives Promises from a majority, proceed.
   - Phase 2 (Accept/Accepted): Proposer sends Accept(b, v) where v is either its own proposed value or, if any Promise contained a value, the value with the highest ballot. Acceptor accepts unless it has promised a higher ballot. Once a majority accepts, the value is chosen.
   - The safety invariant: if a value v is chosen at ballot b, any proposer with ballot b' > b will learn v during Phase 1 and must re-propose v.

3. **Multi-Paxos.** The optimization that makes Paxos practical: elect a stable leader. The leader runs Phase 1 once (to establish its ballot), then skips Phase 1 for subsequent proposals — issuing only Phase 2 messages. This reduces commit latency from 2 round-trips to 1 round-trip in the common case.

4. **Why Paxos is hard.** The algorithm has many roles (proposer, acceptor, learner), many message types (Prepare, Promise, Accept, Accepted), and subtle interactions (a proposer must re-propose the highest-numbered accepted value, not its own). The original paper's allegorical presentation made this harder. Raft was explicitly a reaction to this complexity.

5. **Experimental target.** Paxi framework (Ailidani et al. 2019, SIGMOD). Cite `3299869.3319893.pdf` in corpus. Paxi implements classical Multi-Paxos and serves as the reference Paxos implementation for our measurements.

### 3.2 Raft (4–5 pages)

**Papers to cite:**
- **Ongaro & Ousterhout 2014** (`In Search of an Understandable Consensus Algorithm`, USENIX ATC) — `atc14-paper-ongaro.pdf` in corpus. The definitive Raft paper. Quote: *"Raft separates the key elements of consensus, such as leader election, log replication, and safety, and it enforces a stronger degree of coherency to reduce the number of states that must be considered."*
- **Howard et al. 2015** (`Raft Refloated: Do We Have Consensus?`, SIGOPS OSR) — `2723872.2723876.pdf` in corpus. Critical analysis of Raft's corner cases. Use this to add nuance: Raft is simpler than Paxos but not trivial.

**What to write:**

1. **Design philosophy.** Raft was designed for understandability. Ongaro and Ousterhout observed that Paxos, despite being correct, was notoriously hard to implement correctly. Raft decomposes consensus into three independent subproblems: leader election, log replication, and safety. Each can be understood in isolation.

2. **Leader election.** Servers are in one of three states: leader, follower, candidate. Time is divided into terms. Each term begins with an election. Followers use randomized election timeouts (150–300ms) — the first to time out becomes candidate, increments its term, and requests votes. If it receives votes from a majority, it becomes leader. The randomized timeout prevents split votes (two candidates starting elections simultaneously). If no candidate wins, terms increase and the process repeats.

3. **Log replication.** The leader accepts client commands, appends them to its log, and sends AppendEntries RPCs to followers. Once a majority of followers have appended the entry, the leader commits it and applies it to its state machine. The leader then notifies followers of the commit. All log entries flow from leader to followers — followers never initiate log writes.

4. **Safety.** Raft's key safety property: leader completeness. A leader must have all committed entries from previous terms. This is enforced by the election restriction: a candidate's log must be at least as up-to-date as the majority that votes for it (checked via term and index comparison). This guarantees the Leader Completeness Property. The commit rule requires that an entry from the leader's current term must be committed before entries from prior terms can be considered committed — preventing the situation where a leader commits an old entry that a future leader might overwrite.

5. **Pre-vote.** An optimization not in the original paper but widely implemented: before incrementing its term and requesting votes, a candidate sends pre-vote requests. Only if a majority responds positively does it proceed to a real election. This prevents a partitioned node from disrupting the cluster when it rejoins with an inflated term. Both etcd and KRaft implement pre-vote.

6. **The Howard et al. critique.** "Raft Refloated" (Howard et al. 2015) identified subtle interactions between leader election and log replication that can cause availability degradation. A leader that is network-partitioned from a majority but can still communicate with one follower will repeatedly attempt to replicate, consuming resources and delaying recovery. This is not a safety violation but an operational concern. Mention this to show critical engagement with the protocol, not just praise.

7. **Experimental targets.** etcd v3.5.15 (the canonical production Raft implementation) and Kafka KRaft 3.7.0 (Raft adapted for log-based metadata management). Two implementations of the same algorithm allow us to distinguish algorithmic properties from implementation-specific choices.

### 3.3 ZAB — ZooKeeper Atomic Broadcast (2–3 pages)

**Papers to cite:**
- **Junqueira, Reed, Serafini 2010** (`Zab: High-performance Broadcast for Primary-Backup Systems`, IEEE/IFIP DSN). Note: `279227.279229.pdf` is Lamport's Paxos, NOT ZAB. The ZAB paper must be cited from external sources or from references in the ZooKeeper documentation.
- **Hunt et al. 2010** (`ZooKeeper: Wait-free Coordination for Internet-scale Systems`, USENIX ATC). The ZooKeeper paper that contextualizes ZAB's role.

**What to write:**

1. **Context.** ZAB was designed specifically for Apache ZooKeeper, a coordination service that provides a hierarchical namespace (like a filesystem) with strong ordering guarantees. ZooKeeper's architecture is primary-backup: one server is the leader (primary), others are followers (backups). All writes go through the leader. ZAB is the protocol that replicates the leader's state to followers.

2. **Three phases.** Leader Election, Discovery (synchronization), Broadcast. In Leader Election, a prospective leader gathers votes. In Discovery, the new leader determines the most recent committed state and synchronizes followers. In Broadcast, the leader proposes state changes that followers acknowledge. A proposal is committed once a quorum of followers acknowledge it.

3. **Difference from Paxos.** ZAB allows a new leader to have an incomplete log. During Discovery, the leader explicitly synchronizes its state with followers — it does not need to "learn" the full history through the Paxos Prepare mechanism. This is an optimization for ZooKeeper's workload (reads dominate, writes are infrequent, state is small).

4. **Role in this thesis.** ZAB is evaluated indirectly: we benchmark Kafka in ZooKeeper mode (confluentinc/cp-kafka:7.5.0), where ZooKeeper provides metadata consensus. The KRaft-vs-ZK comparison (Chapter 6, fig05) directly measures the benefit of replacing ZAB with Raft.

### 3.4 EPaxos (3–4 pages)

**Papers to cite:**
- **Moraru, Andersen, Kaminsky 2013** (`There Is More Consensus in Egalitarian Parliaments`, SOSP) — `2517349.2517350.pdf` in corpus. The definitive EPaxos paper. Quote: *"EPaxos provides optimal commit latency in the wide-area for non-conflicting commands, handles conflicts efficiently, and its throughput scales almost linearly with the number of replicas."*
- **Tollman, Park, Ousterhout 2021** (`EPaxos Revisited`, NSDI) — in `new-entries.bib`. Re-evaluation that finds EPaxos degrades under moderate-to-high conflict. Use this to add nuance: the leaderless design has a trade-off.

**What to write:**

1. **The leaderless insight.** "All previously described protocols (Paxos, Raft, ZAB) have a single stable leader that proposes all commands. This creates a bottleneck: the leader's CPU and network bandwidth limit total throughput. EPaxos asks: what if any replica can propose a command at any time?"

2. **Dependency tracking.** EPaxos does not establish a total order of commands. Instead, each command carries a dependency set — the set of commands that must execute before it. Replicas build a conflict graph and execute commands in topological order. Commands that do not conflict (do not access the same keys) can execute in any order, enabling true parallelism.

3. **Fast path vs slow path.** For non-conflicting commands, EPaxos commits in 1 round-trip: the proposer sends the command to a fast quorum (2f replicas out of 2f+1), they record it, and reply. No leader contact needed. If commands conflict (their dependency sets differ), EPaxos falls back to a slow path requiring an additional round. Under high conflict, the slow path dominates and performance degrades.

4. **The Tollman et al. critique.** "EPaxos Revisited" found that the original EPaxos evaluation used a workload with artificially low conflict. Under moderate-to-high conflict (realistic for many applications), EPaxos's latency and throughput degrade substantially. This is not a flaw in the protocol design — it is the expected trade-off of leaderless consensus. The thesis's experimental evaluation tests this directly.

5. **Experimental target.** Paxi framework in EPaxos mode (ailidani/paxi, `-algorithm=epaxos`). Same codebase as Paxos, enabling direct head-to-head comparison without implementation-specific confounds.

### 3.5 Tendermint / CometBFT (3–4 pages)

**Papers to cite:**
- **Buchman 2016** (`Tendermint: Byzantine Fault Tolerance in the Age of Blockchains`, M.Sc. Thesis, University of Guelph) — `Buchman_Ethan_201606_Msater_thesis.pdf` in corpus. The foundational description of Tendermint.
- **Buchman, Kwon, Milosevic 2018** (`The Latest Gossip on BFT Consensus`, arXiv) — in `new-entries.bib`. The protocol specification.
- **Cason, Fynn, Milosevic, Milosevic, Buchman, Pedone 2021** (`The Design, Architecture and Performance of the Tendermint Blockchain Network`, SRDS) — `Pedone_2021_IEEE_SRDS.pdf` in corpus. The most comprehensive performance evaluation. Quote: *"To the best of our knowledge, this is the most comprehensive performance evaluation of a Tendermint blockchain network to date."*
- **Buchman, Guerraoui, Komatovic et al. 2022** (`Revisiting Tendermint: Design Tradeoffs, Accountability, and Practical Use`, DSN) — `Accountable_Tendermint___DSN_2022.pdf` in corpus. Formalization and accountability analysis.

**What to write:**

1. **Context.** Tendermint was the first BFT consensus protocol designed specifically for blockchain applications. It emerged from the Cosmos ecosystem, which aimed to create an "Internet of Blockchains." Unlike PBFT (Castro & Liskov 1999), which was designed for closed-membership systems, Tendermint supports open, proof-of-stake validator sets.

2. **Protocol structure.** Rounds with a deterministic rotating proposer. Each round has three phases: Propose (proposer broadcasts a block), Prevote (every validator votes on the proposal), Precommit (validators that see 2f+1 Prevotes for the same block send Precommit). A block is committed when a validator sees 2f+1 Precommits. The proposer rotation is deterministic — round r's proposer is validators[r mod N] — preventing a single Byzantine validator from blocking progress by refusing to propose.

3. **The ABCI interface.** Tendermint's key architectural innovation: the Application Blockchain Interface (ABCI) decouples the consensus engine from the state machine (the application). The consensus engine (Tendermint Core, now CometBFT) handles block proposal, voting, and commitment. The application (e.g., Cosmos SDK) handles transaction validation and state transitions. They communicate through a socket protocol. This separation meant blockchain developers could build applications without modifying consensus code — a significant engineering contribution.

4. **Message complexity.** O(n²) messages per decision: each validator sends to every other validator in Prevote and Precommit phases. This limits practical validator set size to ~100–200. HotStuff (Section 3.6) directly addresses this limitation with linear O(n) voting.

5. **Accountability.** Unlike PBFT and HotStuff, Tendermint supports accountability: when a safety violation occurs, misbehaving validators can be cryptographically identified from the messages they signed. Cite Buchman et al. 2022 (DSN) for the formalization.

6. **Experimental target.** CometBFT v0.38.12, 4 validators. This is the minimum BFT configuration (f=1 in 3f+1). The thesis does not test Tendermint scalability beyond 4 validators — this is documented as a scope limitation.

### 3.6 HotStuff (4–5 pages)

**Papers to cite:**
- **Yin, Malkhi, Reiter, Gueta, Abraham 2019** (`HotStuff: BFT Consensus with Linearity and Responsiveness`, PODC) — `HotStuff_BFT_Consensus_with_Linearity_and_Responsi.pdf` in corpus. The core algorithm. Cite this for the protocol description and theoretical properties.
- **Yin et al. 2019** (arXiv full version) — `1803.05069v6.pdf` in corpus. Cite this for the protocol-interpretation framework and extended evaluation. Quote: *"HotStuff is the first partially synchronous BFT replication protocol exhibiting... responsiveness and communication complexity that is linear in the number of replicas."*
- **Jalalzai, Niu, Feng, Gai 2023** (`Fast-HotStuff: A Fast and Resilient HotStuff Protocol`, IEEE TDSC) — `2010.11454v10.pdf` in corpus. Post-HotStuff optimization showing that three rounds can be reduced to two. Cite this in the "variants" discussion. Quote: *"Fast-HotStuff has lower latency and is more robust against performance attacks that HotStuff is susceptible to."*

**What to write:**

1. **The two-property breakthrough.** "Before HotStuff, no BFT protocol achieved both linear communication complexity and responsiveness simultaneously. PBFT (Castro & Liskov 1999) had responsiveness but quadratic O(n²) messaging — every replica communicated with every other during view changes. Tendermint had linear messaging under synchrony but was not responsive — rounds advanced on timer expiration, not at actual network speed. HotStuff achieved both properties through a novel voting pipeline."

2. **The pipeline mechanism.** HotStuff has three phases per decision: Prepare, Pre-Commit, Commit. Each phase produces a Quorum Certificate (QC) — a threshold signature aggregating n-f votes. Crucially, these phases are pipelined: the Prepare phase for block B+1 runs concurrently with the Pre-Commit for B and the Commit for B-1. A block is committed when three consecutive QC-chained blocks exist (the three-chain commit rule).

3. **Why linearity works.** In PBFT/Tendermint, all-to-all voting during view changes requires O(n²) messages because every replica must convince every other that a view change is justified. In HotStuff, the new leader collects QCs from the previous view and broadcasts them in a single message. Each QC is a single threshold signature attesting that n-f replicas endorsed the previous phase. This reduces view-change communication to O(n).

4. **Responsiveness.** After GST (Global Stabilization Time), a correct leader waits only for the first n-f responses — not for a timeout. The protocol commits at the speed of actual network delay, not maximum network delay. This is the property that gives HotStuff sub-second latency in practice, compared to Tendermint's several seconds (timer-bound).

5. **The 2-chain variant (Diem).** The production deployment of HotStuff in Diem (Meta's blockchain, formerly Libra) uses a 2-chain variant: the Pre-Commit phase is eliminated. A block is committed when two consecutive QC-chained blocks exist. This saves one message delay at the cost of a slightly more complex leader-replacement mechanism. Our experimental evaluation uses this variant via the asonnino/hotstuff reference implementation.

6. **Experimental target.** asonnino/hotstuff (main branch, 2-chain Diem variant), 4 validators in a single container. Methodology limitation: the harness reports only mean latency, not percentiles. This is documented transparently.

### 3.7 KRaft — Kafka Raft (1.5–2 pages)

**Papers to cite:**
- **KIP-500** (Apache Kafka Improvement Proposal, 2019): Replace ZooKeeper with a Self-Managed Metadata Quorum. Online specification. Cite the KIP number and the Apache wiki.
- **Wang, Koshy, Subramanian et al. 2015** (`Building a Replicated Logging System with Apache Kafka`, PVLDB) — referenced in the thesis bundle. Describes the original Kafka architecture and motivates the eventual removal of ZooKeeper.

**What to write:**

1. **Motivation.** Kafka was originally built on ZooKeeper for metadata management: ZooKeeper stored topic configurations, partition leadership, and controller election. This created an operational burden: operators had to manage two distributed systems (Kafka + ZooKeeper). ZooKeeper was also a bottleneck — metadata changes required ZooKeeper writes, and ZooKeeper's ZAB protocol was not optimized for Kafka's access patterns.

2. **The KRaft controller quorum.** KIP-500 replaced ZooKeeper with an embedded Raft implementation inside Kafka brokers. The KRaft controller quorum (3 or 5 nodes) manages metadata using a replicated Raft log. Each controller can also act as a data broker, reducing the total number of processes.

3. **Controller log vs data log.** The metadata log is separate from Kafka's data partitions. The metadata log is a Raft-replicated log that stores topic configurations, partition assignments, and ISR (In-Sync Replica) membership. Data partitions continue to use Kafka's existing replication protocol (leader-follower with ISR).

4. **Significance for this thesis.** KRaft is a case study in protocol evolution: replacing one consensus protocol (ZAB, via ZooKeeper) with another (Raft, embedded). Our KRaft-vs-ZK comparison (Chapter 6, fig05) directly measures the performance impact of this replacement.

---

## Chapter 4: Comparative Theoretical Analysis

Use `docs/algorithms/comparison_matrix.md` as the source for Table 1. Each section below expands one row of that table.

### 4.1 Fault models across protocols (1.5 pages)

1. **Table: fault model per protocol.** Paxos/Raft/ZAB/KRaft/EPaxos = CFT, 2f+1. Tendermint/HotStuff = BFT, 3f+1.
2. **Why BFT needs more replicas.** In BFT, a leader cannot trust that a follower's "yes" means the follower actually accepted the proposal — it might be lying. The leader needs 2f+1 matching responses out of 3f+1 total to guarantee that at least one correct process endorsed the decision and that no two conflicting decisions can both gather 2f+1 votes. This is the proof from Lamport, Shostak, Pease 1982.
3. **The cost in practice.** Cite **Alqahtani & Demirbas 2021** (`Bottlenecks in Blockchain Consensus Protocols`, COINS) — `2103.04234v4.pdf` in corpus. Quote: *"Even when there is enough network bandwidth, the CPU cost of serialization and deserialization of the messages limits the throughput and increases the latency of the protocols."* This sets up the experimental finding: BFT systems are ~100× slower than CFT systems, and the bottleneck is often CPU, not network.

### 4.2 Leader models (1 page)

- Single stable leader: Paxos, Raft, ZAB, KRaft. Leader is the throughput bottleneck but simplifies recovery.
- Deterministic rotating proposer: Tendermint. No single bottleneck, but round transitions cost time.
- Rotating leader with pacemaker: HotStuff. Linear view change, responsive.
- Leaderless: EPaxos. Maximum parallelism under low conflict, degrades under high conflict.

### 4.3 Commit paths and message complexity (1.5 pages)

- CFT: 1 round-trip, O(N) messages. Cite Ongaro & Ousterhout 2014 for Raft's AppendEntries mechanism.
- EPaxos fast path: 1 round-trip, O(N). Slow path: 2 round-trips, O(N²) dependency graph tracking. Cite Moraru et al. 2013.
- Tendermint: 3 phases, O(N²) all-to-all voting per phase. Cite Buchman 2016.
- HotStuff: 3 phases pipelined, O(N) via vote aggregation (QC = threshold signature). Cite Yin et al. 2019.

### 4.4 Latency characteristics (1 page)

- Paxos/Raft: 2×RTT. Steady leader, minimal overhead.
- Tendermint: ≥4×RTT (Propose + Prevote + Precommit + Commit broadcast). Round timers add fixed overhead. Expected: 30–80ms on LAN at 4 validators.
- HotStuff: 2×RTT + pipelining. Responsive — commits at actual network delay. Expected: 2–10ms at 4 validators (asonnino's published numbers).

### 4.5 Scalability (1 page)

- Cite **Ailijiang et al. 2019** (Paxi, SIGMOD) for the throughput formula: throughput is inversely proportional to the number of replicas and proportional to the batch size.
- Raft: sub-linear decay (leader broadcasts to followers in parallel). KRaft amortizes consensus cost across record batches.
- EPaxos: good scalability under low conflict, degrades under high conflict. Cite Tollman et al. 2021.
- Tendermint: O(N²) messaging limits practical size. Cite Alqahtani & Demirbas 2021.
- HotStuff: linear O(N) voting enables 100+ validator deployments. Cite Yin et al. 2019 and Jalalzai et al. 2023.

### 4.6 Summary table

Insert Table 1 from `docs/algorithms/comparison_matrix.md` as a full-page table.

---

## Chapter 5: Experimental Methodology

### 5.1 Systems under test (1 page)

1. **7 systems.** List each with:
   - Name, algorithm, version, Docker image.
   - Source: who wrote it, is it production or research code.
   - Why it was chosen: which thesis-summary algorithm it represents.

2. **Why 5 of 5 named algorithms are measured.** The thesis summary commits to measuring "implementations of some of the above [protocols]." We measure all 5 named algorithms: Paxos, Raft, EPaxos, Tendermint, HotStuff. Plus ZAB (adjacent in the Kafka evolution story) and KRaft (production Raft variant). This is the strongest possible interpretation of "some of."

### 5.2 Deployment architecture (2 pages)

1. **Local mode (development).** Docker Compose on a single i7/32GB host. 12 compose files. Used for correctness testing, development, and smoke testing. Not used for thesis performance claims (co-location interference makes throughput/latency numbers unreliable on a single host).

2. **VM mode (thesis data).** 5 Hetzner CPX21 VMs (3 vCPU, 4 GB RAM, 40 GB NVMe each). Ubuntu 24.04. Private network 10.0.0.0/24. Typical inter-node latency: 0.2–0.5 ms.

3. **Node roles:**
   - node1–node4: consensus nodes. Each runs one container per system with `--network host`.
   - loadgen: benchmark orchestration, data collection, Prometheus, Grafana, cAdvisor. Does not run consensus nodes (except HotStuff's single-container cluster).

4. **Container placement.** Documented in `deploy/lib.sh`. Example: for a 3-node KRaft cluster, k1→node1, k2→node2, k3→node3. For a 4-node CometBFT cluster, tm1→node1, tm2→node2, tm3→node3, tm4→node4. HotStuff runs all 4 validator processes in a single container on loadgen.

5. **Name resolution.** /etc/hosts maps container names to private IPs. This enables inter-container communication without Docker Swarm or overlay networks.

6. **Provisioning.** Cloud-init (`deploy/cloud-init.yaml`) installs Docker, tunes kernel (BBR congestion control, increased socket buffers), disables swap. Setup script (`deploy/setup_cluster.sh`) uploads the thesis bundle, builds custom images, distributes them.

### 5.3 Experiment design (2 pages)

1. **6 scenarios** (described with what fault is injected and what protocol behavior is tested):
   - **baseline**: no faults. Steady-state throughput and latency.
   - **leader_kill**: kill the current leader/proposer. Tests leader-election failover time and throughput recovery.
   - **packet_loss**: 5% packet loss on all inter-node links via `tc netem`. Tests resilience to unreliable networks.
   - **network_partition**: isolate one node via `iptables DROP` on all other nodes (bidirectional). Tests partial-connectivity behavior.
   - **double_kill**: kill two nodes. Crosses the majority quorum boundary for CFT (2f+1 with 2 dead = liveness lost). Expected: zero throughput.
   - **slow_node**: CPU stress on one node using `stress-ng`. Tests performance under resource contention.

2. **Statistical design:**
   - **n=10 for baseline.** Rationale: Mann-Whitney U with n₁=n₂=10 can detect Vargha-Delaney A ≥ 0.78 at α=0.05 (vs A ≥ 0.90 with n=5). Baseline cells are where the thesis's primary claims live (pairwise comparisons, bootstrap CIs).
   - **n=5 for fault scenarios.** Power is lower but these scenarios serve a qualitative purpose (does the system survive? does throughput drop to zero?) rather than quantitative comparison.
   - **n=3 for scalability.** Three data points (3, 5, 7) are the minimum to distinguish linear from non-linear trends.
   - **200 leader-election trials per system.** Produces a smooth CDF with ~3.5% margin of error at the median.
   - **Total**: 257 main cells + 1400 leader-election trials. ~48 hours of unattended execution.

3. **Cite Sondhi 2021** (`Empirical Performance Evaluation of Consensus Algorithms in Permissioned Blockchain Platforms`, M.Sc. Thesis, University of Windsor) — `Sondhi_uwindsor_0115O_12677.pdf` in corpus. His thesis applies chaos engineering to consensus evaluation, similar to our approach.

### 5.4 Metrics collection (1.5 pages)

1. **Per-run metrics.** For every (system, scenario, run_id) cell:
   - `throughput.csv`: time_offset_sec, ops_per_sec. Per-second (or per-5s) samples during the measurement window.
   - `latency.csv`: avg, p50, p95, p99, p99_9, max in microseconds.
   - `correctness.txt`: PASS (≥95% of sent records retrievable), FAIL, NO_DURABILITY_API (HotStuff), SKIP (fault scenarios).
   - `manifest.json`: full metadata (system, scenario, timestamps, cluster size, kernel, docker version).

2. **How throughput is measured.** Per system:
   - Kafka (KRaft/ZK): `kafka-producer-perf-test.sh` — built-in tool that reports records/sec every 5 seconds.
   - etcd: `/usr/local/bin/benchmark put` — built-in tool. Limitation: reports only summary rate, not per-second samples. Per-run mean is correct; within-run variance is unavailable (documented in methodology.md).
   - CometBFT: `cmt_bench.py` — custom Python driver using `/broadcast_tx_commit` RPC.
   - Paxi: `paxi_bench.py` — custom Python driver using Paxi's HTTP API (PUT/GET).
   - HotStuff: `hs-run.sh` — runs asonnino's `fab local` harness inside the container. Limitation: harness reports only summary TPS and mean latency. Per-second samples and latency percentiles are unavailable (documented).

3. **How latency percentiles are measured.** CometBFT, Paxi, and Kafka report full percentile distributions (avg, p50, p95, p99, p99.9, max). etcd reports latency distribution from which percentiles are extracted. HotStuff reports only mean latency — all percentile fields are set to the mean. The analysis pipeline flags this as `zero_variance=True` and marks pairwise latency comparisons as `reliable=False`.

4. **Prometheus + cAdvisor.** Per-container CPU, memory, network bytes (scraped every 10s). etcd internal Raft metrics (proposal durations, commit latency). CometBFT consensus metrics (block time, vote counts, round steps).

5. **Network baseline.** Captured before the campaign: `results/environment.txt` contains kernel, CPU, memory, Docker version, and inter-node ping latency.

### 5.5 Fault injection methodology (1 page)

1. **VM mode** (`deploy/faults.sh`): SSH-based injection.
   - Leader detection: protocol-specific query before kill (KRaft: `kafka-metadata-quorum.sh`, etcd: `endpoint status`, Tendermint: `/block` proposer address, Paxos: `/state` isLeader).
   - leader_kill: SSH → `docker kill` on leader container.
   - packet_loss: SSH → `tc qdisc add dev eth0 root netem loss 5%` on all 4 nodes for the measurement duration.
   - network_partition: SSH → bidirectional `iptables` DROP on both sides (target node + all peers).
   - slow_node: SSH → `stress-ng --cpu 2` for the measurement duration.
   - double_kill: leader + one other node.

2. **Scope limitation: no Byzantine fault injection.** "This thesis tests BFT liveness bounds (can the cluster survive f crash faults?) but does not inject Byzantine behavior (equivocation, invalid signatures, contradictory messages). Testing BFT safety properties requires modified validator binaries that deliberately misbehave, which is out of scope. This limitation is consistent with similar academic benchmarking studies."

### 5.6 Warmup and measurement protocol (0.5 page)

- Warmup: 180 seconds (leader election settles, log replay completes, throughput stabilizes).
- Measurement: 300 seconds.
- Total per run: 480 seconds (8 minutes).
- Between runs: 20-second settle time.
- Between scenarios: cluster is not torn down (same stack, repeated runs).

---

## Chapter 6: Experimental Results and Analysis

Each section below corresponds to one hero figure plus the associated statistical tests.

### 6.1 Baseline throughput (fig01) (4–5 pages)

**Data source:** `analysis/out/summary.csv` (rows where scenario=baseline, metric=throughput). `analysis/out/pairwise_tests.csv` (rows where scenario=baseline, metric=throughput). `analysis/out/plots/fig01_throughput.png`.

**What to write:**

1. **Describe the figure.** "Figure 1 shows mean baseline throughput with 95% bootstrap confidence intervals for all 7 systems. The y-axis is logarithmic due to the two-order-of-magnitude range between the fastest (KRaft) and slowest (CometBFT/HotStuff) systems."

2. **Ranking and absolute numbers.** List each system's mean throughput and CI from summary.csv. Example: "KRaft achieved X ops/s (95% CI [Y, Z]) at baseline. CometBFT achieved A tx/s (95% CI [B, C])."

3. **The BFT cost.** "The most striking result is the two-order-of-magnitude throughput gap between CFT and BFT systems. KRaft's throughput of X ops/s is approximately 100× CometBFT's A tx/s and 100× HotStuff's D tx/s. This gap reflects the fundamental cost of Byzantine fault tolerance: BFT systems require supermajority quorums (3f+1 vs 2f+1), all-to-all voting (CometBFT) or cryptographic threshold signatures (HotStuff), and larger messages with cryptographic material."

4. **Cite Alqahtani & Demirbas 2021** to explain the bottleneck: *"Even when there is enough network bandwidth, the CPU cost of serialization and deserialization of the messages limits the throughput and increases the latency of the protocols."* Our data supports this: the VMs had sufficient network bandwidth (private network, 0.2–0.5ms latency), yet BFT throughput was capped by per-message cryptographic operations.

5. **Pairwise comparisons.** Table: system A, system B, n_A, n_B, effect_pct, vargha_delaney_a, p_value, reliable. Discuss the key pairs:
   - KRaft > ZK: "KRaft's X ops/s vs ZK's Y ops/s. The difference is statistically significant (p = 0.00X, Vargha-Delaney A = 0.XX). This confirms that replacing ZooKeeper's ZAB-based metadata consensus with embedded Raft eliminates a bottleneck, supporting the evolution narrative."
   - etcd > Paxi-Paxos: "etcd's X ops/s vs Paxi-Paxos's Y ops/s. The difference is statistically significant (p = 0.00X). This reflects the gap between a production-tuned implementation (etcd) and a research framework (Paxi) — not necessarily an algorithmic difference. Both implement Multi-Paxos/Raft with stable leaders."
   - CometBFT ≈ HotStuff: "CometBFT's A tx/s vs HotStuff's D tx/s. If the difference is NOT statistically significant (p > 0.05), this supports the theoretical expectation that BFT protocols have comparable small-cluster throughput, with HotStuff's linear communication advantage becoming apparent only at larger validator counts."

6. **Explain any counter-intuitive results.** If Paxi-EPaxos is slower than Paxi-Paxos (which it often is on a LAN with low conflict), explain: "EPaxos's leaderless design and dependency tracking impose per-command overhead that is not amortized on a low-conflict, low-latency LAN. The design's advantages (optimal wide-area latency, no leader bottleneck) are not exercised in our single-DC deployment — a known limitation."

### 6.2 Latency characteristics (fig02) (3–4 pages)

**Data source:** `analysis/out/summary.csv` (rows where scenario=baseline, metric=lat_p99_us). `analysis/out/plots/fig02_latency_cdf.png`.

**What to write:**

1. **Describe the figure.** "Figure 2 shows the cumulative distribution of p99 write latency at baseline. Each curve represents the distribution of per-run p99 latencies across the n=10 baseline runs for that system. Systems with genuine percentile data show smooth CDFs. HotStuff shows a vertical line because the asonnino harness reports only mean latency — all percentile values are set to the mean."

2. **Ranking.** List each system's median p99 latency from summary.csv. Expected: etcd has lowest p99 (fast single-operation commits), CometBFT highest (3-phase voting + round timers), HotStuff very low (pipelined, responsive).

3. **The HotStuff caveat.** "HotStuff's p99 latency in this figure is the *mean* latency, not a true 99th percentile. The asonnino benchmark harness reports only mean end-to-end latency. All HotStuff latency percentiles in this thesis should be interpreted as the mean value. This limitation is documented transparently in the methodology chapter and in the auto-generated methodology.md. The analysis pipeline marks HotStuff latency pairwise comparisons as `reliable=False`."

4. **Interpretation.** "etcd's sub-millisecond p99 latency reflects Raft's efficient leader-driven commit: a single AppendEntries round-trip with small payloads. Kafka's higher p99 (X ms) reflects the log-batching architecture: the producer batches records before sending, adding end-to-end delay. CometBFT's highest p99 (Y–Z ms) reflects the three-phase voting protocol and round-timer overhead."

5. **BFT latency comparison.** "CometBFT's median p99 of X ms vs HotStuff's Y ms. HotStuff's responsiveness property (committing at actual network speed, not timeout-bound) predicts lower latency than Tendermint's timer-bound rounds. Our data [supports / does not support] this prediction."

### 6.3 Leader-election failover (fig03) (3–4 pages)

**Data source:** `analysis/out/leader_election.csv`. `analysis/out/plots/fig03_leader_election.png`.

**What to write:**

1. **Describe the figure.** "Figure 3 shows the CDF of leader-election failover time across 200 trials per system. Failover time is measured from the moment the leader process is killed to the first successful write after the kill, as observed by an external probe client."

2. **Ranking and interpretation.** Expected:
   - etcd fastest (median ~200–500ms): single randomized election, no Pre-Vote overhead in the local case.
   - KRaft (median ~500ms–1s): similar Raft election but with KRaft controller-specific overhead.
   - Tendermint (median ~1–3s): round-timeout duration. The deterministic proposer rotation means the next proposer must wait for the current round to time out before starting a new one.
   - HotStuff fast (median ~200–500ms): view-change protocol is a single message round (new leader collects QCs and broadcasts). The pacemaker handles this efficiently.
   - Paxos (median ~1–3s): Multi-Paxos leader election can involve multiple Prepare rounds (split votes), giving it a longer tail than Raft.

3. **Statistical comparison.** "The difference between etcd's median X ms and Paxi-Paxos's median Y ms is [significant / not significant]. This supports the theoretical expectation that Raft's randomized election timeouts and Pre-Vote mechanism provide more predictable failover than classic Multi-Paxos, which can experience multi-round split-vote scenarios."

4. **Tail latency.** "The 95th percentile failover time is more informative than the median for availability analysis. A system that fails over in 200ms 50% of the time but 5s 5% of the time has worse availability than one that fails over in 500ms consistently."

### 6.4 Throughput under fault scenarios (fig04) (3–4 pages)

**Data source:** `analysis/out/summary.csv` (all scenarios). `analysis/out/plots/fig04_under_fault.png`.

**What to write:**

1. **Describe the figure.** "Figure 4 shows mean throughput for each system under each fault scenario. Baseline bars show the steady-state throughput from Section 6.1. The remaining bars show how throughput degrades under each injected fault."

2. **Per-scenario findings:**
   - **leader_kill:** "All systems show a temporary throughput dip during leader re-election, followed by recovery to near-baseline levels. The dip is deeper for Paxos (multi-round elections) and shallower for Raft (efficient randomized elections)."
   - **packet_loss:** "All systems sustain most of their baseline throughput under 5% packet loss, confirming that majority-quorum protocols tolerate occasional message loss through retransmission. HotStuff shows a larger drop (X% vs Y% for KRaft), consistent with its all-to-all voting during view changes amplifying the effect of lost messages."
   - **network_partition:** "Throughput drops to zero or near-zero for all systems when one node is isolated. For CFT systems with 3 nodes, isolating one node leaves 2 (no majority — the system cannot commit). For BFT systems with 4 validators, isolating one leaves 3 (a supermajority — the system SHOULD continue committing if the isolated node is not the leader). Our data [confirms / contradicts] this expectation."
   - **double_kill:** "All systems drop to zero throughput. Two dead nodes cross the majority boundary for CFT (need 2 of 3 alive) and the supermajority boundary for BFT (need 3 of 4 alive). This confirms the liveness bounds predicted by theory."
   - **slow_node:** "All systems show reduced throughput when one node is under 70% CPU load, but the degree varies. [Describe which systems are most/least affected.]"

3. **Cross-reference Ailijiang et al. 2019 (Paxi).** Their throughput formulas predict that a slow follower should have less impact on leader-based protocols (leader continues processing, slow follower just delays commit) than on leaderless protocols (every replica participates in every decision). Our data [supports / does not support] this.

### 6.5 KRaft vs Kafka+ZK (fig05) (2–3 pages)

**Data source:** `analysis/out/summary.csv` (rows where system=kraft or zk). `analysis/out/pairwise_tests.csv`. `analysis/out/plots/fig05_kraft_vs_zk.png`.

**What to write:**

1. **Describe the figure and the evolution narrative.** "Figure 5 directly compares Kafka in KRaft mode (embedded Raft) against Kafka in ZooKeeper mode (external ZAB). This is the evolution narrative in microcosm: replacing one consensus protocol (ZAB) with another (Raft) and measuring the difference."

2. **Expected finding.** "KRaft outperforms ZK mode across all scenarios. The difference is statistically significant at baseline (p = 0.00X). The performance gap has two sources: (a) ZooKeeper is a separate process requiring separate network hops for metadata writes, while KRaft uses an in-process Raft log, and (b) ZooKeeper's ZAB protocol is optimized for small metadata updates, not Kafka's high-throughput metadata patterns."

3. **Under faults.** "The gap narrows under packet_loss and network_partition because both modes share Kafka's data replication protocol (the bottleneck shifts from metadata consensus to data replication)."

### 6.6 Scalability (fig06) (2–3 pages)

**Data source:** `analysis/out/summary.csv` (rows where cluster_size=5 or 7). `analysis/out/plots/fig06_scalability.png`.

**What to write:**

1. **Describe the figure.** "Figure 6 shows mean baseline throughput as a function of cluster size (3, 5, 7 nodes) for KRaft and etcd. Both curves descend as cluster size increases, consistent with Raft theory: every write requires acknowledgment from a majority, and a larger cluster means more per-commit coordination."

2. **Sub-linear decay.** "Both curves are sub-linear — the throughput reduction from 3 to 5 nodes is smaller than from 5 to 7, and neither drop is proportional to cluster size. This confirms that Raft's leader broadcasts to followers in parallel, amortizing the cost of additional replicas."

3. **KRaft descends more gently than etcd.** "KRaft's relative reduction from 3 to 7 nodes is X% vs etcd's Y%. This reflects KRaft's record batching: a single Raft commit in KRaft carries hundreds of user records, whereas an etcd put is a single-record Raft commit. The per-record consensus cost is amortized across batches."

4. **Citation.** Reference the Raft paper (Ongaro & Ousterhout 2014) for the theoretical expectation; reference `docs/algorithms/scalability.md` for the detailed analysis methodology.

### 6.7 Pairwise statistical tests (2 pages)

**Data source:** `analysis/out/pairwise_tests.csv`.

**What to write:**

1. **Full table.** Include the pairwise_tests.csv as a table (or select the most informative rows). Columns: system A, system B, n_A, n_B, throughput_mean_A, throughput_mean_B, effect_pct, vargha_delaney_a, p_value, reliable.

2. **How to interpret Vargha-Delaney A.** "Vargha-Delaney A measures the probability that a random value from system A exceeds a random value from system B. A = 0.5 means no difference; A > 0.56 is a small effect; A > 0.64 is a medium effect; A > 0.71 is a large effect. This effect size measure is independent of sample size, unlike p-values, making it more informative for small-sample studies like this thesis."

3. **Which pairs matter.** Focus on:
   - KRaft vs ZK (evolution narrative — replacing consensus protocols).
   - etcd vs Paxi-Paxos (production implementation vs research framework).
   - CometBFT vs HotStuff (competing BFT protocols).
   - KRaft vs etcd (two Raft implementations — what differs?).

4. **Zero-variance pairs.** "Pairs involving HotStuff latency metrics are marked `reliable=False` because HotStuff's latency data has zero variance (all percentile values are the mean). These pairs should not be used to support or refute any hypothesis."

### 6.8 Correctness results (1 page)

1. **Baseline correctness.** All CFT systems pass ≥95% durability verification. HotStuff reports NO_DURABILITY_API (no read API on the asonnino client). Paxos/EPaxos correctness is spot-sampled (18 keys only — Paxi has no scan API).

2. **Fault scenario correctness.** Correctness is not checked for fault scenarios (data loss is expected and protocol-correct during leader kill, partition, double kill). The purpose of fault scenarios is performance characterization, not correctness verification — correctness under faults would require a separate linearizability-checking testbed beyond this thesis's scope.

### 6.9 Discussion (2–3 pages)

1. **The evolution narrative supported by data.**
   - Paxos established the template → Raft made it accessible → EPaxos questioned the leader assumption → Tendermint extended to BFT → HotStuff made BFT fast → KRaft replaced ZAB for engineering reasons.
   - Each step solved a measurable problem: Raft improved implementability; EPaxos improved WAN latency (not measured here); HotStuff improved BFT scalability and latency; KRaft eliminated ZK operational overhead (measured in fig05).

2. **What the numbers mean, not just what they are.** "A 100× throughput gap between CFT and BFT is not an argument against BFT. It is the cost of a fundamentally stronger guarantee. A bank choosing between etcd and CometBFT is choosing between a threat model (crashes only, trusted operators) and the associated performance. The thesis does not declare a 'winner' — it quantifies the trade-off."

3. **Limitations that affect interpretation.**
   - Single-region deployment. All protocols measured on the same LAN. WAN performance characteristics (where EPaxos, Paxos-family protocols excel) are not captured.
   - 4 BFT validators. HotStuff's linear communication advantage over Tendermint is theoretical at N=4; it would manifest at N=100+. Our measurements capture the entry point of the curve, not the full characteristic.
   - HotStuff latency percentiles unavailable. The thesis's latency comparison for BFT is incomplete.
   - No Byzantine fault injection. We test BFT liveness (can it survive crashes?) but not safety (does it detect equivocation?).

4. **Connection to broader literature.** Reference Sondhi 2021 for chaos-engineering methodology, Berger et al. 2023 for simulation-based BFT evaluation as a complement to our empirical approach, and Alqahtani & Demirbas 2021 for the general bottleneck analysis that explains our throughput results.

---

## Chapter 7: Conclusions and Future Work

### 7.1 Summary of contributions (1 page)

1. Provided a comprehensive theoretical analysis of 7 consensus protocols spanning 1989–2019, tracing the evolution from single-leader CFT to pipelined BFT.
2. Built a reproducible experimental framework supporting both Docker Compose (single-host development) and multi-VM cloud deployment.
3. Quantified the BFT-vs-CFT throughput gap (~100× on 3–4 node clusters) and the KRaft-vs-ZK performance improvement.
4. Documented all methodology limitations transparently, enabling informed interpretation of results.
5. Produced an open-source bundle: benchmark scripts, deployment automation, analysis pipeline with statistical tests, and an interactive web visualizer.

### 7.2 Decision framework: which protocol to use when (1 page)

| Deployment scenario | Recommended protocol | Rationale |
|---|---|---|
| Single-DC, CFT, high throughput | KRaft | Record batching amortizes consensus cost; embedded controller eliminates ZK overhead |
| Single-DC, CFT, low latency | etcd | Lightweight single-operation Raft commits; mature production implementation |
| Single-DC, CFT, simple operations | Raft (via etcd or Consul) | Most accessible specification; widest production ecosystem |
| Geo-distributed, CFT, low conflict | EPaxos | Leaderless design provides optimal WAN latency for non-conflicting commands |
| Untrusted participants, BFT, scale | HotStuff | Linear communication enables 100+ validator deployments |
| Untrusted participants, BFT, accountability | Tendermint | Cryptographic accountability for safety violations; simpler operations |

### 7.3 Limitations (0.5 page)

List the limitations from Section 6.9 concisely, referencing the auto-generated `methodology.md` for the complete register.

### 7.4 Future work (0.5 page)

1. **Byzantine fault injection.** Modify CometBFT or HotStuff validators to equivocate, sign invalid blocks, or send contradictory Prevotes/Precommits. Test whether the remaining validators detect and reject the misbehavior.
2. **BFT scalability.** Deploy HotStuff and Tendermint at 7, 13, 31, 100 validators on AWS, measuring the point where HotStuff's linear O(n) communication diverges from Tendermint's quadratic O(n²).
3. **WAN deployment.** Deploy across AWS regions (eu-west, us-east, ap-southeast) to evaluate EPaxos's WAN latency claims and Paxos-family geo-distribution characteristics.
4. **Linearizability checking.** Integrate Porcupine (the state-of-the-art linearizability checker) and record full operation histories to verify protocol correctness under concurrent faults.
5. **Instrument HotStuff client.** Modify the asonnino/hotstuff client to record per-transaction latency, enabling true percentile reporting and closing the thesis's primary methodology limitation.

---

## Appendices

### Appendix A: Protocol message-flow diagrams
- Visual diagrams of each protocol's commit path.
- Source: `visualizer/backend/src/main/resources/algorithms/*.json` — the animation specs define nodes, edges, and step-by-step message flows. These can be rendered as static diagrams for the thesis.

### Appendix B: Full experimental results
- `summary.csv` in full.
- `pairwise_tests.csv` in full.
- `leader_election.csv` in full.

### Appendix C: Deployment commands
- The exact Hetzner CLI commands, cloud-init.yaml, and deployment scripts.
- Reference `STRATEGY.md` for the numbered step-by-step guide.

### Appendix D: Methodology limitations register
- Auto-generated by `analyse.py` → `analysis/out/methodology.md`.
- Every known limitation with its handling in the pipeline.

### Appendix E: Visualizer and Grafana screenshots
- Results tab: system+scenario drill-down with aggregated stats.
- Compare tab: cross-system side-by-side for baseline throughput.
- Algorithms tab: animated network view for each protocol.
- Grafana dashboard: Prometheus metrics during a representative run.

---

## Bibliography

### Corpus papers (in `Consensus Thesis Corpus/`)

| File | Paper | Cite in section |
|---|---|---|
| `279227.279229.pdf` | Lamport 1998, "The Part-Time Parliament", ACM TOCS | 3.1 (Paxos) |
| `atc14-paper-ongaro.pdf` | Ongaro & Ousterhout 2014, "In Search of an Understandable Consensus Algorithm", USENIX ATC | 3.2 (Raft) |
| `2723872.2723876.pdf` | Howard et al. 2015, "Raft Refloated: Do We Have Consensus?", SIGOPS OSR | 3.2 (Raft critique) |
| `2517349.2517350.pdf` | Moraru et al. 2013, "There Is More Consensus in Egalitarian Parliaments", SOSP | 3.4 (EPaxos) |
| `Buchman_Ethan_201606_Msater_thesis.pdf` | Buchman 2016, "Tendermint: Byzantine Fault Tolerance in the Age of Blockchains", M.Sc. Thesis | 3.5 (Tendermint) |
| `Accountable_Tendermint___DSN_2022.pdf` | Buchman et al. 2022, "Revisiting Tendermint", DSN | 3.5 (Tendermint accountability) |
| `Pedone_2021_IEEE_SRDS.pdf` | Cason et al. 2021, "The Design, Architecture and Performance of the Tendermint Blockchain Network", SRDS | 3.5 (Tendermint eval) |
| `HotStuff_BFT_Consensus_with_Linearity_and_Responsi.pdf` | Yin et al. 2019, "HotStuff: BFT Consensus with Linearity and Responsiveness", PODC | 3.6 (HotStuff) |
| `1803.05069v6.pdf` | Yin et al. 2019, "HotStuff: BFT Consensus in the Lens of Blockchain" (arXiv full version) | 3.6 (HotStuff framework) |
| `2010.11454v10.pdf` | Jalalzai et al. 2023, "Fast-HotStuff", IEEE TDSC | 3.6 (HotStuff variants) |
| `3299869.3319893.pdf` | Ailijiang et al. 2019, "Dissecting the Performance of Strongly-Consistent Replication Protocols", SIGMOD | 3.1 (Paxi framework), 5.3 (benchmark methodology) |
| `2103.04234v4.pdf` | Alqahtani & Demirbas 2021, "Bottlenecks in Blockchain Consensus Protocols", COINS | 4.1 (fault model cost), 6.1 (BFT bottleneck) |
| `2309.17245v1.pdf` | Berger et al. 2023, "Scalable Performance Evaluation of BFT Systems Using Network Simulation", PRDC | 5.3 (simulation methodology), 7.4 (future work) |
| `1704.03319v2.pdf` | Arun et al. 2017, "Speeding up Consensus by Chasing Fast Decisions" (CAESAR), DSN | 3.4 (EPaxos comparison) |
| `Sondhi_uwindsor_0115O_12677.pdf` | Sondhi 2021, "Empirical Performance Evaluation of Consensus Algorithms in Permissioned Blockchain Platforms", M.Sc. Thesis | 5.3 (benchmark methodology) |
| `2023-397.pdf` | **VERIFY FROM PDF** — tentatively Civit et al. 2023, "On the Validity of Consensus", PODC | 3.5–3.6 (BFT validity conditions) |

### Supplementary entries (in `new-entries.bib` and `Thesis.bib`)

| Entry | Paper | Cite in section |
|---|---|---|
| `fischer_impossibility_1985` | Fischer, Lynch, Paterson 1985, JACM | 1.2 (FLP impossibility) |
| `lamport_part-time_1998` | Lamport 1998, ACM TOCS | 3.1 (Paxos) |
| `lamport_paxos_2001` | Lamport 2001, SIGACT News | 3.1 (Paxos Made Simple) |
| `howard_paxos_2020` | Howard & Mortier 2020, PaPoC | 3.2 (Paxos vs Raft comparison) |
| `tollman_epaxos_2021` | Tollman et al. 2021, NSDI | 3.4 (EPaxos Revisited) |
| `charapko_scalable_2021` | Charapko et al. 2021, HotCloud | 5.3 (consensus overhead in cloud) |
| `buchman_latest_2018` | Buchman et al. 2018, arXiv | 3.5 (Tendermint protocol) |
| `zhang_reaching_2024` | Zhang et al. 2024, ACM Computing Surveys | 4.1 (BFT survey) |
| `ieee_performance_2022` | IEEE CSDE 2022 | 5.3 (benchmark methodology) |
| `amiri_bedrock_2024` | Amiri et al. 2024, NSDI | 5.3 (BFT benchmarking platform) |
| `yin_hotstuff_2019` | Yin et al. 2019, arXiv (Thesis.bib) | 3.6 (HotStuff) |
| `jalalzai_fast-hotstuff_2024` | Jalalzai et al. 2024, IEEE TDSC (Thesis.bib) | 3.6 (Fast-HotStuff) |
| `alqahtani_bottlenecks_2021` | Alqahtani & Demirbas 2021 (Thesis.bib) | 4.1, 6.1 |

### Foundational papers (NOT in bundle — must cite from external sources)

| Paper | Venue | Section |
|---|---|---|
| Lamport 1978, "Time, Clocks, and the Ordering of Events in a Distributed System" | CACM | 1.1 |
| Lamport, Shostak, Pease 1982, "The Byzantine Generals Problem" | TOPLAS | 1.2 |
| Dwork, Lynch, Stockmeyer 1988, "Consensus in the Presence of Partial Synchrony" | JACM | 1.2 |
| Schneider 1990, "Implementing Fault-Tolerant Services Using the State Machine Approach" | ACM Computing Surveys | 1.2, 2.2 |
| Chandra & Toueg 1996, "Unreliable Failure Detectors for Reliable Distributed Systems" | JACM | 1.2 |
| Castro & Liskov 1999, "Practical Byzantine Fault Tolerance" | OSDI | 3.5 context |
| Brewer 2000, "Toward Robust Distributed Systems" (CAP Theorem) | PODC Keynote | 1.3 |
| Gilbert & Lynch 2002, "Brewer's Conjecture and the Feasibility of CAP" | SIGACT News | 1.3 |
