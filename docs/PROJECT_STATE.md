# PROJECT STATE — the single driving document

**This is the only document that drives the project.** Decisions, open
issues, pending work, and the route to `main` all live here. Everything else
in `docs/` is *reference* — consulted when this file sends you there, never
the thing you plan from. If this file and a reference file disagree about
what to DO, this file wins; if they disagree about a METHOD, the reference
wins (see §8).

**Last verified: 2026-08-15.** `mvn21 clean verify` → **`Tests run: 243,
Failures: 0, Errors: 0`, BUILD SUCCESS**, on branch `eighth-review-tier1`
at `e8561f6`. Count read from Maven's own summary line, never from
arithmetic over report files (F76).

---

## 1. What this project is

A master's thesis: *Evolution and Comparison of Consensus Protocols in
Distributed Systems*. Theoretical analysis plus an experimental benchmark of
**seven systems** — KRaft, Kafka+ZooKeeper (ZAB), etcd (Raft), CometBFT
(Tendermint), Paxos, EPaxos (both via Paxi), and HotStuff. Metrics:
consensus latency, throughput, fault tolerance, scalability.

The experiment is a **unified Java harness** (`consensus-bench`) driving all
seven through ONE async load model, on a **multi-VM Hetzner cluster**, with
Prometheus/Grafana as the explanation-and-validity layer.

**Why it was rebuilt, because it explains every rule below.** The previous
approach died twice: *measurement flaws* (each system measured by a different
client stack — CometBFT capped at ~6 tx/s by 6 blocking clients; every HTTP
probe doing a TCP handshake inside the latency sample), and a *"v6" deploy
layer shipped unverified* that would have failed at first contact. The lesson
encoded into everything: **execute and verify, never assert.**

---

## 2. Where it stands

### Done and verified by execution

- **P0/P1** — the measurement instrument: open-loop engine with
  coordinated-omission correction, real HdrHistogram (`latency.hlog` for
  §3 pooling), `EventLog` failover instrumentation, manifest v2, typed
  K=1000 key contract, D7 conflict knob.
- **P2 (Gate G1 signed off 2026-07-16)** — all drivers: etcd (jetcd),
  Kafka (KRaft + ZK), CometBFT, Paxi (Paxos/EPaxos), HotStuff SUMMARY
  parser.
- **P3.3** — the whole remote layer: `RemoteSshProvider` serves **7/7
  systems**, every recipe verified by a real formation run BEFORE its
  golden, `SshFaultInjector` with all five fault primitives.
- **M3.3** — `remote-run` (one cell) and `campaign-run` (one system block:
  seeded shuffle, manifest-resume, failure-continues, `--dry-run`).
- **M5 observability, complete as of this session** — `PrometheusExporter`
  (M5.4), `ValidityChecker` **wired into the run flow**, harness
  self-metrics on :9400 (M5.3), docker-events audit (P4.5), Grafana
  dashboards, offline replay.
- **The evidence layer** — named simulations published as
  `simulation.json`, per-simulation `journal.jsonl`, per-cell
  `grades.csv`, and `analyse.py` failing closed.

### The number that matters most

**Validity gates that actually evaluate: 1 of 10 → 9 of 11.**

At the start of this session `ValidityChecker` was a library *nothing
called*, so **no campaign run produced a `validity.json` at all**, and a run
measured against it evaluated ONE gate and still reported `valid: true`.
Measured again on 2026-08-15 with the full collector set: **9 of 11
evaluate**. The two SKIPs are both legitimate — `baseline_error_rate` is
N/A on a fault run by design, and `durability` awaits P2.6's *scope
decision*, not a missing collector.

### Not built yet

- `S4.2` report generator (queue #18 — deliberately last; delete it if
  unread after the pilot).
- `M6.4` analysis v2: pooled histograms, Holm–Bonferroni, ECDFs, the 8
  figures (queue #21 — the largest single build left).
- HotStuff fault scenarios (queue #26 — needs preregistration decisions
  first; HotStuff is BASELINE-only today).
- `M1.3` picocli CLI (see F75 below).

### Never done, and gating everything billed

**No `terraform apply` has ever run.** Gate G2 is intact.

---

## 3. How work happens here (the flow)

```
  ┌─ read THIS file → pick the top unblocked queue item (§6)
  │
  ├─ 1. failing test FIRST, watched red for the RIGHT reason
  ├─ 2. minimum code that passes it
  ├─ 3. mvn21 clean verify  → count from Maven's summary line, ≥ the §0 count
  ├─ 4. update §5/§6 of this file with evidence
  ├─ 5. commit (message states the red, the reasoning, the gate result)
  └─ 6. push to the working branch
         │
         └─ when the branch is done → §7 merge runbook → main
```

**Worktrees.** Two Claude sessions worked this repo concurrently on
2026-08-14 and shared ONE checkout, where the failure mode is a *silent lost
update*, not a merge conflict. They are now isolated:

| Worktree | Branch | Role |
|---|---|---|
| `~/Downloads/consensus-bench-thesis` | `seventh-review-tier1` | the original session; **idle since 2026-08-14**, fully contained in the branch below |
| `~/Downloads/cbt-eighth` | `eighth-review-tier1` | **where all current work lives** |

Git refuses one branch in two worktrees, which is why the *mid-increment*
session kept its path and the other moved. If a third session is needed:
`git worktree add ../cbt-<topic> -b <topic>` — never a second session in an
existing checkout.

**Build:**
```bash
cd harness && mvn21 clean verify      # ~/tools/maven/mvn21.sh if no alias
# Needs Docker + BOTH local images, once per machine:
#   docker build -t paxi:6823d0b infra/paxi
#   docker build -t hotstuff:dc01ac8 infra/hotstuff
```

---

## 4. Locked decisions (do not relitigate without cause)

Rationale for each lives in `MASTER_PLAN.md` §1. Index only:

| # | Decision |
|---|---|
| D1 | Multi-VM on Hetzner; one consensus node per VM |
| D2 | Docker is per-VM *packaging*, not topology (host networking, pinned digests) |
| D3 | Dedicated vCPU (CCX13) for consensus nodes — shared vCPU is a validity threat |
| D4 | The Java harness is the load generator; Paxi is a system under test **and** a cross-validation oracle, never the harness |
| D5 | Harness = primary instrument; Prometheus = explanation + validity |
| D6 | Paxi's in-memory commit vs fsync-backed etcd/Kafka is **documented, not equalized** |
| D7 | Conflict ratio (0/2/10%) is a workload factor for the Paxi pair |
| D8 | Raft scalability {3,5,7} on 7 provisioned VMs |
| D9 | BFT nodes upsized (CCX23/33) — the CFT/BFT hardware seam is an accepted, documented threat |
| D10 | Kafka+ZK runs ZooKeeper **colocated** on the broker nodes, so F6 holds hardware constant |
| D11 | Loadgen on dedicated vCPU — the instrument must not sit on shared cores |
| — | **No Ansible** — Terraform owns infra, cloud-init owns first boot, the harness owns dynamic orchestration |
| **D12** | A simulation is a **typed Java record**, published as `simulation.json`. **No framework adopted** — Gatling, Drools, Jepsen and Chaos Toolkit surveyed and refused with reasons; four *artifacts* copied instead |
| **D13** | Result confidence is an **ordinal grade adapted from GRADE** (Guyatt et al., BMJ 2008), never a numeric score. Grades a **CELL** (n=5), not a run. `VOID` is our addition — GRADE has no level for "cannot evidence its own claim" |
| **D14** | Packet-loss severity is a **workload factor swept at 5% and 30%**; severity is part of run identity |
| **D15** | Fault semantics settled: partition is pairwise IP DROP preserving the loadgen→leader path; slow_node is host `stress-ng` with a **run-shape-derived** timeout; failover trials use the runbook's 180+180 shape; EPaxos/CometBFT targeting is fixed for cells and **rotated** for failover trials |

---

## 5. Open issues

Closed findings F1–F76 with their evidence live in
`docs/archive/FINDINGS_LEDGER_F1-F76.md` — the audit trail, kept because
code comments reference F-numbers. Only what is still OPEN is below.

| # | Issue | Why it matters | Next |
|---|---|---|---|
| **F75** (half) | `picocli 4.7.6` is declared, shaded into the uber-jar, and referenced only in a comment (`Main.java:23`). Micrometer, its twin, **was** spent this session (M5.3) | Build weight and supply-chain surface for zero benefit | Spend it (M1.3) or drop it from the pom. Queue #11b |
| **F76** | A `mvn21 clean verify` once reported BUILD SUCCESS having run only **90 tests across 15 classes**, skipping the test covering the change being gated. Did not reproduce; cause unknown and deliberately not invented | The whole discipline rests on "a green verify means the suite ran". One counter-example weakens every green-gate claim including G1's | **Watch.** Mitigation is live as gate 1 of §7. If it recurs, capture `target/surefire-reports/` **before** re-running — the re-run destroyed the evidence last time |
| **F68** (residual) | The Kafka parity band was widened to 0.1x–10x and stays in `verify`. It measured 1.28x and 1.86x since | Resolved in practice; listed so a future red is read against its history, not as a fresh regression | None unless it reds again |
| **N1** | For preregistered-failure scenarios (the F26 paxi wedge, DOUBLE_KILL) the writer's `error_rate > 0.5 ⇒ failed` rule could exclude exactly the preregistered evidence | Would delete a finding the thesis wants | Verify at the M6.2 pilot; if it trips, the choice is scenario-aware status vs analysis-side handling — author's call |
| **N2** | `corpus/` tracks the signed thesis-assignment form. The GitHub repo is PRIVATE today | If ever made public, the file and its git history go too; removal then needs a history rewrite | Author's call before any public push |

**Decisions the author still owes** (each blocks a queue item):

- **P2.6 scope** — the durability/safety oracle. It is the one remaining
  gate that SKIPs for want of a *decision* rather than a collector.
- **HotStuff fault preregistration** (queue #26): target semantics, the
  `faults` field's meaning for kill scenarios, and whether the upstream
  client survives a mid-run kill (probe locally first).

---

## 6. The work queue

Confidence = **how likely this lands as specified, first attempt, without a
new decision.** It is *not* D13's result-confidence grade; do not let the two
words collide.

Everything below the billing gate is **done**. What remains is either yours,
or costs money.

| # | Item | Blocks / notes | Conf |
|---|---|---|---|
| **1** | **G2 golden read-through — YOURS, no code** | **Blocks everything billed.** Read all 7 goldens in `harness/src/test/resources/goldens/` against their header checklists, then sign off here. **Three changed this session** and need re-reading: the F69 host-sweep block (all seven), `slow_node`'s derived `--timeout`, and packet-loss severity as a parameter | 90% |
| **2** | **P3.5 price check — YOURS, ~10 min** | `hcloud server-type list` → sync `local.hourly_eur` in `main.tf` + runbook §2. Blocks first apply | 90% |
| **3** | **P3.4 canary** — 2 VMs, one etcd cell, <€0.10 | First real-VM contact. See "what is still unproven" below | **55%** |
| 4 | Per-system remote smokes | One short cell each on Phase-A infra | 45% |
| 5 | **G3 / M6.1 cross-validation** | Harness vs native tools, ≤15% or explained | 40% |
| 6 | **M6.2 pilot** | Fixes every PROVISIONAL threshold numerically. Feeds D13's grades | 55% |
| 7 | **M6.3 campaign** | Full matrix, ~118 h ≈ 5 unattended days, ≈€61 | 35% |
| 8 | **M6.4 / S5.3 analysis v2** | Pooled `.hlog` histograms, Holm–Bonferroni, ECDFs, the 8 figures. Largest single build left | 50% |
| 9 | S4.2 report generator | Lowest value here — the journal is the load-bearing artifact. **Delete this item if unread after the pilot** | 55% |
| 10 | NEXT-4 HotStuff fault scenarios | Needs the preregistration decisions in §5 | 40% |
| 11 | P2.6 durability oracle | Needs the scope decision in §5 | 50% |
| 11b | M1.3 picocli CLI | Closes F75's other half | 75% |
| 12 | F8 / P2.0 scheduler scaling | **Conditional only** — build if G3 shows achieved < 99% of target. Do not build speculatively | 80% |

### What the canary will and will not tell you

Proven locally this session, so the canary is no longer the first time
anyone sees it work:

- `HostFaultEffectTest` — on real ubuntu-24.04 containers with `NET_ADMIN`,
  driven through the **real** `SshFaultInjector`: netem installs on the
  *resolved* iface and heal removes it; **partition actually stops ping**
  and heal restores it; `stress-ng` really runs and heal's `pkill` really
  kills it; the F69 sweep clears a leaked fault.
- `PrometheusExporterTest` — against a real `prom/prometheus:v2.53.0`, the
  version the obs compose deploys, asserting **`ValidityChecker` can read
  what `PrometheusExporter` wrote**.
- `infra/probes/host-fault-tools-probe.sh` — the tc/iptables/pkill exit
  codes, measured on the campaign's OS.

**Still unproven, and unprovable locally:** sudo availability under the
cloud-init user, cloud-init timing, Hetzner private-net routing, and
`docker`/`sshd` stderr wording on the real image. Budget one session for
fallout; the expected shape is "adjust one probe string and its golden, TDD".

---

## 7. Merge runbook — `eighth-review-tier1` → `main`

**This is the target.** Read it fully before starting; it is short because
the topology is simple, and the topology is simple *by construction*.

### 7.1 The situation, verified 2026-08-15

```
main  9e9fbdd ────────────────────────────────────────────►  (has NOT moved)
        └── seventh-review-tier1  172d1f0   (7 commits, IDLE)
                └── eighth-review-tier1  e8561f6   (27 commits total)
```

- `eighth-review-tier1` is a **strict superset** of `seventh-review-tier1`
  — `git log seventh..eighth` is empty in the "unique to seventh"
  direction (verified: 0 commits). Merging eighth brings both.
- `main` has **not advanced** since the branch point, so the merge is
  **conflict-free by construction**, not by luck.
- Both worktrees are clean; both branches are pushed to `origin`.

### 7.2 The gate — all nine must hold before merging

Full detail in `GIT_WORKFLOW.md`; summarised so this runbook stands alone.

| # | Gate | How |
|---|---|---|
| 1 | **Suite green, count verified** | `cd harness && mvn21 clean verify` → BUILD SUCCESS with the count read from Maven's **`Tests run:` summary line**, ≥ 243. A suspiciously fast run (<4 min) is a FAILED gate until the count is confirmed — that is F76 |
| 2 | **Environment could run the tests** | Docker up; `docker images \| grep -E 'paxi:6823d0b\|hotstuff:dc01ac8'` shows both |
| 3 | **Findings ledgered with evidence** | §5 above + the archived ledger |
| 4 | **This file updated** | §0 date, §2 state, §6 queue |
| 5 | **TDD evidence stated** | each behaviour-changing commit names its red |
| 6 | **Goldens re-read if touched** | ⚠ **THEY WERE.** Queue #1 is a hard precondition of this merge |
| 7 | **No `terraform apply`** | `hcloud server list` empty / no `infra/` state change |
| 8 | **Doc authority sweep** | done — see §8 |
| 9 | **Branch pushed** | done |

> **Gate 6 is the one that blocks today.** The F69 host-sweep block, the
> derived `slow_node` timeout, and packet-loss severity all changed golden
> text after the ledger last called the goldens FINAL. **Do not merge before
> queue #1.**

### 7.3 The merge, step by step

```bash
# 0. From the eighth worktree, confirm the gate
cd ~/Downloads/cbt-eighth/harness
mvn21 clean verify                      # read "Tests run:" — expect >= 243
cd ~/Downloads/cbt-eighth
git status --short                      # must be empty

# 1. Confirm main has still not moved (if it HAS, see 7.5)
git fetch origin
git log --oneline eighth-review-tier1..origin/main   # expect: empty

# 2. Merge with --no-ff, so the review pass stays visible as a unit
git checkout main
git merge --no-ff eighth-review-tier1 -m "merge: eighth review — \
simulation specs, rules-as-record, confidence grading, full validity layer"

# 3. Re-verify ON main. Merging cannot break a test, but gate 1 is about
#    the tree you are about to publish, not the one you tested.
cd harness && mvn21 clean verify
cd ..

# 4. Publish
git push origin main

# 5. Retire the branches — eighth contains seventh, so both go together
git push origin --delete eighth-review-tier1 seventh-review-tier1
git branch -d eighth-review-tier1 seventh-review-tier1
```

### 7.4 Retiring the second worktree

The `seventh-review-tier1` worktree at `~/Downloads/consensus-bench-thesis`
has been idle since 2026-08-14 and its branch is fully contained in the
merge. **Do not delete it before the merge lands** — it is the only
independent copy if something goes wrong.

```bash
# AFTER step 4 above succeeds:
cd ~/Downloads/cbt-eighth
git worktree remove ~/Downloads/consensus-bench-thesis
git worktree list        # should show cbt-eighth only
```

Then decide where the canonical checkout lives. `~/Downloads/cbt-eighth` is
a working name for a review branch, not for the project — either rename the
directory or re-clone to a stable path and delete both. Whichever you pick,
update `GIT_WORKFLOW.md`'s "Local:" line, which currently names the old path.

### 7.5 If `main` HAS moved (it has not, but for next time)

`git merge origin/main` into the branch FIRST, resolve there, re-run the
full gate, then merge to main. Never resolve conflicts on `main` — a
half-merged `main` is the one state no one can reason about, and this
project's whole history is about not creating states like that.

### 7.6 What this merge delivers to `main`

27 commits. In one sentence each:

- **Correctness that would have corrupted data:** F70 (an overflowed event
  log can no longer pose as a measurement), F69 (a leaked netem/iptables
  fault can no longer outlive the JVM that injected it), F52 (a baseline
  that failed 49% of its ops is no longer `valid`), F53/D14 (two packet-loss
  severities no longer share one directory and one hash).
- **The validity layer, made real:** `ValidityChecker` wired in, the
  Prometheus exporter, harness self-metrics, the docker-events audit — 1 of
  10 gates evaluating became 9 of 11.
- **The evidence layer:** named simulations published as JSON, rules and
  false-positive triage written into every verdict, the per-simulation
  journal, GRADE-adapted cell grades, and `analyse.py` failing closed.
- **Confidence before the VMs:** fault effects proven on real containers,
  the export path proven against a real Prometheus, the host toolchain's
  exit codes measured on the campaign's own OS.

---

## 8. Document map

**One driver, many references.** This file is the driver. Everything else is
consulted, not planned from.

| Document | Role |
|---|---|
| **`PROJECT_STATE.md`** (this file) | **THE DRIVER** — decisions, issues, queue, merge plan |
| `MASTER_PLAN.md` | Decision rationale D1–D15 |
| `DATA_ANALYSIS_METHODOLOGY.md` | **Wins on methodology.** Statistics, validity gates, threats to validity |
| `IMPLEMENTATION_PLAN.md` | **Wins on acceptance criteria.** Milestones M0–M6, gates G1–G3 |
| `CAMPAIGN_RUNBOOK.md` · `EXECUTION_AND_COST_MODEL.md` | Campaign operations, phases, cost, retrieval protocol |
| `GIT_WORKFLOW.md` | Branch convention, the 9-item gate, concurrent-session protocol |
| `SIMULATION_AND_RULES_ANALYSIS.md` | Prior-art survey with citations + the S0–S5 design. **§9 is thesis material** — the answer to "why not Jepsen/Gatling?" |
| `METRICS_AND_SOURCES.md` · `OBSERVABILITY_AND_EXPECTATIONS.md` | Preregistered expectations and false-positive catalogue |
| `LOCAL_TESTING.md` · `PER_ALGORITHM_TEST_GUIDE.md` · `MONITORING_GUIDE.md` | Operator guides |
| `MEASUREMENT_DIAGRAMS.md` · `JAVA_HARNESS_ASSESSMENT.md` | Architecture and rationale |
| `examples/` · `SSH_SETUP.md` | Sample data, planned SSH switch |
| `archive/` | **History, not guidance.** Includes the full F1–F76 findings ledger |

**Authority when they conflict:** live code > `IMPLEMENTATION_PLAN` +
`DATA_ANALYSIS_METHODOLOGY` > this file > other docs > `archive/`. This file
wins on *what to do next*; those two win on *how it must be done*.

**Archived in this consolidation** (moved to `archive/`, not deleted):
`PENDING_TASKS.md` → `FINDINGS_LEDGER_F1-F76.md` (the evidence trail),
`WORK_QUEUE.md` and `HOW_TO_CONTINUE.md` (folded into §6 and §3),
`CONTINUATION_PROMPT.md` (superseded by this file being self-contained).

---

## 9. Working agreement

1. **Execute, don't assert.** If a claim can be compiled, run, or parsed,
   do it and show the evidence. Every "verified" in this file has a command
   behind it.
2. **TDD, strictly.** Failing test first, red for the *right* reason.
3. **One increment per session-step.** Stop at checkpoints with done /
   evidence / not-verified / next.
4. **Honest review every time.** Separate verified from assumed; name what
   was not tested. Skipped verification is this project's documented primary
   failure mode.
5. **Respect the gates.** G1 (done) / G2 (blocked on queue #1) / G3. Never
   advance on confidence — the evidence must exist.
6. **Never `terraform apply`** before G2. Laptop numbers are never thesis
   data.
7. **Update this file at session end.** It is the only thing a fresh session
   reads first.
