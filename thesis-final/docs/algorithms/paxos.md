# Paxos

## What it is

Paxos is the foundational consensus protocol. Introduced by Leslie Lamport in 1989 (published 1998), it is the algorithm from which almost every other modern consensus protocol descends — directly or indirectly. Raft was designed explicitly as "an understandable Paxos"; ZAB is "Paxos adapted for ZooKeeper's primary-backup model"; EPaxos is "Paxos without a stable leader"; HotStuff is "Paxos-style voting with linear complexity". Understanding Paxos is prerequisite to understanding the rest of the family.

The core problem Paxos solves is **agreement among a set of processes that communicate asynchronously and may fail by stopping**: given that each process may propose a value, the processes should agree on exactly one value, even though any process can crash and messages may be arbitrarily delayed.

**Single-decree Paxos** (Lamport's original protocol) decides on one value. It has two roles — *proposers* and *acceptors* — though in practice every process plays both roles. The protocol runs in two phases:

1. **Prepare / Promise**: A proposer picks a ballot number higher than any it has seen and sends a Prepare(b) to a majority of acceptors. Acceptors that have not promised a higher ballot respond with Promise(b, prev_value), where prev_value is the last value they accepted (if any).
2. **Accept / Accepted**: If the proposer receives Promise from a majority, it sends Accept(b, v) where v is either its own proposed value or — if any Promise contained a prev_value — the prev_value with the highest ballot. Acceptors that have not promised a higher ballot respond with Accepted(b, v). Once a majority of acceptors Accept a value, that value is chosen.

The safety invariant is that **if a value is chosen, no different value is ever chosen**. This holds because any future proposer with a higher ballot will learn the chosen value through its Prepare round and re-propose that same value.

**Multi-Paxos** — what every real system uses — extends single-decree Paxos to decide a sequence of values (a log). The optimization that makes it practical is electing a **stable leader** that skips the Prepare phase for every subsequent entry: the leader runs one Prepare round to claim the role, then issues Accept rounds directly until it fails or is pre-empted. This is the form every Paxos implementation — and every Raft implementation, which is essentially Multi-Paxos with a cleaner specification — uses in production.

## Why Paxos is in the thesis

The thesis summary explicitly names Paxos as a classical foundational protocol:

> "Κλασικά πρωτόκολλα όπως το Paxos και το Raft, έχουν αποτελέσει θεμέλια για τη σχεδίαση αξιόπιστων κατανεμημένων υπηρεσιών"

Paxos must therefore appear in the theoretical/architectural analysis of the thesis. We also measure it experimentally via the **Paxi framework** (github.com/ailidani/paxi) — the same framework used by the SIGMOD 2019 paper "Dissecting the Performance of Strongly-Consistent Replication Protocols" that is in the project bundle as `3299869_3319893.pdf`. Using Paxi means our experimental methodology is directly citable to published work.

Paxi implements classical Multi-Paxos with a stable leader. Our compose file `compose/paxi-paxos.yml` starts three replicas with `-algorithm=paxos` and Paxi handles the full protocol machinery.

## What we test

Same four main scenarios and same metrics as the Raft systems:

**Baseline, leader_kill, packet_loss, network_partition** in session 7 (paxos + epaxos combined). 5 runs per cell. The benchmark driver `paxi_bench.py` issues HTTP PUTs against Paxi's RESTful API with 6 concurrent workers; Paxi's internal forwarding handles redirection to the leader. Output format is parse_results.sh-compatible so downstream code treats the data identically to all other systems.

**double_kill and slow_node** in session 5 (deep faults). double_kill crosses the majority boundary on a 3-node cluster (needs 2 of 3 for majority; 1 remaining is below quorum), so throughput should drop to zero. slow_node applies 70% CPU load on one replica via Pumba; Paxos is leader-centric so a slow leader hurts badly while a slow follower matters less.

**Leader-election CDF** in session 6. Kill the leader; measure time until the probe's next successful PUT. Classical Multi-Paxos triggers a new Prepare round on a higher ballot when the old leader fails; the recovery time is dominated by the dead-leader detection timeout plus one round-trip for Prepare.

## How to read the results

**Paxos baseline throughput vs Raft systems.** Multi-Paxos and Raft are algorithmically near-identical — both elect a stable leader, both commit via majority ack — so their throughput should be in the same order of magnitude. Expect Paxi-Paxos to land in the low thousands of ops/s: less than Kafka (which batches aggressively at the log level) but comparable to etcd for single-operation commits. If Paxi-Paxos is dramatically slower than etcd, suspect the implementation rather than the algorithm — Paxi is research-grade code, not production-tuned.

**Leader-kill failover.** This is where Paxos's lack of Raft's pre-vote and randomized-timeout mechanisms shows up. Raft was designed with specific enhancements (randomized election timeouts, pre-vote) that Paxos's original specification does not include. Expect Paxi-Paxos's failover-time tail to be noticeably longer than etcd's — split-vote scenarios take multiple rounds to resolve.

**Paxos is the theoretical baseline.** When you write the thesis, the narrative is "Raft's observed failover-time advantage over Multi-Paxos is not algorithmic, it is implementation: Raft specifies the mitigations for split votes; Paxos leaves them as an exercise for the implementer." This is the classical evolution argument — Paxos proved the problem was solvable, Raft proved it could also be specified *understandably*.

## How to write about it

The Paxos chapter of the literature review opens the experimental story. Suggested structure:

> "Paxos (Lamport 1989/1998) established the template for all subsequent consensus protocols: a majority-quorum commit rule, phased voting with monotonically increasing ballot numbers, and a safety argument that depends on no single replica being decisive. This section describes the single-decree Paxos protocol and the Multi-Paxos optimization, then discusses how later protocols (Raft, ZAB, EPaxos) refined the Paxos template for specific deployment needs."

Then the experimental chapter cites Paxi:

> "We evaluate classical Multi-Paxos via the Paxi framework (Ailidani et al. 2019), which provides a reference implementation alongside EPaxos in the same codebase. This allows direct head-to-head measurement with identical workloads, client concurrency, and network conditions — eliminating variance from implementation-specific factors when comparing the two protocols."

> "At baseline, Paxi-Paxos sustained X ops/s (Table N). Under leader-kill, median failover was Y seconds. These numbers are consistent with those reported by Ailidani et al. (Table 4 of their SIGMOD 2019 paper) scaled for our single-host laptop environment."

## Papers to cite

- **Lamport 1998**, *The Part-Time Parliament*, ACM TOCS. The original (famously allegorical) Paxos paper.
- **Lamport 2001**, *Paxos Made Simple*. The approachable re-explanation.
- **Chandra, Griesemer, Redstone 2007**, *Paxos Made Live*. Google Chubby's lessons from building production Paxos.
- **`3299869_3319893.pdf`** in the project bundle — Ailidani et al. 2019, *Dissecting the Performance of Strongly-Consistent Replication Protocols*. Introduces Paxi, measures Paxos and EPaxos head-to-head. Our experimental methodology directly follows this paper.
- **`2023397.pdf`** — contextual, discusses Paxos-family choices for replicated logs.

## Common pitfalls in running these experiments

**Paxi is research code.** Its focus is correctness and algorithmic fidelity, not production-grade performance. Absolute numbers will be lower than etcd. The value of Paxi in the thesis is not as a production benchmark but as a **controlled comparison**: same framework, same client library, same network layer, *different algorithm flag*. Head-to-head Paxos vs EPaxos numbers from Paxi are methodologically cleaner than comparing two independently-implemented systems.

**Leader detection for leader_kill.** Paxi exposes a `/state` HTTP endpoint that reports per-replica ballot numbers and whether the replica considers itself leader. `leader_kill.sh` queries paxi1's /state, parses the "leader" field, and targets whichever replica id it reports. If the endpoint doesn't respond in time (Paxi is still initializing), the script falls back to killing paxi1.

**Cluster size fixed at 3.** Unlike KRaft and etcd, we do not vary the Paxi cluster size. The reason is methodological: Paxi's reported numbers are already noisy because of its research-code status, and introducing scalability-sub-matrix noise on top would make comparisons hard to interpret. The thesis's scalability chapter focuses on KRaft and etcd where production-quality implementations give cleaner curves; Paxi provides the algorithmic comparison point at the 3-node baseline.
