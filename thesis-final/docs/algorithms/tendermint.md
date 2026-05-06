# Tendermint (CometBFT)

> **Measured experimentally in v3** (was literature-only in v2). CometBFT is the only BFT system in the experimental campaign; it provides the crash-fault-tolerant vs Byzantine-fault-tolerant comparison the thesis title commits to.

## What it is

Tendermint is a Byzantine-fault-tolerant consensus algorithm. "Byzantine" means it tolerates nodes that behave arbitrarily — crashing, lying about state, sending contradictory messages to different peers, signing invalid blocks. This is a stronger threat model than Raft or ZAB, which only tolerate crash faults (a node either works correctly or stops).

CometBFT is the current name of the Tendermint Core implementation, after a 2023 rename from "Tendermint Core" to "CometBFT". The algorithm is unchanged; the name is not. We use CometBFT v0.38.12 in the experiments.

A Tendermint cluster has validators that take turns as the **proposer**. The algorithm proceeds in rounds. In each round:

1. **Propose**: the designated proposer broadcasts a proposed block.
2. **Prevote**: every validator broadcasts a Prevote message endorsing that block (or nil if the proposer is suspected faulty).
3. **Precommit**: if a validator sees 2f+1 Prevotes for the same block, it broadcasts Precommit for that block.
4. **Commit**: if a validator sees 2f+1 Precommits for the same block, it commits.

If any phase fails to get 2f+1 matching votes in time, the round ends and the next round begins with a new proposer. The proposer rotation is deterministic — round r + 1 always has a different proposer than round r, which prevents a single Byzantine validator from blocking progress forever.

The fault tolerance is **f Byzantine faults in 3f + 1 validators**. Our 4-validator cluster tolerates 1 Byzantine fault.

## How our experiments work

**4 validators** in a Docker Compose stack with an init container. The init container runs `cometbft testnet --v 4` once on first start, which generates:
- One shared `genesis.json` (same chain ID, same initial validator set with matching public keys)
- Per-validator `priv_validator_key.json` (each node's secret Ed25519 key)
- Per-validator `node_key.json` (p2p identity)
- Per-validator `config.toml` with correct moniker and persistent_peers

The init container writes these into a shared Docker volume `cmt-testnet`, then exits. The four validator services wait for init completion via `depends_on: condition: service_completed_successfully`, copy their assigned nodeN/ directory into their own CMTHOME, and start.

**The benchmark driver** is `cmt_bench.py` which we wrote specifically for this purpose. YCSB has no Tendermint binding; the Cosmos SDK's `simd` test tool is too heavyweight. `cmt_bench.py` drives the built-in kvstore application via HTTP RPC (`/broadcast_tx_commit`) with 6 concurrent workers, records per-operation latency, and emits output in the same format as `kafka-producer-perf-test.sh` so `parse_results.sh` parses it with the existing parser.

**Leader detection for the leader_kill scenario** queries `/block` for the latest committed block's `proposer_address`, then matches that against `/validators` to recover the validator index (1-based), which maps to `tm1` through `tm4`. This is the correct proposer-detection logic — it replaces the buggy v1 code that queried `/status` and returned the node's own moniker.

## What we measure

Same five scenarios and same metrics as the CFT systems:

**Baseline, leader_kill, packet_loss, network_partition** in session 3 (main campaign). Same 5 runs per cell, same 8-minute duration, same throughput and latency percentiles captured by `cmt_bench.py` and normalized by `parse_results.sh`.

**double_kill, slow_node** in session 5 (deep-fault campaign). Killing 2 of 4 validators leaves 2 alive, which is below the 2f+1 = 3 threshold for BFT liveness. Same expected outcome as a 3-node CFT cluster with 2 down: throughput drops to zero.

**Leader-election CDF** in session 6. 200 trials of kill-proposer-and-measure-recovery. The "recovery" is when `broadcast_tx_commit` starts succeeding again under the next proposer.

## How to read the results

The headline Tendermint story in the thesis will be **the order-of-magnitude throughput gap versus CFT**. Typical Tendermint numbers on a 4-validator cluster are 100-1000 transactions per second at baseline. Typical Kafka KRaft numbers on a 3-node cluster are 10,000-100,000 records per second. This two-order-of-magnitude gap is the core empirical finding of the BFT chapter. It quantifies the cost of Byzantine fault tolerance relative to crash fault tolerance.

Expected shape of the failover curve: `fig03_leader_election.png` should show Tendermint rotating through rounds faster than Raft systems settle elections — but Tendermint's per-round cost is higher. Median Tendermint failover should be in the 1-3 second range (roughly one round-timeout duration); median Raft failover should be sub-second. These compete on different axes, and the CDF makes the trade-off legible.

Under `double_kill`, Tendermint behaves exactly like a CFT system with too many failures: the remaining 2 validators cannot form a 3-of-4 supermajority, so no rounds complete, no blocks are committed, throughput is zero. This empirically confirms the BFT liveness bound.

Under `slow_node` (one validator at 70% CPU load), Tendermint's all-to-all voting should suffer more than Raft's leader-centric commit — the slow validator's votes become a bottleneck. Expected observation: throughput drops 20-40% under slow_node for Tendermint vs 5-15% for Raft systems. This is one of the thesis's more original findings if the data supports it.

## How to write about it

Three paragraphs in the BFT chapter:

> "Byzantine fault tolerance (BFT) extends consensus to tolerate arbitrary node misbehavior. Where Raft and ZAB assume nodes fail by stopping, BFT protocols assume nodes may exhibit any behavior consistent with their message schema, including signing contradictory messages. The classical fault bound is f Byzantine nodes in 3f+1, established by Castro and Liskov's PBFT (2002).
>
> Tendermint (Buchman 2016, Kwon 2014) is a leader-based BFT protocol structured around rotating proposers and two-phase voting (Prevote + Precommit). We evaluate CometBFT v0.38.12, the current open-source implementation, on a 4-validator cluster — the smallest meaningful BFT configuration, tolerating 1 Byzantine fault.
>
> At baseline, CometBFT sustained X ops/s (Table N) against KRaft's Y ops/s on 3-node hardware. The ~100× gap reflects the fundamental cost of the all-to-all voting pattern (Prevote and Precommit each require 2f+1 messages from 2f+1 validators) versus KRaft's leader-aggregated Raft commits. Under leader-kill, CometBFT's median failover was X seconds against KRaft's Y seconds; the larger Tendermint figure reflects round-timeout periods rather than the randomized election backoff used by Raft."

When discussing `double_kill` and `slow_node`, cite the papers for theoretical grounding and the CSV data for empirical numbers.

## Papers to cite

- **`Buchman_Ethan_201606_Msater_thesis.pdf`** — Buchman's master's thesis introducing Tendermint. Foundational.
- **`HotStuff_BFT_Consensus_with_Linearity_and_Responsi.pdf`** — Yin et al., *HotStuff*, PODC 2019. For comparing Tendermint against a more modern BFT protocol.
- **`Accountable_Tendermint___DSN_2022.pdf`** — Civit et al., *Polygraph*, DSN 2022. Extends Tendermint with Byzantine-behaviour attribution.
- **`2103_04234v4.pdf`** — PaxiBFT, unified benchmarking framework for BFT protocols.

## What can go wrong in our measurement

**The init container runs only on first `docker compose up -d`.** The check at the start of tm-init's entrypoint (`if [ -f /testnet/node0/config/genesis.json ]`) short-circuits on re-runs. This is intentional — regenerating keys between runs would break the existing chain state. But it means if you need to reset cleanly you must `docker compose down -v` (with `-v` to wipe volumes, including `cmt-testnet`) before the next `up`.

**RPC timeout on overloaded clusters.** `cmt_bench.py` uses a 10-second HTTP timeout for `broadcast_tx_commit`. Under heavy load (especially at `slow_node` scenarios), some requests may time out. The driver counts these as errors, not successes, which is the honest accounting.

**Proposer moniker vs proposer_address.** The leader-kill script must match the current proposer's address against the validators list to find the moniker. If the cluster is in the middle of a round transition when we query, the reported proposer for the *latest committed* block may differ from the proposer for the *current* round. We kill whoever committed the latest block; on a stable cluster this is the same as the current proposer with high probability. Document this timing detail in the methodology chapter if a reviewer asks about it.

**4 validators is small.** Tendermint's performance characteristics change noticeably at 10+ validators (the O(n²) message complexity starts to dominate). Our 4-node measurement is the entry point of the curve, not a full characterization. This is fine for the thesis — we are illustrating the BFT-vs-CFT gap, not mapping BFT scalability — and is documented in the methodology chapter.
