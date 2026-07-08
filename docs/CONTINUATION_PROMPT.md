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

6. Keep it simple, minimal, correct. No speculative abstraction. The pure-JDK
   LatencyRecorder is a stand-in — when you introduce Maven, swap it for real
   HdrHistogram before trusting any latency number.

## Where to start

We are at the top of M1 → M2 in IMPLEMENTATION_PLAN.md. The skeleton compiles
(`javac -d out $(find src -name '*.java')`), and M0 (single-node etcd via the
pure-JDK HTTP driver) is verified. Do NOT redo M0.

Proposed first increment (confirm with me before coding, then do only this):
M1.1 — turn the skeleton into a Maven project using the provided pom.xml, get
`mvn -q verify` green, and port the M0 smoke to run through the built jar so we
prove the toolchain end-to-end. If `mvn` can't reach Central in your sandbox,
say so, fall back to a documented offline path, and flag it as needing my
machine — don't fake a green build.

Immediately after, M1.2: replace the LatencyRecorder internals with real
HdrHistogram behind the existing API, with a unit test asserting exact
percentiles on a known sample set — and add the Little's-Law relationship
(window ≈ throughput × latency in saturation mode) as a permanent engine
self-test, since it caught a real corroboration in M0.

Then M2.1 (jetcd EtcdDriver + leader detection) as the first real driver,
TDD from its acceptance criterion in the plan.

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
- IMPLEMENTATION_PLAN.md
- DATA_ANALYSIS_METHODOLOGY.md
- MASTER_PLAN.md
- the consensus-bench source tree + pom.xml + results/ (M0 evidence)
- infra/main.tf, observability/
- the consensus papers already in the project

Start by reading PROJECT_STATE.md, confirming the current compile + M0 still
reproduce, then propose the M1.1 increment and wait for my go-ahead.

────────────────────────────────────────────────────────────────────────────
Note on the model switch: this project involves consensus/BFT and distributed-
systems benchmarking. If a cyber-adjacent request in this project ever gets
routed away from Fable to another model, that's the safeguards behavior noted
in the system card, not a problem with your prompt — just re-send or rephrase.
