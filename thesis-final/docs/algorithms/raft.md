# Raft

## What it is

Raft is a consensus algorithm designed to be understandable. It replicates a log across a set of servers so that every server agrees on the same sequence of entries. Clients talk only to the current leader; the leader replicates entries to the followers, and once a majority acknowledges an entry, the leader commits it. If the leader fails, one of the followers is elected as the new leader.

Three roles exist in a Raft cluster: **leader**, **follower**, and **candidate**. At any time there is at most one leader; all client requests go through it. Followers are passive — they only respond to the leader's heartbeats and replication RPCs. A follower becomes a candidate when it stops hearing heartbeats and starts an election. If a candidate wins a majority of votes it becomes the new leader.

Safety comes from a small number of invariants: a leader only keeps log entries that are present on a majority of servers; commitment requires acknowledgment from a majority; and every server applies entries to its state machine in the order they are committed. The result is a linearizable log — every operation appears to take effect at a single instant between when it was invoked and when it completed.

## Where it appears in this thesis

Raft is not tested directly. It is the parent algorithm of two systems we do test experimentally: **etcd** (a canonical production Raft implementation, one of the most widely deployed in the world) and **KRaft** (Kafka's internal Raft variant). Reading this document first makes the etcd and KRaft docs much shorter, because both inherit Raft's model.

## What we measure for Raft-family systems

Four numbers, across four scenarios. The numbers are:

**Throughput** — sustained operations per second during the 5-minute measurement window. Measured client-side by the perf tool issuing 1 KB writes as fast as the cluster will accept them, with acks=all (KRaft, ZK) or sequential puts (etcd). This is the headline number of the thesis body.

**Latency percentiles** — avg, p50, p95, p99, and p99.9 of per-operation latency, in microseconds. Percentiles matter more than the average for distributed systems because tails are where pathological cases live; the thesis cites p99 in the text and p99.9 in a footnote where relevant.

**Failover time** — the time from killing the current leader to the first successful client operation on the newly-elected leader. Measured by an external probe container that is not running on the killed node, so it survives the kill. 200 trials per system produce a CDF.

**Throughput under fault** — the same throughput number, but with a fault injected 3 minutes into the run. Shows how each system's throughput recovers (or does not) from the disruption.

The four scenarios are **baseline** (no fault), **leader_kill** (one `docker kill` at the warmup boundary), **packet_loss** (2% loss applied to all cluster containers for the measurement window), and **network_partition** (one node isolated for the measurement window so the remaining 2-of-3 form a quorum).

## How to read the results

Look at the hero figures in `analysis/out/plots/` in this order:

1. `fig01_throughput.png` — which system is fastest at baseline? Error bars are 95% bootstrap CIs; overlapping bars mean the systems are statistically indistinguishable.
2. `fig02_latency_cdf.png` — which system has a tighter p99 latency distribution? A curve that rises steeply at low latency and flattens quickly means a tight, predictable tail.
3. `fig03_leader_election.png` — how fast does each system recover from a leader crash? Lower and tighter curves are better. etcd typically under 1 second, KRaft a few seconds, Kafka+ZK longer.
4. `fig04_under_fault.png` — how much does each fault hurt each system? Compare baseline bar to leader_kill / packet_loss / network_partition bars. The taller the remaining bar, the more resilient the system.

## How to write about it in the thesis

The Raft chapter of the literature review covers the algorithm abstractly. The experimental chapter then uses Raft as the theoretical yardstick against which etcd and KRaft are measured. Typical phrasing:

> "Both etcd and KRaft implement Raft with the single-leader single-log model described by Ongaro and Ousterhout. Our measurements (Table X) show throughput of Y ops/s and p99 latency of Z ms for etcd at baseline, consistent with the paper's claim that Raft can commit a log entry in one message round-trip under stable leadership."

When reporting the Mann-Whitney U test results for pairwise comparisons:

> "At baseline, etcd sustained a mean throughput of X ops/s against KRaft's Y ops/s (n=5 each, Mann-Whitney U=Z, p=0.W). We reject the null hypothesis of equal distributions at α=0.05."

## Papers to cite

The Raft chapter of the thesis literature review should cite Ongaro and Ousterhout 2014, *In Search of an Understandable Consensus Algorithm* (USENIX ATC). The paper is in the project as `atc14paperongaro.pdf`. The thesis visual treatment of leader election (fig03) follows that paper's Figure 16 directly, which is a CDF of election-time measurements — exactly what our `run_le_trial.sh` produces.

## What can go wrong in our measurement

Three things. First, if the probe container is not actually external (if it happens to land on the same host as the killed leader due to scheduling), it may be affected by the kill itself. We mitigate this by running the probe in its own container on the `thesis-net` overlay, not by exec-ing into a SUT container.

Second, if the leader-detection logic in `leader_kill.sh` gets a stale answer, we kill a follower instead of the leader, and the "failover" we measure is trivially zero. To avoid this, the leader-kill script queries the cluster directly every time, never caches, and the analysis script discards any trial with failover time under 10 milliseconds as suspect.

Third, YCSB-style benchmarks can saturate the client before saturating the server, producing an artificially low throughput. We use the systems' native benchmarking tools (`kafka-producer-perf-test.sh`, `benchmark`) which are known to saturate their respective servers first.
