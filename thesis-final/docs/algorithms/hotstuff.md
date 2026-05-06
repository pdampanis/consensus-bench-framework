# HotStuff

## What it is

HotStuff is a leader-based Byzantine-fault-tolerant consensus protocol published by Yin, Malkhi, Reiter, Gueta, and Abraham at PODC 2019. The paper's central contributions are two properties that prior BFT protocols did not achieve simultaneously:

**Linearity**: after the system reaches global stabilization time (GST), a correct leader drives consensus to decision with O(n) authenticators per decision (rather than O(n²) for PBFT or Tendermint). This is the property that lets HotStuff scale to 100+ validators where PBFT's all-to-all voting saturates.

**Responsiveness**: after GST, a correct leader waits only for the first n−f responses rather than a synchrony-bound timeout. The protocol commits at the speed of actual network delay, not maximum network delay. This gives HotStuff much lower tail latency than protocols that bake in worst-case timeouts (like Tendermint, whose rounds advance on timer expiration).

The mechanism that enables both properties is a **three-phase voting pipeline** — Prepare, Pre-Commit, and Commit — where each phase's Quorum Certificate (QC) becomes input to the next. A leader collects n−f votes for one phase, aggregates them into a QC, and immediately issues the next phase. The three-chain commit rule (a block is committed when three consecutive QC-chained blocks exist) is what provides the safety argument.

The original paper presents a basic HotStuff and an event-driven HotStuff (the "chained" or 3-chain variant). Diem (formerly Libra) and Aptos use a 2-chain variant that saves one message delay at the cost of a slightly more complex leader-replacement flow.

## Why HotStuff is in the thesis

The thesis summary names HotStuff explicitly as one of the modern BFT evolution points:

> "σύγχρονες μεγαλύτερες απαιτήσεις σε απόδοση, διαθεσιμότητα και ανθεκτικότητα σε βυζαντινά σφάλματα, οδήγησαν στην ανάπτυξη πιο εξελιγμένων πρωτοκόλλων συναίνεσης όπως τα EPaxos, HotStuff και Tendermint"

The evolution narrative is crucial: HotStuff sits at the leading edge of the BFT story. Tendermint (2014) brought PBFT-style Byzantine consensus to the blockchain era with simple operational semantics but O(n²) messaging and synchrony-bound timeouts. HotStuff (2019) kept Tendermint's simplicity while achieving linearity and responsiveness — the two properties BFT was missing for production-scale deployments. Every modern BFT system — Diem, Aptos, Sui, Flow, and many consortium chains — derives from HotStuff.

## What we test

We use the **asonnino/hotstuff** reference implementation (github.com/asonnino/hotstuff, main branch, 2-chain Diem variant). This is:

- Written in Rust with real cryptography (ed25519-dalek), real networking (tokio), real storage (rocksdb)
- Designed specifically for 4-node local benchmarking (our exact use case)
- The same codebase that produced the published ~966 tx/s, 9 ms end-to-end latency benchmark numbers — directly citable
- Maintained by Alberto Sonnino (ex-Novi/Meta, Mysten Labs) under Apache 2.0

**One container, four validator processes.** The asonnino repo bundles key generation, committee assembly, and process orchestration in a Python + Fabric harness. Rather than reimplement those steps in separate containers, we vendor the entire repo into one Docker image (`docker/hotstuff/Dockerfile`) and use the harness as intended: a single container runs `fab local`, which starts 4 validator processes and 1 client process in a tmux session. This is unusual for our bundle — every other system has one container per validator — but it is the cleanest way to use asonnino's measurement pipeline without breaking its internal assumptions.

**Fault injection happens inside the container.** Because all 4 validators are in one container, `docker kill` would take them all down at once. For leader_kill, double_kill, slow_node, packet_loss, and network_partition, we use an in-container script (`/opt/hs-inject.sh`) invoked via `docker exec`. The script targets specific node processes by port binding (ss / iptables / tc / kill-by-pid). See `docker/hotstuff/hs-inject.sh`.

**Main scenarios (session 8).** baseline, leader_kill, packet_loss, network_partition — 5 runs each, 8 minutes per run. The benchmark driver is the vendored fabric harness; we normalize its SUMMARY block into the same kafka-producer-perf-test format the other systems use, so `parse_results.sh` processes it identically.

**Deep-fault scenarios (session 5).** double_kill and slow_node — same 5 runs per cell as the others. double_kill kills 2 of 4 validators, leaving 2 which is below the 2f+1 = 3 BFT liveness threshold; expected outcome is throughput drops to zero.

**Leader-election CDF (session 6).** 200 trials. Each trial spawns a fresh 4-validator testbed via `fab local` with a 25-second budget, waits 3s for baseline, kills node-0, and parses the remaining validators' log files for the first "Committed" line after the kill. Failover = first-commit-time − kill-time.

## Published reference numbers to validate against

asonnino's own README reports, on a 4-node local cluster with default config:

| Metric | Value |
|---|---|
| Consensus TPS | ~967 tx/s |
| Consensus latency | ~2 ms |
| End-to-end TPS | ~960 tx/s |
| End-to-end latency | ~9 ms |

Our baseline measurement should land in this neighborhood (±30%). If throughput is dramatically lower than 600 tx/s on our i7, the benchmark is probably running with too few CPU cores or the tmux session crashed mid-run. Check `fab.log` for early termination warnings.

## How to read the results

**Three-way comparison: HotStuff vs CometBFT vs everyone else.** The thesis's BFT chapter is where HotStuff's story lives. Expected qualitative findings:

- **HotStuff baseline TPS is similar to Tendermint** (both 10²–10³ on 4 nodes), because both are partially-synchronous BFT with comparable message complexities at small N.
- **HotStuff baseline latency is noticeably lower than Tendermint** — 9 ms vs 30-80 ms typical. This is the responsiveness property: HotStuff commits at actual network delay, Tendermint waits for timer expiration in each round.
- **HotStuff leader-failover is very fast** — the view-change protocol is just one message round rather than Tendermint's round-timeout-plus-new-round. Expect median failover under 1 second.
- **HotStuff and CFT systems are two orders of magnitude apart** at baseline. KRaft at ~12k tx/s vs HotStuff at ~1k tx/s is the cost of Byzantine tolerance. This is the thesis's headline BFT-vs-CFT number.

**Under load/fault:** HotStuff's all-to-all voting pattern during view changes means `packet_loss` and `network_partition` hit it harder than Raft systems. Expect larger throughput drops under these scenarios than KRaft or etcd show.

## How to write about it

The HotStuff chapter of the thesis opens with the theoretical story:

> "HotStuff (Yin et al. PODC 2019) is the first Byzantine-fault-tolerant state-machine-replication protocol to achieve linear communication complexity and responsiveness simultaneously. Prior BFT protocols — PBFT (Castro & Liskov 1999), Tendermint (Buchman 2016) — traded one property for the other: PBFT has responsiveness but quadratic messaging; Tendermint has linear messaging under synchrony but waits for fixed-duration round timers. HotStuff achieves both through a three-phase voting pipeline in which each phase's Quorum Certificate is input to the next, committed on a three-chain rule."

Then the experimental contribution:

> "We evaluate the 2-chain Diem variant of HotStuff via Alberto Sonnino's reference implementation (asonnino/hotstuff, main branch). On a 4-validator cluster tolerating f = 1 Byzantine fault, we measured baseline throughput of X tx/s and end-to-end latency of Y ms (Table N), consistent with published numbers from the same codebase. Against CometBFT under identical conditions, HotStuff's latency was Z% lower (Figure M), illustrating the responsiveness advantage in practice."

Cite both the original paper and asonnino's repo:

- Yin, Malkhi, Reiter, Gueta, Abraham (2019), *HotStuff: BFT Consensus with Linearity and Responsiveness*, PODC 2019. This is `HotStuff_BFT_Consensus_with_Linearity_and_Responsi.pdf` in the project bundle.
- Sonnino, A. *HotStuff: implementation of the HotStuff consensus protocol*. github.com/asonnino/hotstuff. Apache-2.0.

## Papers to cite alongside

- **`1803_05069v6.pdf`** — the HotStuff arXiv paper (latest revision)
- **`2010_11454v10.pdf`** — Fast-HotStuff, a Jalalzai follow-up discussing forking attacks
- **`2309_17245v1.pdf`** — Further HotStuff-family analysis
- **Buchman master's thesis** — for Tendermint comparison
- **Accountable Tendermint (DSN 2022)** — for the Byzantine-accountability angle HotStuff does not address directly

## Limitations and honest caveats

**Latency percentiles are not available from asonnino's harness.** The SUMMARY block reports only *mean* end-to-end latency — no p50/p95/p99/p99.9. Our normalization writes the mean into all percentile fields (avg, p50, p95, p99, p99.9, max = 2×mean). The analysis pipeline (`analyse.py`) detects this as zero-variance synthetic data and:
1. Marks all HotStuff latency percentile rows with `zero_variance: True` in `summary.csv`
2. Labels HotStuff latency comparisons in `pairwise_tests.csv` as `reliable=False`
3. Excludes HotStuff from the latency CDF plot (or labels it "mean-only")
4. Generates `methodology.md` documenting the limitation for the thesis chapter

**HotStuff throughput is summary-only.** The asonnino harness produces a final end-to-end TPS number, not per-second samples. Our normalization emits a single progress line with that TPS. The throughput.csv contains one row per run. The per-run mean is correct; per-run variance is unavailable. Between-run variance (across 5 repeated runs) is genuine and used for statistical tests.

**Correctness verification is not possible for HotStuff.** The asonnino implementation has no read/get API. We report `NO_DURABILITY_API` with the end-to-end TPS from the SUMMARY block. This verifies execution completeness, not data durability. The thesis methodology chapter must document this as a limitation: HotStuff experimental results reflect benchmark execution correctness, not state machine durability verification.

**Fault injection is process-level, not container-level.** Our in-container injector kills PIDs, runs tc on loopback, and uses iptables INPUT/OUTPUT rules on port 9000. This is not quite the same methodology as the Pumba-based container-level injection used for other systems. Document this as "fault injection for HotStuff is process-level via an in-container script, equivalent in effect to Pumba's container-level operations."

**Single host performance cap.** 4 validator processes plus 1 client plus tmux in one container means ~5 processes fighting for 4 CPU cores. asonnino's published 966 tx/s is from a lightly-loaded local host; on a fully-provisioned i7 with 32 GB we expect similar numbers, but a laptop running other loads will produce lower throughput. Record the CPU load during runs (see `results/environment.txt`).

**HotStuff is not measured for scalability.** The scalability sub-matrix (sizes 3/5/7) covers only kraft and etcd. Extending to HotStuff would require modifying asonnino's fabfile to emit configs for non-default committee sizes, which is possible but out of scope. For scalability beyond N=4, the thesis's literature review cites the original paper's 100-validator AWS experiments.

**Not production code.** Like Paxi for Paxos and EPaxos, asonnino's HotStuff is a research-grade reference implementation. Absolute performance is lower than a commercial BFT system (Diem, Aptos) would achieve. The value here is the **controlled comparison** with the other systems under identical conditions, not absolute numbers against cutting-edge production implementations.
