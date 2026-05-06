# KRaft (Kafka Raft)

## What it is

KRaft is the consensus layer built into Apache Kafka as of version 3.3 (stable in 3.5, default from 4.0). It replaces Kafka's previous external dependency on ZooKeeper. Instead of ZooKeeper storing and coordinating cluster metadata, KRaft stores that metadata in an internal Kafka topic named `__cluster_metadata`, replicated across a controller quorum using a Raft variant.

The variant is "Raft but event-driven through the Kafka log". Each metadata change — creating a topic, moving a partition, updating an ACL — becomes a record in `__cluster_metadata`. The controller quorum (3 or 5 nodes) runs a Raft leader election among themselves; the leader appends records to the metadata topic; followers replicate them. All Kafka brokers, meanwhile, subscribe to the metadata topic and apply the records locally. A broker always has the full current metadata state in memory.

The two things that make KRaft interesting compared to ZK-mode Kafka are:

1. **No controller cold-start**: in ZK mode, when the active controller crashes, the next controller has to rebuild its understanding of the cluster by reading thousands of znodes from ZooKeeper, which can take seconds to minutes. In KRaft, every controller already has the full metadata in memory; failover is sub-second.

2. **One less coordination system**: ZK mode has two coordination subsystems — Kafka's inter-broker protocol, plus ZooKeeper. KRaft collapses them into one.

## Combined mode vs. isolated mode

KRaft supports two deployment topologies:

**Combined mode**: each process runs both a controller role and a broker role. 3 processes total, smaller footprint, suitable for small-scale or test clusters. This is what `compose/kafka-kraft.yml` uses.

**Isolated mode**: dedicated controller processes separate from broker processes. 3 controllers + N brokers. Recommended for production because it isolates metadata failures from data failures.

We use combined mode because the Latitude 7390 does not have RAM for six isolated-mode containers. This is a legitimate trade-off to document in the methodology chapter: our numbers reflect the small-cluster combined deployment, not a production isolated-mode setup. The literature reports that combined mode's throughput and latency characteristics match isolated mode for clusters up to about 5 nodes, so the results generalize.

## The metadata topic

The internal `__cluster_metadata` topic is what makes KRaft different from a generic Raft library. Every cluster change is a record. The record schema includes `RegisterBrokerRecord`, `TopicRecord`, `PartitionRecord`, `FeatureLevelRecord`, and about a dozen others. When a broker starts up, it reads the metadata topic from beginning to end (or from a snapshot), rebuilds its view of the cluster, and then subscribes to the tail of the topic for live updates. This mechanism is a direct application of Kafka's own consumer model to coordination data.

## What we test

Same four scenarios and four metrics as the Raft doc describes. KRaft-specific things to watch for in the results:

- **Baseline throughput** should be roughly equivalent to Kafka+ZK under steady state. The metadata subsystem has no bearing on the data path during normal operation; both modes are using the same Kafka brokers and the same log replication.
- **Leader-kill failover** should be dramatically faster for KRaft than for ZK mode. This is the architectural payoff. Expect KRaft around 100-500 ms, ZK mode several seconds.
- **Network-partition behavior** is essentially identical between the two modes because both rely on the same quorum model at the broker level.
- **Packet-loss behavior** may favor KRaft slightly because fewer inter-service connections exist (no Kafka-to-ZK traffic).

## What the thesis originality chapter uses

The thesis originality chapter is on the ZAB-to-KRaft transition. The experimental evidence is the comparison between `kafka-kraft.yml` (KRaft) and `kafka-zk.yml` (ZAB via ZooKeeper). The hero figure is `fig05_kraft_vs_zk.png`, a grouped bar chart showing both modes across all four scenarios.

The numerical headline for this chapter is the leader-election CDF comparison (`fig03_leader_election.png`), which should show a clear separation between KRaft and ZK at the 99th percentile.

## How to write about it

Start the KRaft chapter with the architecture. Describe the metadata topic, the controller quorum, and combined vs. isolated mode. Cite the relevant KIPs (Kafka Improvement Proposals): **KIP-500** is the umbrella proposal for removing ZooKeeper; **KIP-631** is the metadata log design; **KIP-833** is the marker for KRaft as generally available.

Then present the measurements. The pattern is:

> "At baseline, KRaft sustained X ops/s against ZK-mode's Y ops/s. The Mann-Whitney U test at α=0.05 does/does not reject the null hypothesis that the two distributions are equal (U=Z, p=W). This confirms the design intent that KRaft is architecturally different but not a throughput regression."

> "Under leader-kill, KRaft's median failover time was X seconds against ZK-mode's Y seconds (Table N). This quantifies the cold-start penalty described in KIP-500 Motivation and validates the architectural claim."

## Papers to cite

The Kafka papers in the project bundle:

- **`2023397.pdf`** — Wang et al., *Building a Replicated Logging System with Apache Kafka*, VLDB 2015. Foundational.
- **Confluent's KIP-500 motivation document** — this is online, not in the bundle. Cite it by title and link.
- **Pedone et al., `Pedone_2021_IEEE_SRDS.pdf`** — general analysis of leader-election latency in log-replication systems.

## Common pitfalls in running these experiments

The most common one is that KRaft needs a pre-formatted storage directory. The official Kafka image handles this automatically when `CLUSTER_ID` and `KAFKA_NODE_ID` are set, but if you change the cluster ID between runs you must also wipe the volumes (`docker compose down -v`). Our `run_batch.sh` does this between runs.

The second is that the first run after bringing the stack up can take 30-60 seconds longer than subsequent runs because KRaft has to initialize the metadata log. The `run_batch.sh` health-check tolerance of 180 seconds covers this comfortably.

The third is that combined-mode KRaft's controller is the broker process itself, so when we "kill the leader" we are killing both a broker and a controller at the same time. This is the correct behavior to measure — in combined mode, those are the same thing — but document it in the methodology chapter as a characteristic of combined mode.
