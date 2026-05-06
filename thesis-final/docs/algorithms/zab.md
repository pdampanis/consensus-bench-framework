# ZAB (ZooKeeper Atomic Broadcast)

## What it is

ZAB is the consensus protocol that powers ZooKeeper. Like Raft, it replicates an ordered log across a small group of servers, with a single leader at a time and a majority-quorum commit rule. Unlike Raft, ZAB was designed specifically for ZooKeeper's workload of frequent small metadata writes with many long-lived watches, and its algorithmic details reflect that origin.

A ZAB ensemble has three roles: **leader**, **follower**, and (in some implementations) **observer**. Observers do not vote; we ignore them. Every write goes to the leader. The leader assigns the write a monotonically increasing *zxid* (ZooKeeper transaction id), proposes it to the followers, and waits for a majority to acknowledge. Once acknowledged, the leader broadcasts a COMMIT message and the write is applied.

The protocol runs in two phases. **Broadcast phase** is the steady-state operation just described. **Recovery phase** runs when a leader fails or when the ensemble starts: a new leader is elected, the leader synchronizes its log with every follower up to the highest committed zxid, and then the ensemble transitions back to broadcast phase. Recovery is where ZAB's behavior differs most from Raft — it is more elaborate because ZooKeeper clients have strong session semantics (watches, ephemeral znodes) that must survive the transition.

## How ZAB shows up in Kafka

Kafka in ZooKeeper mode uses ZooKeeper to store cluster metadata: the list of brokers, the list of topics, partition assignments, ACLs, and so on. A Kafka broker acts as a client of ZooKeeper. One Kafka broker at a time holds the controller role, claimed by creating an ephemeral znode at `/controller`; if that broker dies, its session expires, the znode disappears, and another broker races to create it.

Critically, **controller election in ZK-mode Kafka has two serial parts**:

1. The ZooKeeper ensemble itself must have a live leader (ZAB-level election).
2. A Kafka broker must observe the `/controller` znode disappear and successfully re-create it (Kafka-level election, mediated by ZK).

After a Kafka controller fails, the new controller has to re-read cluster metadata from ZooKeeper — every topic, every partition, every config override. This **cold start** can take seconds to minutes on large clusters, and is the specific reason KRaft was designed. The thesis originality chapter quantifies exactly this cost.

## What we test

Same three CFT-family metrics as the Raft document describes: throughput, latency percentiles, and failover time. Same four scenarios: baseline, leader_kill, packet_loss, network_partition.

For leader_kill in the Kafka+ZK setup, our `leader_kill.sh` script targets the **Kafka controller broker**, not the ZooKeeper leader. The reason: the controller election path (kill controller → new broker claims `/controller` znode → new controller reads metadata from ZK) is the architectural cost we are measuring. The ZAB-internal election of a new ZooKeeper leader is fast and not the bottleneck the thesis is investigating. Document this choice explicitly in the methodology chapter — it is the same measurement that KIP-500's motivation section cites.

## How to read the results

For the headline KRaft-vs-ZK comparison, look at:

1. `fig01_throughput.png` — both Kafka modes should produce similar throughput at baseline. The data path is the same in both modes; metadata architecture does not affect steady-state producer performance.
2. `fig03_leader_election.png` — this is where ZK-mode should look materially worse. Expected behavior: KRaft median under 1 second, ZK mode median 2 to 10 seconds depending on how much metadata has to be re-read.
3. `fig05_kraft_vs_zk.png` — direct head-to-head across all scenarios. The hero figure of the originality chapter.

## How to write about it

The ZAB literature review section should explain the algorithm abstractly — broadcast phase, recovery phase, zxid ordering — then explain how Kafka uses it. Key citations:

> "Kafka's original architecture (Wang et al., 2015) relied on ZooKeeper to coordinate broker metadata. The controller role, implemented via an ephemeral znode, is won by whichever broker first observes a prior controller's session expire. This introduces a cold-start penalty on controller failover, as the newly-elected controller must reconstruct its view of the cluster from ZooKeeper."

When describing experimental results:

> "At baseline, ZK-mode Kafka sustained X ops/s compared to KRaft's Y ops/s (n=5 each, Mann-Whitney U=U, p=P). The difference is / is not statistically significant; both modes deliver equivalent steady-state throughput."

> "Under leader-kill, ZK-mode's median failover was X seconds against KRaft's Y seconds. This 5-10x gap quantifies the architectural penalty of external metadata coordination and is consistent with the numbers reported in KIP-500's motivation."

## Papers to cite

- **`279227_279229.pdf`** in the project bundle — Junqueira et al., *ZooKeeper: Wait-free coordination for Internet-scale systems*, USENIX ATC 2010. The original ZooKeeper paper.
- **`2023397.pdf`** — Wang et al., *Building a Replicated Logging System with Apache Kafka*, VLDB 2015. Describes Kafka's original ZK-based architecture.
- For the ZAB protocol specifically, the canonical reference is Junqueira, Reed, and Serafini, *ZAB: High-performance broadcast for primary-backup systems*, DSN 2011. Not in the bundle; cite by title.

## Common pitfalls in running these experiments

**Single ZooKeeper vs. ZK ensemble.** Our `kafka-zk.yml` deploys a single ZooKeeper node, not a 3-node ensemble. This is a deliberate simplification — a single node has no ZAB to run, just the broadcast phase. The thesis claim is about Kafka controller election, not ZAB itself, so a single ZK node is sufficient. Document this in the methodology chapter and note that in production you would deploy a 3 or 5-node ZK ensemble. Our numbers are strictly better than a production setup would produce, which strengthens the thesis claim rather than weakening it: even with a trivially-fast ZK, the controller cold-start cost dominates.

**Controller vs. broker failover.** In our runs the "controller" is whichever broker owns `/controller` at any given moment, and the leader_kill script kills that specific container. Because controller ownership rotates as brokers come and go, you cannot pre-compute which container to kill; the script must query ZooKeeper every trial. Our script does this correctly via `echo 'get /controller' | zkCli.sh`.

**Session timeouts.** ZooKeeper sessions have a configurable timeout (default 30 seconds in ZK 3.9) that affects how quickly the `/controller` znode disappears after a kill. The Kafka default is 18 seconds. Our compose does not override this, so measurements reflect the default. If you want to see the best-case ZK failover, you can lower the session timeout via `KAFKA_ZOOKEEPER_SESSION_TIMEOUT_MS`, but document any change you make.
