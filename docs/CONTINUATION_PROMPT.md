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

**P0, P1, the WHOLE P2 driver phase, and P3.3a-d for etcd+KRaft+Paxi are
closed (2026-07-17): SshExecutor seam (acceptance vs a real sshd),
RemoteSshProvider (etcd + KRAFT + PAXOS/EPAXOS, goldens written FIRST as
the spec, matched verbatim), SshFaultInjector (kill/netem-on-resolved-
iface/pairwise-partition/slow_node, heal LIFO loud), F26 locked (paxi
leader_kill = adaptive-mode wedge). The KRaft remote recipe — the
highest-risk one — was de-risked in two verified steps:
KraftMultiBrokerFormationTest proved the env-var contract BY EXECUTION
(3-broker quorum, acks=all under min.insync.replicas=2, Isr=3), then the
golden encoded the remote deltas (private-IP voters/advertised, host
networking, fixed cluster id + no volume = byte-fresh state, api-versions
quorum oracle, BARE host:port endpoints). The 2026-07-17 review fixed
F28 (slowNode SSH backgrounding held the exec channel open — nohup +
stream redirect, measured red vs a real sshd), F29 (remote pre-clean
sweeps thesis-* on ALL provisioned nodes), F30 (EtcdHttpDriver never
claims a leader). P3.3d-cometbft is ALSO DONE, both steps: step 1
verified the DISTRIBUTION-shaped 4-validator recipe BY EXECUTION
(testnet as one-shot keygen; four small JSONs per node incl.
priv_validator_state; init keeps pre-placed files; peers via
--p2p.persistent_peers excluding self; ids via CMTHOME=<dir>
show-node-id — the --home flag is IGNORED; seds for
max_subscription_clients AND addr_book_strict=false; n_peers=3, tx
committed through 3-of-4 precommits, all replicas reached the height);
step 2 encoded it as `tendermint-size4-start-stop.txt` (keygen/
show-node-id as docker run --rm one-shots, artifacts compacted to
single-line JSON before printf, fresh-state rm -rf first, private-IP
peers, /health + latest_block_height>=1 quorum gate, size≠4 refused)
matched verbatim by the RemoteSshProvider TENDERMINT branch. The
P3.3d-kafka_zk STEP 1 is ALSO DONE: KafkaZkColocatedFormationTest
verified the D10 colocated shape BY EXECUTION — the apache/kafka
entrypoint REFUSES ZK mode, so brokers bypass it (printf
server.properties + kafka-server-start.sh; SAME image digest as KRaft —
F6 stays identical-binaries; ZK mode logs "started
(kafka.server.KafkaServer)"), and the digest-pinned zookeeper:3.9
ensemble serves Prometheus :7000 via ZOO_CFG_EXTRA (znode_count —
P4.3's source); acks=all committed under min-ISR 2, Isr=3. STEP 2 is
ALSO DONE: golden `kafka_zk-size3-start-stop.txt` (two containers per
VM, private-IP wiring, server.properties printf'd inside the broker
start script, ZK :7000 gate + ZK-MODE started line + api-versions==3,
teardown brokers-then-ensemble) matched verbatim by the provider
KAFKA_ZK branch — the RemoteSshProvider now serves SIX of seven
systems; only HOTSTUFF fails closed.
Suite: 114 tests green via `mvn21 clean verify`
(integration tests need the local Docker daemon + the once-per-machine
`docker build -t paxi:6823d0b infra/paxi`).** The measurement instrument
is complete. Do NOT redo any of it — PENDING_TASKS.md is the ledger
(F1–F30), PROJECT_STATE.md §3 the evidence, MEASUREMENT_DIAGRAMS.md the
architecture reference.

Proposed next increment (confirm with me before coding, then do only this):
**P3.3d-hotstuff — the last system, verify-first, starting with the
image.** The asonnino/hotstuff build is research code (Rust node +
Python/fab orchestration): (1) pin a source commit and write
infra/hotstuff/Dockerfile (the paxi pattern — multi-stage cargo build,
record the image id); (2) probe the node binary's ACTUAL CLI contract
(keys/committee file generation, ports, mempool/consensus params) — do
NOT assume fab's localnet semantics transfer; (3) verify a 4-node
formation locally (committee file with all keys, one container per
node, a committed client burst visible in the SUMMARY-feeding logs);
(4) only then the remote golden + provider HOTSTUFF branch. Expect
this to span multiple increments — the image+CLI probe alone is one.
After that: the G2 human read-through of ALL goldens, and the P3.4
canary. Also pending: P2.6 (safety-oracle scope, before M5.5), P2.0
(scheduler scaling — only if triggered).

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
green (114 tests, Docker required), then propose the P3.3d-hotstuff
image+CLI-probe increment, and wait for my go-ahead.

────────────────────────────────────────────────────────────────────────────
Note on the model switch: this project involves consensus/BFT and distributed-
systems benchmarking. If a cyber-adjacent request in this project ever gets
routed away from Fable to another model, that's the safeguards behavior noted
in the system card, not a problem with your prompt — just re-send or rephrase.
