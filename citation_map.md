# Citation Map: "Evolution and Comparison of Consensus Protocols in Distributed Systems"

---

## CORPUS PAPERS

---

### 1. Fast-HotStuff: A Fast and Resilient HotStuff Protocol
**File:** `2010.11454v10.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | Fast-HotStuff: A Fast and Resilient HotStuff Protocol |
| **Authors** | Mohammad M. Jalalzai, Jianyu Niu, Chen Feng, Fangyu Gai |
| **Venue** | IEEE Transactions on Dependable and Secure Computing (TDSC), Early Access |
| **Year** | 2023 (published online 25 Aug 2023; arXiv v1 Oct 2020) |
| **Key Claims** | (1) HotStuff's third communication round is not strictly necessary — a two-round protocol can achieve equivalent responsiveness and linear view change. (2) Fast-HotStuff reduces latency by eliminating the commit round while preserving safety. (3) Fast-HotStuff is more robust against performance attacks (e.g., leader equivocation) that three-round HotStuff is susceptible to. |
| **Thesis Chapter** | Ch. on BFT protocol optimization / post-HotStuff variants |
| **Thesis Section** | Section on reducing communication rounds in BFT protocols |
| **Claim Supported** | "While HotStuff achieves responsiveness and linear view change, it requires three communication rounds — raising the question of whether this overhead can be eliminated without sacrificing liveness or safety." |
| **Key Quote** | *"Fast-HotStuff has lower latency and is more robust against performance attacks that HotStuff is susceptible to."* |

---

### 2. Bottlenecks in Blockchain Consensus Protocols
**File:** `2103.04234v4.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | Bottlenecks in Blockchain Consensus Protocols |
| **Authors** | Salem Alqahtani, Murat Demirbas |
| **Venue** | IEEE International Conference on Omni-layer Intelligent Systems (COINS) |
| **Year** | 2021 |
| **Key Claims** | (1) Communication complexity is the primary bottleneck — even with sufficient bandwidth, CPU cost of message serialization/deserialization limits throughput. (2) PBFT, Tendermint, HotStuff, and Streamlet all fail to scale with increasing validators under identical conditions. (3) Rotating communication hotspots, pipelining, and reducing communication complexity are the most effective mitigations. |
| **Thesis Chapter** | Ch. on performance comparison / scalability analysis |
| **Thesis Section** | Section on bottleneck analysis or throughput/latency scaling |
| **Claim Supported** | "As the number of validators increases, consensus protocol throughput degrades not solely due to network bandwidth but because of CPU-bound serialization costs." |
| **Key Quote** | *"Even when there is enough network bandwidth, the CPU cost of serialization and deserialization of the messages limits the throughput and increases the latency of the protocols."* |

---

### 3. Scalable Performance Evaluation of Byzantine Fault-Tolerant Systems Using Network Simulation
**File:** `2309.17245v1.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | Scalable Performance Evaluation of Byzantine Fault-Tolerant Systems Using Network Simulation |
| **Authors** | Christian Berger, Sadok Ben Toumia, Hans P. Reiser |
| **Venue** | IEEE 28th Pacific Rim International Symposium on Dependable Computing (PRDC) |
| **Year** | 2023 |
| **Key Claims** | (1) Network simulation can accurately forecast BFT protocol performance at scale without requiring real large-scale cloud deployments. (2) At larger system scales, the network becomes the dominating factor, making simulation results converge with real-world measurements. (3) The method works with existing BFT implementations without code modification ("plug-and-play"). |
| **Thesis Chapter** | Ch. on evaluation methodology / experimental framework |
| **Thesis Section** | Section on simulation-based evaluation or benchmarking approaches |
| **Claim Supported** | "Evaluating BFT protocols at scale is prohibitively expensive in real cloud deployments; network simulation offers a validated, cost-effective alternative." |
| **Key Quote** | *"Our solution uses network simulations to forecast the performance of BFT protocols while experimentally scaling the environment. Our method seamlessly plug-and-plays existing BFT implementations into the simulation without requiring code modification."* |

---

### 4. EPaxos (There is more consensus in Egalitarian parliaments)
**File:** `2517349.2517350.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | There Is More Consensus in Egalitarian Parliaments |
| **Authors** | Iulian Moraru, David G. Andersen, Michael Kaminsky |
| **Venue** | ACM Symposium on Operating Systems Principles (SOSP) |
| **Year** | 2013 |
| **Key Claims** | (1) EPaxos achieves optimal commit latency (one round-trip for non-conflicting commands) without a designated leader, enabling any replica to propose. (2) EPaxos provides "generalized consensus" by tracking command dependencies rather than ordering all commands in a total sequence. (3) EPaxos throughput scales nearly linearly with the number of replicas in wide-area deployments. |
| **Thesis Chapter** | Ch. on consensus in WAN/geo-replicated systems |
| **Thesis Section** | Section on leaderless/multi-leader consensus or generalized consensus |
| **Claim Supported** | "Leaderless consensus protocols such as EPaxos eliminate the single-leader bottleneck by allowing any replica to propose commands while maintaining optimal commit latency." |
| **Key Quote** | *"EPaxos provides optimal commit latency in the wide-area for non-conflicting commands, handles conflicts efficiently, and its throughput scales almost linearly with the number of replicas."* |

---

### 5. Raft Refloated: Do We Have Consensus?
**File:** `2723872.2723876.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | Raft Refloated: Do We Have Consensus? |
| **Authors** | Heidi Howard, Malte Schwarzkopf, Anil Madhavapeddy, Jon Crowcroft |
| **Venue** | ACM SIGOPS Operating Systems Review (OPSR) |
| **Year** | 2015 |
| **Key Claims** | (1) The original Raft protocol has subtle safety/liveness issues not widely acknowledged. (2) Raft's "understandability" claims require closer scrutiny; the protocol is simpler than Paxos but still has non-trivial corner cases. (3) Leader election and log replication interact in ways that can lead to availability degradation under certain failure patterns. |
| **Thesis Chapter** | Ch. on crash fault-tolerant (CFT) protocols / Raft |
| **Thesis Section** | Section on Raft analysis or CFT protocol trade-offs |
| **Claim Supported** | "Although Raft was designed for understandability, its leader election mechanism and log replication interaction introduce subtle failure modes not apparent in the original specification." |
| **Key Quote** | *The paper critically examines Raft's design choices and identifies scenarios where Raft's claimed simplicity masks operational corner cases.* |

---

### 6. Paxos (The Part-Time Parliament)
**File:** `279227.279229.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | The Part-Time Parliament |
| **Authors** | Leslie Lamport |
| **Venue** | ACM Transactions on Computer Systems (TOCS) |
| **Year** | 1998 (published May 1998; originally submitted 1990) |
| **Key Claims** | (1) Introduces the Paxos consensus algorithm, providing a formal proof that consensus can be achieved despite asynchrony, crash failures, and message loss. (2) Defines the state machine replication approach and shows how Paxos implements it. (3) Establishes that a majority quorum (2f+1 replicas) is sufficient for tolerating f crash failures. |
| **Thesis Chapter** | Ch. on classical consensus / foundations |
| **Thesis Section** | Section on the origins of consensus or crash fault-tolerant consensus |
| **Claim Supported** | "The Paxos algorithm, introduced by Lamport, remains the canonical consensus protocol for crash fault-tolerant systems and underpins most modern distributed coordination services." |
| **Key Quote** | *"The Paxon parliament's protocol provides a new way of implementing the state machine approach to the design of distributed systems."* |

---

### 7. Paxi / Dissecting the Performance of Strongly-Consistent Replication Protocols
**File:** `3299869.3319893.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | Dissecting the Performance of Strongly-Consistent Replication Protocols |
| **Authors** | Ailidani Ailijiang, Aleksey Charapko, Murat Demirbas |
| **Venue** | ACM SIGMOD International Conference on Management of Data |
| **Year** | 2019 |
| **Key Claims** | (1) Presents the Paxi framework for prototyping and evaluating single-leader, multi-leader, hierarchical, and leaderless consensus protocols under a unified benchmarking setup. (2) Derives throughput and latency formulas parameterized by network RTT, number of replicas, and batching. (3) Single-leader protocols offer highest throughput under low-contention LAN workloads; multi-leader and leaderless protocols excel in WAN with higher replication factors. |
| **Thesis Chapter** | Ch. on consensus protocol comparison / taxonomy |
| **Thesis Section** | Section on analytical modeling or empirical comparison of protocol families |
| **Claim Supported** | "The Paxi framework provides a systematic comparison of consensus protocol families, demonstrating that no single protocol design is universally optimal — the best choice depends on network topology, workload contention, and replication factor." |
| **Key Quote** | *"These formulas enable the developers to decide which category of protocols would be most suitable under given deployment conditions."* |

---

### 8. CAESAR (Speeding up Consensus by Chasing Fast Decisions)
**File:** `1704.03319v2.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | Speeding up Consensus by Chasing Fast Decisions |
| **Authors** | Balaji Arun, Sebastiano Peluso, Roberto Palmieri, Giuliano Losa, Binoy Ravindran |
| **Venue** | IEEE/IFIP 47th International Conference on Dependable Systems and Networks (DSN) |
| **Year** | 2017 |
| **Key Claims** | (1) CAESAR is a multi-leader generalized consensus protocol that tolerates conflicting dependencies without rejecting fast-path proposals, unlike EPaxos. (2) Outperforms EPaxos by up to 1.7x under 30% conflicting requests and Multi-Paxos by up to 3.5x. (3) Achieves this by changing how fast decisions are taken: a quorum with different dependency sets can still commit a fast decision. |
| **Thesis Chapter** | Ch. on multi-leader / generalized consensus |
| **Thesis Section** | Section on conflict handling in generalized consensus |
| **Claim Supported** | "While EPaxos forces slow-path resolution when dependency sets differ, CAESAR can commit fast decisions even with conflicting dependency information, significantly improving throughput under contention." |
| **Key Quote** | *"Caesar outperforms other multi-leader competitors by as much as 1.7x in the presence of 30% conflicting requests, and single-leader by up to 3.5x."* |

---

### 9. HotStuff (arXiv full version)
**File:** `1803.05069v6.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | HotStuff: BFT Consensus in the Lens of Blockchain |
| **Authors** | Maofan Yin, Dahlia Malkhi, Michael K. Reiter, Guy Golan Gueta, Ittai Abraham |
| **Venue** | arXiv preprint; shorter version published at ACM PODC |
| **Year** | 2019 (PODC); arXiv v1 March 2018, v6 July 2019 |
| **Key Claims** | (1) First partially synchronous BFT protocol combining responsiveness (leader drives consensus at actual network speed) with linear communication complexity. (2) Introduces a novel pipelining framework that expresses DLS, PBFT, Tendermint, and Casper in a unified framework. (3) Demonstrates throughput and latency comparable to BFT-SMaRt at 100+ replicas while using linear (vs. quadratic) communication during leader failover. |
| **Thesis Chapter** | Ch. on BFT protocols / blockchain consensus |
| **Thesis Section** | Section on pipelined BFT or the transition from PBFT to modern BFT |
| **Claim Supported** | "HotStuff marked a turning point in BFT design by achieving both responsiveness and linear communication complexity in the partially synchronous model, enabling deployment at scale in blockchain systems such as Facebook's Libra/Diem." |
| **Key Quote** | *"HotStuff is the first partially synchronous BFT replication protocol exhibiting... responsiveness and communication complexity that is linear in the number of replicas."* |

---

### 10. HotStuff (PODC 2019 conference version)
**File:** `HotStuff_BFT_Consensus_with_Linearity_and_Responsi.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | HotStuff: BFT Consensus with Linearity and Responsiveness |
| **Authors** | Maofan Yin, Dahlia Malkhi, Michael K. Reiter, Guy Golan Gueta, Ittai Abraham |
| **Venue** | ACM Symposium on Principles of Distributed Computing (PODC) |
| **Year** | 2019 |
| **Key Claims** | Same as above but without the protocol-interpretation framework, system evaluation, and extended proofs found in the arXiv version. Focuses on the core algorithmic contributions and the linearity/responsiveness properties. |
| **Thesis Chapter** | Ch. on BFT protocols / blockchain consensus |
| **Thesis Section** | Section on core HotStuff algorithm description and safety/liveness proof outline |
| **Claim Supported** | "The PODC 2019 version of HotStuff establishes the theoretical foundations — linear communication complexity during view changes combined with responsiveness — that distinguish it from prior BFT protocols." |
| **Key Quote** | Cite the PODC version for the core algorithm; cite the arXiv version for the full evaluation, framework, and protocol interpretations. |

---

### 11. Accountable Tendermint / Revisiting Tendermint
**File:** `Accountable_Tendermint___DSN_2022.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | Revisiting Tendermint: Design Tradeoffs, Accountability, and Practical Use |
| **Authors** | Ethan Buchman, Rachid Guerraoui, Jovan Komatovic, Zarko Milosevic, Dragos-Adrian Seredinschi, Josef Widder |
| **Venue** | IEEE/IFIP 52nd Annual Conference on Dependable Systems and Networks (DSN) |
| **Year** | 2022 |
| **Key Claims** | (1) Formalizes the correctness conditions of Tendermint consensus and identifies subtle aspects of its design. (2) Analyzes Tendermint's accountability support — the protocol can identify Byzantine replicas that caused a safety violation. (3) Clarifies the practical tradeoffs between performance, accountability, and the specific quorum/communication patterns adopted by Tendermint. |
| **Thesis Chapter** | Ch. on BFT protocols / accountability in consensus |
| **Thesis Section** | Section on forensic analysis / accountability in BFT protocols |
| **Claim Supported** | "Tendermint's design provides not only safety and liveness guarantees but also accountability: when safety is violated, misbehaving replicas can be identified, which is critical for practical blockchain deployments." |
| **Key Quote** | *The paper formalizes Tendermint's accountability property and clarifies the design tradeoffs that distinguish it from PBFT and HotStuff.* |

---

### 12. Raft
**File:** `atc14-paper-ongaro.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | In Search of an Understandable Consensus Algorithm |
| **Authors** | Diego Ongaro, John Ousterhout |
| **Venue** | USENIX Annual Technical Conference (ATC) |
| **Year** | 2014 |
| **Key Claims** | (1) Raft was explicitly designed for understandability, decomposing consensus into leader election, log replication, and safety. (2) Raft guarantees safety (never returns incorrect results) and availability (available as long as any majority of servers is operational). (3) Provides stronger leader guarantees than Paxos (log entries only flow from leader to followers), simplifying reasoning. |
| **Thesis Chapter** | Ch. on crash fault-tolerant (CFT) consensus protocols |
| **Thesis Section** | Section on leader-based consensus or Raft vs. Paxos comparison |
| **Claim Supported** | "Raft reimagines consensus by prioritizing understandability: it decomposes the problem into independent subproblems (leader election, log replication, safety) and enforces stronger invariants than Paxos." |
| **Key Quote** | *"Raft separates the key elements of consensus, such as leader election, log replication, and safety, and it enforces a stronger degree of coherency to reduce the number of states that must be considered."* |

---

### 13. Tendermint Thesis (Ethan Buchman)
**File:** `Buchman_Ethan_201606_Msater_thesis.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | Tendermint: Byzantine Fault Tolerance in the Age of Blockchains |
| **Authors** | Ethan Buchman |
| **Venue** | M.Sc. Thesis, University of Guelph |
| **Year** | 2016 |
| **Key Claims** | (1) Describes the original design and implementation of the Tendermint consensus engine, a PBFT-derived BFT protocol for blockchain applications. (2) Introduces the Application Blockchain Interface (ABCI) decoupling consensus from the state machine. (3) Provides the first rigorous treatment of Tendermint's locking mechanism and proof-of-stake integration. |
| **Thesis Chapter** | Ch. on BFT protocols / Tendermint |
| **Thesis Section** | Section on the origins of Tendermint or BFT consensus in blockchain |
| **Claim Supported** | "Tendermint, introduced in Buchman's 2016 thesis, pioneered the separation of BFT consensus from application logic via ABCI, enabling the Cosmos ecosystem and fundamentally shaping blockchain consensus engineering." |
| **Key Quote** | *The thesis provides the foundational description of Tendermint's consensus protocol, locking rules, and proof-of-stake integration.* |

---

### 14. Tendermint Blockchain Network (Pedone et al.)
**File:** `Pedone_2021_IEEE_SRDS.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | The Design, Architecture and Performance of the Tendermint Blockchain Network |
| **Authors** | Daniel Cason, Enrique Fynn, Nenad Milosevic, Zarko Milosevic, Ethan Buchman, Fernando Pedone |
| **Venue** | IEEE 40th International Symposium on Reliable Distributed Systems (SRDS) |
| **Year** | 2021 |
| **Key Claims** | (1) Provides the most comprehensive architectural description and empirical performance evaluation of a complete Tendermint deployment. (2) Analyzes how the consensus protocol, peer-to-peer gossip layer, and mempool interact to determine end-to-end throughput and latency. (3) Identifies bottlenecks in the broadcast mechanism and proposal creation that limit Tendermint's throughput under high load. |
| **Thesis Chapter** | Ch. on BFT protocol performance / Tendermint evaluation |
| **Thesis Section** | Section on system-level evaluation or Tendermint performance |
| **Claim Supported** | "A complete Tendermint deployment involves complex interactions between the consensus core, peer-to-peer gossip layer, and mempool — the consensus protocol alone does not determine end-to-end performance." |
| **Key Quote** | *"To the best of our knowledge, this is the most comprehensive performance evaluation of a Tendermint blockchain network to date."* |

---

### 15. Sondhi Thesis
**File:** `Sondhi_uwindsor_0115O_12677.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | Empirical Performance Evaluation of Consensus Algorithms in Permissioned Blockchain Platforms |
| **Authors** | Shivam Sondhi |
| **Venue** | M.Sc. Thesis, University of Windsor |
| **Year** | 2021 |
| **Key Claims** | (1) Proposes a methodology to empirically compare consensus protocols (PBFT, Raft, Kafka-based) across permissioned blockchain platforms (Hyperledger Fabric, etc.). (2) Chaos engineering techniques reveal that consensus performance is highly sensitive to network conditions and node failures. (3) Systematic benchmarking shows that no consensus algorithm dominates across all metrics; the choice depends on the deployment scenario. |
| **Thesis Chapter** | Ch. on empirical evaluation / benchmarking |
| **Thesis Section** | Section on cross-platform comparison or experimental methodology |
| **Claim Supported** | "Permissioned blockchain platforms employ fundamentally different consensus mechanisms, and empirical evaluations using chaos engineering reveal that network conditions — not just the protocol design — dominate end-to-end performance." |
| **Key Quote** | *The thesis applies chaos engineering to understand consensus algorithm performance in permissioned blockchains.* |

---

### 16. 2023-397 (Likely: "On the Validity of Consensus" or related Civit/Guerraoui paper)
**File:** `2023-397.pdf`

| Field | Detail |
|-------|--------|
| **Full Title** | ***TENTATIVE — VERIFY FROM PDF:*** On the Validity of Consensus |
| **Authors** | Pierre Civit, Seth Gilbert, Rachid Guerraoui, Jovan Komatovic, Manuel Vidigueira |
| **Venue** | ACM Symposium on Principles of Distributed Computing (PODC) |
| **Year** | 2023 |
| **Key Claims** | (1) Formally studies validity conditions in Byzantine consensus, distinguishing between different notions of validity (strong validity, external validity, etc.). (2) Shows that classical BFT protocols implicitly rely on varying validity definitions that affect their security guarantees in practice. (3) Establishes impossibility results and protocols for achieving stronger validity guarantees under different synchrony assumptions. |
| **Thesis Chapter** | Ch. on BFT fundamentals / consensus correctness conditions |
| **Thesis Section** | Section on validity and correctness conditions in consensus |
| **Claim Supported** | "The validity condition in Byzantine consensus — what constitutes a 'legitimate' decision value — varies across protocols and has profound implications for practical safety guarantees." |
| **Key Quote** | *Verify from PDF for the exact key result.* |

> **⚠️ NOTE:** Filename "2023-397" could not be definitively identified via web search. The above is a best-guess match based on author overlap with the Civit/Guerraoui group. Please verify against the actual PDF. It could alternatively be "All Byzantine Agreement Problems Are Expensive" (PODC 2024) or another paper.

---

---

## FOUNDATIONAL PAPERS (NOT IN CORPUS — MUST BE CITED)

---

### F1. FLP Impossibility
| Field | Detail |
|-------|--------|
| **Full Title** | Impossibility of Distributed Consensus with One Faulty Process |
| **Authors** | Michael J. Fischer, Nancy A. Lynch, Michael S. Paterson |
| **Venue** | Journal of the ACM (JACM), Vol. 32, No. 2, pp. 374–382 |
| **Year** | 1985 |
| **Key Result** | In a purely asynchronous system, no deterministic consensus protocol can guarantee termination even with a single crash failure. This establishes a fundamental trade-off between safety and liveness. |
| **Thesis Chapter** | Ch. on foundational theory / impossibility results |
| **Thesis Section** | Section on synchrony assumptions or the FLP result |
| **Claim Supported** | "The FLP impossibility result defines the fundamental boundary of what is achievable in asynchronous consensus: any protocol must either sacrifice liveness, rely on randomization, or assume some degree of synchrony." |
| **Key Quote** | *"No completely asynchronous consensus protocol can tolerate even a single unannounced process death."* |

---

### F2. Lamport Clocks
| Field | Detail |
|-------|--------|
| **Full Title** | Time, Clocks, and the Ordering of Events in a Distributed System |
| **Authors** | Leslie Lamport |
| **Venue** | Communications of the ACM (CACM), Vol. 21, No. 7, pp. 558–565 |
| **Year** | 1978 |
| **Key Result** | Introduces the *happens-before* partial ordering, logical clocks, and the concept that causality — rather than physical time — is the fundamental ordering primitive for distributed systems. |
| **Thesis Chapter** | Ch. on foundations / ordered communication |
| **Thesis Section** | Section on causality and ordering in consensus |
| **Claim Supported** | "Consensus protocols ultimately depend on establishing a total order of events, a problem Lamport framed via the happens-before relation and logical clocks." |
| **Key Quote** | *"The concept of 'happening before' defines an invariant partial ordering of the events in a distributed multiprocess system."* |

---

### F3. Byzantine Generals Problem
| Field | Detail |
|-------|--------|
| **Full Title** | The Byzantine Generals Problem |
| **Authors** | Leslie Lamport, Robert Shostak, Marshall Pease |
| **Venue** | ACM Transactions on Programming Languages and Systems (TOPLAS), Vol. 4, No. 3, pp. 382–401 |
| **Year** | 1982 |
| **Key Result** | Formalizes the Byzantine fault model and proves that Byzantine agreement requires n > 3f replicas in synchronous systems with oral messages. Introduces the notion of interactive consistency. |
| **Thesis Chapter** | Ch. on BFT foundations |
| **Thesis Section** | Section on the Byzantine fault model and its origins |
| **Claim Supported** | "The Byzantine fault model, in which faulty processes may behave arbitrarily — including maliciously — was formalized by Lamport, Shostak, and Pease, establishing the n > 3f bound for synchronous agreement with oral messages." |
| **Key Quote** | *"The reliability required for a computer system to correctly withstand arbitrary malfunctioning of its components."* |

---

### F4. DLS Partial Synchrony
| Field | Detail |
|-------|--------|
| **Full Title** | Consensus in the Presence of Partial Synchrony |
| **Authors** | Cynthia Dwork, Nancy Lynch, Larry Stockmeyer |
| **Venue** | Journal of the ACM (JACM), Vol. 35, No. 2, pp. 288–323 |
| **Year** | 1988 |
| **Key Result** | Introduces the *partially synchronous* model — the standard model for practical BFT protocols (PBFT, HotStuff, Tendermint, etc.). Proves that consensus is solvable with f < n/2 crash faults or f < n/3 Byzantine faults once the system becomes synchronous. |
| **Thesis Chapter** | Ch. on system models and synchrony |
| **Thesis Section** | Section on the partially synchronous model |
| **Claim Supported** | "Most practical BFT consensus protocols — PBFT, Tendermint, HotStuff — operate in the partially synchronous model introduced by Dwork, Lynch, and Stockmeyer, which bridges the gap between pure asynchrony (where FLP applies) and full synchrony." |
| **Key Quote** | *The paper defines the GST (Global Stabilization Time) model that underlies nearly all practical consensus protocols.* |

---

### F5. PBFT
| Field | Detail |
|-------|--------|
| **Full Title** | Practical Byzantine Fault Tolerance |
| **Authors** | Miguel Castro, Barbara Liskov |
| **Venue** | 3rd USENIX Symposium on Operating Systems Design and Implementation (OSDI) |
| **Year** | 1999 |
| **Key Result** | First BFT protocol practical enough for real-world use: operates in the partially synchronous model, tolerates f < n/3 Byzantine faults, uses a three-phase commit (pre-prepare, prepare, commit), and achieves sub-millisecond latency in LAN settings. |
| **Thesis Chapter** | Ch. on BFT foundations / PBFT |
| **Thesis Section** | Section on the first practical BFT consensus |
| **Claim Supported** | "Castro and Liskov's PBFT demonstrated that Byzantine fault tolerance is not merely a theoretical construct but can be implemented with practical performance, using a three-phase commit protocol and view-change mechanism." |
| **Key Quote** | *"This paper presents a new, practical algorithm for state machine replication that tolerates Byzantine faults."* |

---

### F6. CAP Theorem (Brewer)
| Field | Detail |
|-------|--------|
| **Full Title** | Toward Robust Distributed Systems (keynote) — CAP Theorem |
| **Authors** | Eric A. Brewer |
| **Venue** | ACM Symposium on Principles of Distributed Computing (PODC) — Keynote |
| **Year** | 2000 |
| **Key Result** | Conjectures that a distributed system can simultaneously provide at most two of: Consistency, Availability, and Partition tolerance. This trade-off fundamentally constrains the design space of distributed protocols. |
| **Thesis Chapter** | Ch. on foundations / design trade-offs |
| **Thesis Section** | Section on the CAP theorem and its implications for consensus |
| **Claim Supported** | "The CAP theorem frames the fundamental trade-off that consensus protocols must navigate: strong consistency comes at the cost of availability under network partitions." |
| **Key Quote** | Cite the PODC 2000 keynote speech. |

---

### F7. CAP Theorem Proof (Gilbert & Lynch)
| Field | Detail |
|-------|--------|
| **Full Title** | Brewer's Conjecture and the Feasibility of Consistent, Available, Partition-Tolerant Web Services |
| **Authors** | Seth Gilbert, Nancy Lynch |
| **Venue** | ACM SIGACT News, Vol. 33, No. 2, pp. 51–59 |
| **Year** | 2002 |
| **Key Result** | Provides a formal proof of Brewer's CAP conjecture, showing that in an asynchronous network model, consistency and availability cannot both be guaranteed under network partitions. |
| **Thesis Chapter** | Ch. on foundations / design trade-offs |
| **Thesis Section** | Section on the CAP theorem formalization |
| **Claim Supported** | "Gilbert and Lynch formalized Brewer's CAP conjecture, proving that an asynchronous distributed system cannot simultaneously guarantee consistency and availability under partitions." |
| **Key Quote** | *"In a model with asynchronous networks, it is impossible to implement a read/write data object that guarantees both availability and atomic consistency in all executions where messages may be lost."* |

---

### F8. State Machine Replication (Schneider)
| Field | Detail |
|-------|--------|
| **Full Title** | Implementing Fault-Tolerant Services Using the State Machine Approach: A Tutorial |
| **Authors** | Fred B. Schneider |
| **Venue** | ACM Computing Surveys, Vol. 22, No. 4, pp. 299–319 |
| **Year** | 1990 |
| **Key Result** | Formalizes the state machine replication (SMR) paradigm: any service can be made fault-tolerant by replicating its state across multiple servers and ensuring they execute the same sequence of deterministic commands. Consensus becomes the mechanism for agreeing on the command sequence. |
| **Thesis Chapter** | Ch. on foundations / state machine replication |
| **Thesis Section** | Section on the SMR paradigm and its relationship to consensus |
| **Claim Supported** | "All consensus protocols discussed in this thesis serve a common purpose: implementing state machine replication, as formalized by Schneider, where agreement on a total order of commands is the central primitive." |
| **Key Quote** | *"The state machine approach is a general method for implementing a fault-tolerant service by replicating servers and coordinating client interactions with server replicas."* |

---

### F9. Failure Detectors (Chandra & Toueg)
| Field | Detail |
|-------|--------|
| **Full Title** | Unreliable Failure Detectors for Reliable Distributed Systems |
| **Authors** | Tushar Deepak Chandra, Sam Toueg |
| **Venue** | Journal of the ACM (JACM), Vol. 43, No. 2, pp. 225–267 |
| **Year** | 1996 |
| **Key Result** | Introduces the concept of *unreliable failure detectors* and proves that consensus can be solved in asynchronous systems augmented with a failure detector of class ◇W (eventually weak). This result circumvents FLP by encapsulating synchrony assumptions in the failure detector abstraction. |
| **Thesis Chapter** | Ch. on foundations / asynchrony |
| **Thesis Section** | Section on circumventing FLP or failure detection |
| **Claim Supported** | "The FLP impossibility can be circumvented by augmenting the asynchronous model with an unreliable failure detector; Chandra and Toueg showed that the weakest class sufficient for consensus is ◇W." |
| **Key Quote** | *"We show that consensus is solvable using an eventually weak failure detector (◇W), and that any failure detector that can be used to solve consensus is at least as strong as ◇W."* |

---

---

## SUMMARY: PAPER → THESIS MAPPING

| Paper | Primary Thesis Chapter/Section | What to Cite |
|-------|-------------------------------|--------------|
| Lamport 1978 (Clocks) | Ch. Foundations — Causality & Ordering | Happens-before relation; logical clocks as ordering primitive |
| Lamport/Shostak/Pease 1982 (BGP) | Ch. BFT Foundations — Byzantine Model | n > 3f bound; origin of Byzantine fault model |
| FLP 1985 | Ch. Foundations — Impossibility Results | Deterministic async consensus impossibility |
| DLS 1988 | Ch. System Models — Partial Synchrony | GST model; consensus solvability in partial synchrony |
| Schneider 1990 (SMR) | Ch. Foundations — State Machine Replication | SMR paradigm; consensus as ordering primitive |
| Chandra & Toueg 1996 | Ch. Foundations — Asynchrony | ◇W failure detector; circumventing FLP |
| Lamport 1998 (Paxos) | Ch. CFT Consensus — Classical Protocols | Canonical crash-fault consensus; quorum-based SMR |
| Castro & Liskov 1999 (PBFT) | Ch. BFT Consensus — PBFT | First practical BFT; 3-phase commit; view change |
| Brewer 2000 (CAP) / Gilbert & Lynch 2002 | Ch. Foundations — Design Trade-offs | CAP theorem and its formal proof |
| Ongaro & Ousterhout 2014 (Raft) | Ch. CFT Consensus — Raft | Understandability-focused consensus; leader election + log replication |
| Moraru et al. 2013 (EPaxos) | Ch. Leaderless / Multi-Leader Consensus | Leaderless consensus; optimal commit latency; dependency tracking |
| Howard et al. 2015 (Raft Refloated) | Ch. CFT Consensus — Protocol Analysis | Raft corner cases; critical analysis of understandability claims |
| Buchman 2016 (Tendermint Thesis) | Ch. BFT Consensus — Tendermint | Tendermint origins; ABCI; PoS integration |
| Arun et al. 2017 (CAESAR) | Ch. Multi-Leader / Generalized Consensus | Conflict-tolerant fast-path consensus; CAESAR vs. EPaxos |
| Yin et al. 2019 (HotStuff) | Ch. BFT Consensus — Modern BFT | Linearity + responsiveness; pipelining; blockchain BFT |
| Ailijiang et al. 2019 (Paxi) | Ch. Consensus Comparison — Evaluation | Unified comparison framework; throughput/latency formulas |
| Jalalzai et al. 2023 (Fast-HotStuff) | Ch. BFT Optimization — Variants | Two-round BFT; eliminating unnecessary rounds |
| Alqahtani & Demirbas 2021 | Ch. Scalability Analysis — Bottlenecks | Communication complexity bottleneck; CPU serialization cost |
| Cason et al. 2021 (Tendermint Design) | Ch. BFT Performance — Tendermint Eval | System-level architecture; mempool/consensus/gossip interaction |
| Sondhi 2021 (Thesis) | Ch. Empirical Evaluation — Methodology | Chaos engineering; cross-platform benchmarking approach |
| Berger et al. 2023 (Network Simulation) | Ch. Evaluation Methodology — Simulation | Scalable simulation-based BFT evaluation |
| Buchman et al. 2022 (Tendermint DSN) | Ch. BFT Accountability — Formal Analysis | Accountability in BFT; Tendermint formalization |
| 2023-397 (TENTATIVE: Civit et al.) | Ch. BFT Foundations — Validity | Validity conditions; correctness definitions |
