CONTINUATION PROMPT — paste this into a fresh session (Fable) to resume the
consensus-benchmark implementation. Attach the project files noted at the end.

────────────────────────────────────────────────────────────────────────────

You are continuing the implementation of a Java consensus-benchmark harness
for my master's thesis. Read PROJECT_STATE.md in the project files first — it
is the single source of truth for where things stand. Then read
IMPLEMENTATION_PLAN.md for the task breakdown and DATA_ANALYSIS_METHODOLOGY.md
for how results must be produced. If anything I say here conflicts with
PROJECT_STATE.md, ask me; don't silently pick one.

## How I want you to work (non-negotiable)

1. EXECUTE, DON'T ASSERT. You have a code sandbox with JDK 21. If a claim can
   be compiled, run, or parsed, do it and show the output before you say it
   works. The M0 milestone already in the repo is the template: real etcd, real
   numbers, a Little's-Law cross-check. I have been burned on this project
   specifically by code that was shipped on confidence and failed at first
   contact. Verified beats plausible every time.

2. TDD, strictly. For each component: write the acceptance test FIRST from the
   plan's acceptance criterion, run it, watch it fail for the right reason,
   then implement until it passes, then show both the test and the passing run.
   No implementation lands without its test.

3. KARPATHY RULE — keep me in the loop. Work in small, reviewable increments:
   ONE component or ONE gate-step per turn. At the end of each increment, stop
   and give me: (a) what you did, (b) the evidence it works, (c) what you did
   NOT verify, (d) the single next step. Do not generate large multi-component
   batches I can't review. I would rather do ten small correct turns than one
   big one I have to reverse-engineer. When you hit a real decision point,
   surface it and wait rather than guessing.

4. HONEST REVIEW every time you claim something is done. Separate "verified in
   sandbox" from "assumed / needs your machine." Name the untested parts
   explicitly. Correct your own overconfidence — I value that over reassurance.

5. RESPECT THE GATES (G1/G2/G3 in the plan). Never advance past a gate on
   confidence; its evidence must exist. G2's human read-through of the SSH
   golden files is mine to do — surface them, don't skip them.

6. Keep it simple, minimal, correct. No speculative abstraction.

## Where to start

**P0, P1, and THE WHOLE P2 DRIVER PHASE are closed (2026-07-16): P2.1
etcd/jetcd, P2.2 Kafka (+G1 flaw-B parity), P2.3 CometBFT (602 tx/s
acceptance), P2.4 Paxi a/b/c (pinned-source image, probe-write quorum
gate, Ballot-header leader detection, EPaxos exercised, D7 sweep e2e),
P2.5 HotStuff SUMMARY parser. Suite: 101 tests green via `mvn21 clean
verify` (integration tests need the local Docker daemon + the once-per-
machine `docker build -t paxi:6823d0b infra/paxi`).** The measurement
instrument is complete: open-loop engine with CO correction, real
HdrHistogram + latency.hlog, EventLog failover, manifest v2, first-error
capture, `local-run` one-command loop, five production driver surfaces
with bounded (5 s) completion. Do NOT redo any of it — PENDING_TASKS.md
is the ledger (F1–F27), PROJECT_STATE.md §3 the evidence,
MEASUREMENT_DIAGRAMS.md the architecture reference.

Proposed next increment (confirm with me before coding, then do only this):
**P3.3c — the remote FaultInjector, golden-file TDD.** G1 is SIGNED OFF;
P3.3a (SshExecutor seam + sshj, acceptance vs a real sshd) and P3.3b
(RemoteSshProvider, etcd first — golden written FIRST as the spec,
recorded sequence matches verbatim, F20 resolved) are DONE. Build the
SSH FaultInjector on the same seam: docker kill by NodeHandle; netem
packet-loss/delay with the interface resolved per node via
`ip -o route get <peer_private_ip>` and PARSED from the recorded output
(never an assumed eth0); iptables partition as pairwise node-IP rules
that PRESERVE the loadgen→leader path (observing the partitioned leader
IS the measurement); stress-ng slow_node; **heal in finally, provably
always emitted** (a golden must show the heal even when injection
throws). F13/F19 targeting is already pinned by FaultInjectorApplyTest;
F26 (paxi leader_kill semantics) must be DECIDED before any paxi golden.
Then P3.3d extends goldens per (system, scenario, size) for the G2 human
read-through. Also pending: P2.6 (safety-oracle scope, before M5.5),
P2.0 (scheduler scaling — only if triggered).

## What "done" looks like for this whole effort

The definition of done is in IMPLEMENTATION_PLAN.md §"Definition of done":
mvn verify green; G1–G3 archived with evidence; a full campaign tree (CSVs +
per-run metrics/ + validity.json, ≥95% valid) downloaded; the calibration
deltas documented; all 8 figures regenerable from the archive with one
command; and the Hetzner project holding zero servers at rest.

## A methodology thread to keep alive as you build

Every metric we will measure has a PREREGISTERED EXPECTATION: before running a
cell, we state, from protocol theory, the expected direction and rough order
(e.g. HotStuff's O(n) authenticators should give it a throughput edge over
Tendermint's O(n²) at larger n; single-leader protocols should show a leader
CPU ceiling; CometBFT latency should sit near its block interval). The results
chapter is organized as expected-vs-observed, and every material deviation gets
attributed to one of: measurement artifact, implementation property,
environment, or genuine protocol behavior. Build the harness so this is
capturable — expectations recorded alongside runs, deviations computable.
Don't let this become an afterthought bolted on at analysis time.

## Attach these project files

- PROJECT_STATE.md (read first)
- PENDING_TASKS.md (the backlog + status ledger)
- IMPLEMENTATION_PLAN.md
- DATA_ANALYSIS_METHODOLOGY.md
- MASTER_PLAN.md
- LOCAL_TESTING.md (exact commands + expected outputs)
- the consensus-bench source tree + pom.xml + results/ (M0 evidence)
- infra/ (main.tf + cloud-init.yaml), observability/
- the consensus papers already in the project

Start by reading PROJECT_STATE.md, confirming `mvn21 clean verify` is still
green (101 tests, Docker required), then confirm G1 sign-off and propose
the P3.3 increment, and wait for my go-ahead.

────────────────────────────────────────────────────────────────────────────
Note on the model switch: this project involves consensus/BFT and distributed-
systems benchmarking. If a cyber-adjacent request in this project ever gets
routed away from Fable to another model, that's the safeguards behavior noted
in the system card, not a problem with your prompt — just re-send or rephrase.
