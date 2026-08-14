# consensus-bench-thesis

Unified Java benchmark harness for comparing consensus protocols (KRaft,
Kafka+ZooKeeper, etcd, CometBFT/Tendermint, Paxos, EPaxos, HotStuff), for a
master's thesis on the evolution and comparison of consensus protocols in
distributed systems.

This repository is the consolidated handoff bundle. Read
`docs/PROJECT_STATE.md` first — it is the single source of truth for where the
implementation stands.

---

## Start here

**`docs/PROJECT_STATE.md` is the single driving document.** Decisions, open
issues, the prioritized queue, and the step-by-step merge plan to `main` all
live there. Every other file in `docs/` is reference material; `docs/archive/`
is history (including the full F1–F76 findings ledger, which code comments
cite by F-number).

The code, the verification evidence, the Terraform layer and the
observability config are all in this repo:
`https://github.com/pdampanis/consensus-bench-framework` (HTTPS via the `gh`
credential helper — see `docs/GIT_WORKFLOW.md`). Commit and push at the end of
every working session.

If you are handing this to a fresh assistant session, point it at
`CLAUDE.md`, which points at `PROJECT_STATE.md`. Nothing else needs priming —
that is the whole reason there is one driver.

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
│   │   ├── topology/               ClusterProvider SPI + Local/Remote providers
│   │   │                           + SshFaultInjector (all golden-tested)
│   │   ├── campaign/               Inventory + RemoteRunner (remote-run cell)
│   │   │                           + RemoteLogs (chunked whole-log retrieval)
│   │   └── Main.java               CLI: endpoint-run + local-run + remote-run + campaign-run
│   ├── src/test/java/              TDD suite (count: PROJECT_STATE; needs Docker)
│   └── results/                    M0 EVIDENCE — real etcd run outputs
├── infra/
│   ├── main.tf                     cluster as Terraform, phase-parameterized
│   │                               (validated + dummy-token planned; NEVER applied — G2)
│   └── cloud-init.yaml             per-VM substrate (docker, node_exporter, chrony)
├── observability/
│   ├── prometheus.yml              scrape config, role labels (promtool-verified)
│   ├── docker-compose.yml          obs VM: Prometheus + Grafana
│   ├── export_queries.txt          per-run PromQL archive set (25 queries)
│   ├── offline/                    replay a collected campaign snapshot on the laptop
│   └── grafana/provisioning/       datasource + dashboards as code (campaign overview
│                                   + per-algorithm, each with an embedded reading guide)
├── analysis/
│   └── analyse.py                  collected tree → per-cell CIs + spreads (stdlib; --selftest)
├── scripts/
│   └── collect_block.sh            one command: results + Prometheus snapshot → one dated dir
└── docs/
    ├── PROJECT_STATE.md           ← THE DRIVER: decisions, issues, queue, merge plan
    ├── MASTER_PLAN.md             decision rationale D1–D15
    ├── DATA_ANALYSIS_METHODOLOGY.md  wins on methodology (stats, validity, threats)
    ├── IMPLEMENTATION_PLAN.md     wins on acceptance criteria (M0–M6, gates G1–G3)
    ├── CAMPAIGN_RUNBOOK.md        topology, phases, cost, retrieval protocol
    ├── EXECUTION_AND_COST_MODEL.md one-system-at-a-time model, per-system cost
    ├── GIT_WORKFLOW.md            branch convention, the 9-item merge gate
    ├── SIMULATION_AND_RULES_ANALYSIS.md  prior-art survey + citations; §9 is thesis material
    ├── METRICS_AND_SOURCES.md     metric definitions + preregistered expectations
    ├── OBSERVABILITY_AND_EXPECTATIONS.md per-algo baselines + false-positive catalogue
    ├── LOCAL_TESTING.md · PER_ALGORITHM_TEST_GUIDE.md · MONITORING_GUIDE.md  operator guides
    ├── MEASUREMENT_DIAGRAMS.md · JAVA_HARNESS_ASSESSMENT.md  architecture + rationale
    ├── examples/ · SSH_SETUP.md
    └── archive/                   HISTORY ONLY — retired v6 approach, and:
        └── FINDINGS_LEDGER_F1-F76.md  the full findings evidence trail
```

## Current status (verified by execution, 2026-08-15)

`mvn21 clean verify` → **`Tests run: 243, Failures: 0, Errors: 0`**, BUILD
SUCCESS, on `eighth-review-tier1`.

- **P0, P1, P2 (Gate G1 signed off), and the whole remote layer are closed.**
  `RemoteSshProvider` serves all seven systems, each recipe verified by a real
  formation run before its golden.
- **The validity layer is real, not declared.** `ValidityChecker` is wired
  into the run flow, `PrometheusExporter` archives each run's metric window,
  harness self-metrics run on :9400, and the docker-events audit is
  collected. Gates that actually evaluate went from **1 of 10 to 9 of 11**;
  the two SKIPs are legitimate (N/A on fault runs, and P2.6's open scope
  decision).
- **The evidence layer ships with the data**: named simulations published as
  `simulation.json`, the rule and its false-positive triage written into
  every verdict, a per-simulation `journal.jsonl`, GRADE-adapted per-cell
  `grades.csv`, and `analyse.py` failing closed on unverifiable runs.
- **Confidence before the VMs**: fault effects proven on real ubuntu-24.04
  containers (partition really stops traffic; heal really restores it), the
  export path proven against a real Prometheus, and the host toolchain's exit
  codes measured on the campaign's own OS (`infra/probes/`).
- `infra/` is validated and dummy-token planned; **never applied — Gate G2 is
  intact.**

## Honest caveats (do not skip)

- **Nothing has run on a real VM.** The goldens encode intent and cannot
  catch semantic SSH errors; the P3.4 canary is where sudo, cloud-init
  timing, private-net routing and stderr wording are first seen.
- **Three goldens changed since they were last called FINAL** — the G2
  read-through must be redone before anything bills.
- **Laptop numbers are functional evidence only**, never thesis data.
- HotStuff remains the least-instrumented system (log-derived metrics,
  BASELINE-only remotely) and every figure it appears in says so.

## Quick start for the next work session

```bash
# 1. build + full suite (needs Docker + both local image builds; ~4 min)
cd harness && mvn21 clean verify          # expect: BUILD SUCCESS (count: PROJECT_STATE)

# 2. the one-command local loop (the P0 deliverable)
java -jar target/consensus-bench-0.1.0-SNAPSHOT.jar \
  local-run --size 3 --rate 100 --duration 6 --warmup 2 -v

# 3. per-algorithm tests, debugging, and benchmark checklists
#    → docs/PER_ALGORITHM_TEST_GUIDE.md
#    exact expected whole-suite outputs → docs/LOCAL_TESTING.md
```

Then take the top unblocked item from `docs/PROJECT_STATE.md` §6. As of
2026-08-15 the top three are the author's: the **G2 golden read-through**
(blocks everything billed), the P3.5 price check, and the P3.4 canary. One
increment per session, per the working agreement in `PROJECT_STATE.md` §9.

