# consensus-bench-thesis

Unified Java benchmark harness for comparing consensus protocols (KRaft,
Kafka+ZooKeeper, etcd, CometBFT/Tendermint, Paxos, EPaxos, HotStuff), for a
master's thesis on the evolution and comparison of consensus protocols in
distributed systems.

This repository is the consolidated handoff bundle. Read
`docs/PROJECT_STATE.md` first — it is the single source of truth for where the
implementation stands.

---

## THE TWO-PART HANDOFF (read this before anything else)

Continuity across future Claude sessions needs two separate things, because a
project knowledge base and a code repository serve different purposes:

### Part 1 — the git repo (the code + evidence) — DONE

The harness source, the verification evidence, the Terraform infra, and the
observability config live in version control:
`https://github.com/pdampanis/consensus-bench-framework` (HTTPS via the `gh`
credential helper — see `docs/GIT_WORKFLOW.md`). Commit and push at the end
of every working session.

### Part 2 — Upload these specific files to the Claude PROJECT knowledge

So a fresh session automatically loads context, upload the Markdown docs (the
project knowledge base handles documents well; it does NOT handle a live code
tree well):

- `docs/PROJECT_STATE.md`          ← the most important one; upload this
- `docs/PENDING_TASKS.md`          ← prioritized backlog + status ledger
- `docs/IMPLEMENTATION_PLAN.md`
- `docs/DATA_ANALYSIS_METHODOLOGY.md`
- `docs/MASTER_PLAN.md`
- `docs/CAMPAIGN_RUNBOOK.md`       ← topology, phases, cost, Prometheus retrieval
- `docs/EXECUTION_AND_COST_MODEL.md` ← one-system-at-a-time model, per-system cost, artifact collection
- `docs/LOCAL_TESTING.md`          ← manual local verification: exact commands + expected output
- `docs/METRICS_AND_SOURCES.md`     ← study material for the thesis defense
- `docs/JAVA_HARNESS_ASSESSMENT.md`

Re-upload `PROJECT_STATE.md` and `PENDING_TASKS.md` after any session that
changes them — they are the state; stale copies misdirect the next session.

### Part 3 — `docs/CONTINUATION_PROMPT.md` is PASTED, not uploaded

That file is the message you send to start the next working session. Paste its
contents; attach the project (which now has the docs from Part 2).

---

## Why the split

A Claude project's memory is a *summary*, not a file store — it will not
preserve `PROJECT_STATE.md` verbatim or the compiled code. The uploaded docs
give a fresh session the plan and state; the git repo gives it (and you) the
actual, versioned, verifiable code. Relying on memory alone would lose both.

---

## Repository layout

```
consensus-bench-thesis/
├── README.md                        ← you are here
├── harness/                         ← the Java benchmark harness
│   ├── pom.xml                      Maven build (verified: mvn21 clean verify green)
│   ├── src/main/java/gr/thesis/bench/
│   │   ├── core/                   WorkloadEngine, LatencyRecorder (HdrHistogram),
│   │   │                           EventLog (failover), enums
│   │   ├── driver/                 ConsensusDriver SPI + EtcdDriver (jetcd,
│   │   │                           production) + EtcdHttp/Paxi drivers
│   │   ├── results/                CsvResultsWriter (manifest v2, latency.hlog)
│   │   ├── topology/               ClusterProvider SPI + LocalDockerProvider
│   │   └── Main.java               CLI: endpoint-run + local-run (one command,
│   │                               clean→deploy→run→teardown)
│   ├── src/test/java/              112 tests (TDD; integration tests need Docker)
│   └── results/                    M0 EVIDENCE — real etcd run outputs
├── infra/
│   ├── main.tf                     cluster as Terraform, phase-parameterized
│   │                               (validated + dummy-token planned; NEVER applied — G2)
│   └── cloud-init.yaml             per-VM substrate (docker, node_exporter, chrony)
├── observability/
│   ├── prometheus.yml              scrape config, role labels (promtool-verified)
│   ├── docker-compose.yml          obs VM: Prometheus + Grafana
│   ├── export_queries.txt          per-run PromQL archive set (22 queries)
│   └── grafana/provisioning/       datasource-as-code
└── docs/
    ├── PROJECT_STATE.md            ← READ FIRST; single source of truth
    ├── PENDING_TASKS.md            prioritized backlog + status ledger + F-findings
    ├── IMPLEMENTATION_PLAN.md      execution-grade plan, gates G1–G3
    ├── DATA_ANALYSIS_METHODOLOGY.md thesis-chapter-grade methodology
    ├── MASTER_PLAN.md              decisions D1–D11 + architecture
    ├── CAMPAIGN_RUNBOOK.md         topology, phases, cost, retrieval protocol
    ├── EXECUTION_AND_COST_MODEL.md one-system-at-a-time model, per-system cost
    ├── LOCAL_TESTING.md            manual verification: exact commands + outputs
    ├── MONITORING_GUIDE.md         novice guide: watch/debug runs, Grafana+Prometheus demo
    ├── MEASUREMENT_DIAGRAMS.md     engine core + per-system commit-path diagrams
    ├── METRICS_AND_SOURCES.md      metric definitions + papers to study
    ├── JAVA_HARNESS_ASSESSMENT.md  why Java, why a harness
    ├── CONTINUATION_PROMPT.md      ← PASTE this to start the next session
    ├── GIT_WORKFLOW.md             remote + auth (gh credential helper)
    ├── SSH_SETUP.md                planned SSH switch for the remote
    └── archive/                    retired shell/v6 approach — HISTORY ONLY
        ├── HONEST_REVIEW_V6.md     the post-mortem that motivated the rebuild
        ├── REVIEW.md               original shell-bundle review
        └── DEPLOYMENT_GUIDE.md     retired shell deployment guide
```

## Current status (verified, not asserted — as of 2026-07-17)

- **P0, P1, and the whole P2 driver phase closed; GATE G1 SIGNED OFF.
  P3.3a-d (SSH seam + etcd/KRaft/paxi remote providers + fault injector,
  golden-verified — the KRaft and CometBFT recipes each de-risked by a
  real multi-node formation run BEFORE their goldens were written).
  Remote providers now serve etcd/KRaft/CometBFT/Paxos/EPaxos; the
  Kafka+ZK (D10) colocated shape is verified by execution (golden
  pending), leaving only HotStuff. Suite: 112 tests green** (`mvn21 clean
  verify`; the integration tests need the local Docker daemon + the
  once-per-machine `docker build -t paxi:6823d0b infra/paxi`). Details
  and evidence: `docs/PROJECT_STATE.md` §3, ledger in
  `docs/PENDING_TASKS.md` (F1–F30), diagrams in
  `docs/MEASUREMENT_DIAGRAMS.md`.
- **The measurement instrument is complete**: open-loop engine with CO
  correction, real HdrHistogram (true mean, `latency.hlog` pooling input),
  `EventLog` failover instrumentation, manifest v2 (params, digests,
  config hash, honest status), typed K=1000 key contract + D7 conflict knob.
- **`local-run` one-command loop works**: pre-clean → fresh digest-pinned
  etcd (size 1 or 3) → measured run → guaranteed teardown, ~8 s wall-clock.
- **Production etcd driver (jetcd/gRPC) done**, leader detection
  cross-validated against the independent HTTP stack; 5 s per-op deadline
  (writes fail closed on lost quorum, never hang the drain).
- `infra/` **validated + dummy-token planned for all three phases; NEVER
  applied** (Gate G2 intact). `observability/` promtool-verified, not deployed.
- **M0 evidence** (real etcd 3.4.30, Little's-Law cross-check) in
  `harness/results/` — pre-P1.3 vintage, kept as the original reference.

## Honest caveats (do not skip)

- **The P2 driver phase is COMPLETE (2026-07-16)** — etcd, Kafka,
  CometBFT, Paxos/EPaxos (image built from pinned source: `docker build
  -t paxi:6823d0b infra/paxi` once per machine), HotStuff SUMMARY parser.
  **G1 evidence is in-suite; formal sign-off is the author's** (see the
  G1 STATUS entry in PENDING_TASKS). Honest residuals: the HotStuff
  fixture is format-derived until a real Phase-C run re-pins it; Kafka's
  local parity gate is the order-of-magnitude band (15% at G3/M6.1 on the
  cluster); paxi leader_kill semantics are preregistered at P3.3 (F26:
  stock paxi has no failure detector).
- **No remote layer yet**: RemoteSshProvider + FaultInjector + golden tests
  (P3.3) and the canary (P3.4) gate any `terraform apply` (G2).
- **No ValidityChecker / PrometheusExporter / campaign runner yet**
  (P4.1/P4.2/M3.3).
- **`analyse.py` and the React visualizer are not in this repo** (F15): the
  writer targets their contract, but the contract is unverifiable here until
  they are vendored or golden-tested.
- **ZK :7000 and Kafka JMX metric names are unverified** until real
  exporters run (P4.3).
- **Laptop numbers are functional evidence only** — `environment=local` is
  never thesis data.

## Quick start for the next work session

```bash
# 1. build + full suite (needs Docker; ~1.5 min)
cd harness && mvn21 clean verify          # expect: Tests run: 112, BUILD SUCCESS

# 2. the one-command local loop (the P0 deliverable)
java -jar target/consensus-bench-0.1.0-SNAPSHOT.jar \
  local-run --size 3 --rate 100 --duration 6 --warmup 2 -v

# 3. exact expected outputs for everything laptop-provable
#    → docs/LOCAL_TESTING.md (9-point green checklist)
```

Then continue from `docs/PENDING_TASKS.md` (next: **P3.3d-kafka_zk STEP 2 —
the remote golden + provider branch**; the colocated shape is verified
fact as of 2026-07-17), one
increment per session, per the working agreement in `docs/PROJECT_STATE.md` §9.
