# EPaxos (Egalitarian Paxos)

## What it is

EPaxos is a **leaderless** Paxos variant introduced by Moraru, Andersen, and Kaminsky at SOSP 2013. The protocol's central insight is that most commands in a real workload do not conflict with each other — if two clients write to different keys, nothing requires those writes to be ordered globally. Classical Paxos and Raft impose a total order on all commands via a single leader, which is unnecessarily restrictive. EPaxos relaxes the ordering to a partial order based on conflict dependencies, and in exchange any replica can commit non-conflicting commands in a single round-trip.

A command in EPaxos proceeds in two phases:

1. **PreAccept**: The command-owning replica assigns the command a tentative sequence number and a set of *interference dependencies* (other concurrent commands it conflicts with). It broadcasts PreAccept to a fast quorum (⌈3F/2⌉ + 1 out of 2F+1). If every replica in the fast quorum returns the same dependencies, the command is **committed on the fast path** in one round-trip.
2. **Accept / Commit**: If dependencies differ across replicas (a conflict was detected), the command-owning replica runs a second round to unify dependencies, then commits on the slow path in two round-trips.

Three features make EPaxos noteworthy:

**No leader bottleneck**: every replica can propose commands simultaneously. In geo-distributed deployments this lets clients commit to their nearest replica, eliminating the wide-area round-trip to a distant leader.

**No leader to fail**: the protocol has no special replica whose loss triggers a complex election. When any replica dies, the remaining majority can continue — commands in-flight from the dead replica are resolved via a recovery protocol but there is no "electing a new leader" step.

**Fast-path throughput**: on low-conflict workloads, EPaxos throughput is essentially the minimum of all replicas' throughput, not capped at a single leader's throughput. This is the claimed advantage over Paxos.

The cost is implementation complexity. EPaxos has a much larger state machine than Paxos (dependency graphs, sequence-number negotiation, recovery protocol) and the slow path is noticeably slower than classical Paxos when conflicts are frequent.

## Why EPaxos is in the thesis

The thesis summary names EPaxos explicitly as a modern evolution of Paxos driven by performance demands:

> "σύγχρονες μεγαλύτερες απαιτήσεις σε απόδοση, διαθεσιμότητα και ανθεκτικότητα σε βυζαντινά σφάλματα, οδήγησαν στην ανάπτυξη πιο εξελιγμένων πρωτοκόλλων συναίνεσης όπως τα EPaxos, HotStuff και Tendermint"

EPaxos is therefore a required chapter in the thesis evolution narrative. We measure it experimentally via Paxi, which includes a reference EPaxos implementation alongside Paxos. Running both under the same framework, on the same hardware, with the same client drivers, lets the thesis make a **controlled head-to-head comparison** — the single best piece of experimental evidence for the "evolution" argument.

## What we test

Same scenarios as Paxos (baseline, leader_kill, packet_loss, network_partition in session 7; double_kill and slow_node in session 5; leader-election CDF in session 6).

**One important semantic difference**: EPaxos has no leader. The "leader_kill" scenario cannot kill a leader because there isn't one. Our `leader_kill.sh` script for EPaxos kills `paxi2` — a non-probe-connected replica — which still exercises EPaxos's recovery protocol (dependency reconstruction for any in-flight commands from the dead replica). We document this in the methodology chapter.

Similarly for the leader-election CDF: we're measuring "time until the probe recovers after any replica dies" rather than "time until a new leader is elected". For EPaxos this should be noticeably **faster** than Paxos because there is no election to run — the remaining replicas just continue, and only in-flight commands from the dead replica need recovery.

## How to read the results

**EPaxos should show higher baseline throughput than Paxos** on non-conflicting workloads. Our `paxi_bench.py` issues PUTs with keys of the form `w<worker_id>k<counter>`. Because each worker has a distinct key namespace, conflicts between workers are effectively zero. This is the classic fast-path scenario EPaxos was designed for. Expect EPaxos to be **2-3x faster than Paxos** at baseline if the fast path is working correctly.

If EPaxos is *slower* than Paxos, something is wrong with the fast-path detection — probably the implementation is falling back to the slow path more often than the workload should trigger. Check Paxi's log output for "slow path" or "conflict" messages.

**Under leader_kill (really "node_kill" for EPaxos), recovery should be dramatically faster** than Paxos. Paxos needs to detect the dead leader, start a Prepare round on a higher ballot, collect a majority of Promise responses, and then resume committing. EPaxos just continues — only commands in-flight from the dead replica need recovery. Expect EPaxos's failover CDF to be tighter and faster than Paxos's.

**Under slow_node, EPaxos should suffer more than Paxos**. Counter-intuitive but correct: EPaxos needs a fast quorum (⌈3F/2⌉+1 = 3 of 3 for F=1) for the fast path; Paxos needs only a simple majority (2 of 3). A slow node in EPaxos drags down the fast quorum; a slow non-leader in Paxos is ignored as long as the leader and one other are fast. This is one of the thesis's more counter-intuitive findings if the data supports it.

## How to write about it

The EPaxos chapter sits after the Paxos chapter in the literature review. Suggested narrative:

> "EPaxos (Moraru et al. 2013) addresses the two main limitations of Multi-Paxos: the leader bottleneck and the cost of leader failure. Instead of a single stable leader, EPaxos allows any replica to commit commands on a fast path (one round-trip) when those commands do not interfere with other in-flight commands. Interference is defined per-application; for a key-value store, two commands interfere iff they touch the same key."

> "In our measurements (Table N, Figure M), EPaxos achieved X ops/s baseline throughput against Paxi-Paxos's Y ops/s — a ratio of Z. This confirms the fast-path advantage on low-conflict workloads. Under node failure, EPaxos's median recovery time was A seconds versus Paxos's B seconds, validating the leaderless-is-faster-to-recover claim. Under slow-node injection, however, EPaxos degraded by P% of baseline throughput while Paxos degraded by only Q% — consistent with EPaxos's larger fast-quorum requirement (⌈3F/2⌉+1 vs Paxos's majority F+1)."

This three-way pattern — EPaxos wins on baseline, wins on recovery, loses on slow-node — is the thesis's contribution to the EPaxos literature. No published paper has tested this combination head-to-head on the same hardware.

## Papers to cite

- **Moraru, Andersen, Kaminsky 2013**, *There Is More Consensus in Egalitarian Parliaments*, SOSP 2013. The original paper.
- **`3299869_3319893.pdf`** in the project bundle — Ailidani et al. 2019, SIGMOD. Our benchmarking methodology. Includes a direct Paxos-vs-EPaxos comparison using the Paxi framework we use.
- **Tollman et al. 2021**, *EPaxos revisited*. Follow-up work examining corner cases in the original protocol; worth citing as "not all EPaxos implementations handle recovery identically".
- **Whittaker et al. 2021** (frankenpaxos framework) — alternative implementation of EPaxos and other Paxos variants; useful as a cross-check if Paxi numbers look suspicious.

## Common pitfalls

**Conflict detection depends on workload.** If the benchmark uses a small key space (e.g., 100 keys with 6 workers), many concurrent PUTs will target the same keys, driving EPaxos mostly through its slow path. Our `paxi_bench.py` uses worker-id-prefixed keys precisely to keep the fast path engaged. If you change the benchmark to use random keys, expect EPaxos numbers to degrade.

**EPaxos recovery protocol is complex.** The original paper and the Paxi implementation differ slightly in how they handle certain corner cases (dependency cycles, interleaved failures). If the correctness check in `run_one.sh` reports FAIL for EPaxos but PASS for Paxos, this is a known artifact rather than a protocol bug — Paxi's recovery path has some known gaps that the research community has patched in forks but not in the ailidani/paxi master branch. Document this in the thesis as a limitation.

**No leader to target for leader_kill.** Our measurement takes the pragmatic choice of killing paxi2 as a proxy for "any replica dies". An alternative would be to kill the probe-connected replica (paxi1), which would force the probe's HTTP library to reconnect — measuring the client-side reconnect time rather than the protocol's recovery time. We chose paxi2 to isolate the protocol's behaviour. State this methodology choice explicitly in the thesis's EPaxos section.
