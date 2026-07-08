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

### Part 1 — `git init` this whole directory (the code + evidence)

The harness source, the M0 verification evidence, the Terraform infra, and the
observability config are a live codebase. They belong in version control, not
in a chat project's knowledge base:

```
cd consensus-bench-thesis
git init
git add .
git commit -m "Consolidated handoff: M0-verified harness skeleton + infra + docs"
# push to your GitHub/GitLab
```

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

Then, in `docs/PROJECT_STATE.md`, replace the "code lives in the sandbox" note
with your actual git repo URL, so a session knows where the real code is.

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
│   ├── pom.xml                      Maven build (deps pinned; see caveat below)
│   ├── src/main/java/gr/thesis/bench/
│   │   ├── core/                   WorkloadEngine, LatencyRecorder, enums
│   │   ├── driver/                 ConsensusDriver SPI + Etcd/Paxi drivers
│   │   ├── results/                CsvResultsWriter (analyse.py contract)
│   │   ├── topology/               ClusterProvider SPI (impls TODO)
│   │   └── Main.java               minimal CLI
│   └── results/                    M0 EVIDENCE — real etcd run outputs
├── infra/
│   └── main.tf                     full cluster as Terraform (apply/destroy)
├── observability/
│   ├── prometheus.yml              scrape config (templated on inventory)
│   ├── docker-compose.yml          obs VM: Prometheus + Grafana
│   ├── export_queries.txt          per-run PromQL archive set
│   └── grafana/provisioning/       datasource-as-code
└── docs/
    ├── PROJECT_STATE.md            ← READ FIRST; single source of truth
    ├── IMPLEMENTATION_PLAN.md      execution-grade plan, gates G1–G3
    ├── DATA_ANALYSIS_METHODOLOGY.md thesis-chapter-grade methodology
    ├── MASTER_PLAN.md              decisions + architecture
    ├── METRICS_AND_SOURCES.md      metric definitions + papers to study
    ├── JAVA_HARNESS_ASSESSMENT.md  why Java, why a harness
    ├── CONTINUATION_PROMPT.md      ← PASTE this to start the next session
    └── archive/                    retired shell/v6 approach — HISTORY ONLY
        ├── HONEST_REVIEW_V6.md     the post-mortem that motivated the rebuild
        ├── REVIEW.md               original shell-bundle review
        └── DEPLOYMENT_GUIDE.md     retired shell deployment guide
```

## Current status (verified, not asserted)

- Harness skeleton **compiles** under JDK 21 (`javac`, 10 source files),
  reproduced from the Maven `src/main/java` layout in this bundle.
- **M0 executed against real etcd 3.4.30**: open-loop 300 ops/s → 306.6
  achieved / 0 errors; saturation → 1020.8 ops/s, p50 58.9 ms, cross-checked
  by Little's Law (predicted 62.7 ms). Evidence in `harness/results/`.
- `infra/main.tf` is HCL-parse-valid; **not yet `terraform apply`-ed**.
- `observability/` YAML is valid; **not yet deployed**.

## Honest caveats (do not skip)

- **The pom.xml dependency set is NOT resolution-verified.** It was authored
  without Maven Central access. First real action on your machine:
  `cd harness && mvn -q verify`. If a coordinate fails, fix the version — they
  are mainstream, but "unverified" is the honest label.
- **The Maven build itself has never run** — only `javac` on the raw sources
  has. `mvn verify` green is milestone M1.1, not a done thing.
- **`LatencyRecorder` is a pure-JDK stand-in** for HdrHistogram (~3% bucket
  error). It must be swapped for real HdrHistogram (M1.2) before any latency
  number is trusted for the thesis.
- **`CsvResultsWriter` writes p50 as the `avg` field** as a documented
  placeholder; real mean lands in M1.4.
- **M0 is single-node** — no quorum exercised yet. The multi-node ladder is
  M3 (local processes) then M4.6 (2-VM canary).
- **No drivers for Kafka/CometBFT/HotStuff yet**, and no ClusterProvider
  implementations. See `docs/IMPLEMENTATION_PLAN.md` M2–M4.

## Quick start for the next work session

```bash
# 1. verify what's here still holds
cd harness
javac -d /tmp/out $(find src/main/java -name "*.java")   # should be clean

# 2. the real first step (needs Maven + internet)
mvn -q verify                                            # M1.1 acceptance

# 3. reproduce M0 (needs a local etcd on :2379)
#    see docs/PROJECT_STATE.md §3 for the exact command
```

Then follow `docs/IMPLEMENTATION_PLAN.md` from M1, one increment per session,
per the working agreement in `docs/PROJECT_STATE.md` §9.
