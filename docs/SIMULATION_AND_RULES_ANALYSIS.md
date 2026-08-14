# Simulation Control, Rules, and Result Confidence — Analysis and Plan

**Status: ANALYSIS + PLAN ONLY. No code in this document, by instruction.**
Written 2026-08-14 against the tree verified that day (`mvn21 clean verify`
→ **177/177 green, BUILD SUCCESS, 5:06**). Every "what exists today" claim
below carries a `file:line` or an executed command; every "what is missing"
claim was checked, not assumed.

Authority: this document is a PLAN. Per `CLAUDE.md`, live code wins over it,
and `IMPLEMENTATION_PLAN.md` + `DATA_ANALYSIS_METHODOLOGY.md` win on
acceptance criteria and methodology. Where this plan proposes changing those,
it says so explicitly and marks it an author decision.

---

## 0. The question that was asked

> Full control of the rules of each test; easily change a simulation for an
> algorithm and try again; clear and correct results stored and exported for
> each simulation, with exactly what happened; understand whether something
> was a false positive; a confidence level for how much we can trust the
> results and why. Correct collection *while the simulation runs*, and the
> right tools for offline analysis afterwards.

Restated as seven capabilities, which is how the rest of this document is
organised:

| # | Capability | One-line test of whether we have it |
|---|-----------|-------------------------------------|
| C1 | **Declarative simulation** — a named, versioned, reviewable definition of one experiment | Can I change the fault percentage for one algorithm without editing Java? |
| C2 | **Rules as data** — thresholds and gates declared, not buried in code | Can I print the exact rule set that judged run X? |
| C3 | **In-flight collection** — measurements captured correctly *while* the run happens | Does a finished run dir contain its own metrics without a live Prometheus? |
| C4 | **Per-simulation record** — what ran, what was applied, what was observed, what was decided | Is there one file per simulation that a reviewer can read end to end? |
| C5 | **False-positive attribution** — a failed check says *which known benign cause* could explain it | Does a red gate tell me "check CPU steal" or just "FAIL"? |
| C6 | **Confidence grading** — how far a number can be trusted, and why | Can I sort cells by trustworthiness with a stated rule? |
| C7 | **Offline analysis** — everything reproducible from the archive alone | Do the figures regenerate with the cluster destroyed? |

---

## 1. Do we have a rule engine? (direct answer)

**Yes, there is a project `CLAUDE.md`** — `/home/pdampani/Downloads/
consensus-bench-thesis/CLAUDE.md`, 42 lines. It is a *pointer* file, not a
rule engine: it names `PROJECT_STATE.md` as the source of truth, fixes the
authority order, restates the five-point working agreement, and carries two
hard safety rules (never `terraform apply`; laptop numbers are never thesis
data). It governs **how Claude works on the repo**, not how experiments are
judged. Note it also exists to stop sessions inheriting the unrelated
`~/Downloads/CLAUDE.md` (a different project) — that is its main job.

**No rule engine is used in the harness — and one already exists in spirit.**
There are four de-facto rule layers today, none of them declarative:

| Layer | Where | What it rules on | Form |
|---|---|---|---|
| Validity gates | `validity/ValidityChecker.java` (445 lines, 10 gates) | Is this run thesis data? | Java constants + hardcoded method calls |
| Goldens | `src/test/resources/goldens/*.txt` (7 files) | Is this the exact remote command sequence? | Verbatim text, matched by test — **this is the best rule layer in the repo** |
| Preregistration | `OBSERVABILITY_AND_EXPECTATIONS.md`, `METRICS_AND_SOURCES.md`, methodology §7 | What should we expect, and what is a known false positive? | Prose only — not machine-readable, not attached to any verdict |
| Claim framework | `DATA_ANALYSIS_METHODOLOGY.md` §6 | May this become a thesis conclusion? | Prose, four named gates, applied by hand |

The gap is not an *engine*. It is that three of these four layers are prose
or constants, and none of them writes its rule text into the run's output.

---

## 2. What exists vs what is missing, per capability

### C1 — Declarative simulation: **missing, and structurally blocked**

`MatrixRunner.block()` (`MatrixRunner.java:63-69`) hardcodes
`durationSecs=480, warmupSecs=180, window=200, valueSizeBytes=1024`, and
`specs()` (`:90`) hardcodes `faultAtSecs = warmup+60` and
`packetLossPercent = 30`. `Main.campaignRun`'s `requireKnownKeys`
(`Main.java:85-86`) accepts no `duration`, `warmup`, `window`, `valsize`,
`fault-at` or `loss` key at all.

Consequences already ledgered: **F53** (two `packet_loss` cells at different
percentages hash identically, so methodology §1's "any cell individually
reproducible" is false for that scenario) and **F71** (`CAMPAIGN_RUNBOOK.md`
§3 specifies failover trials at 180+180 with the fault at +60; the code runs
180+300 with the fault at +240 — the runbook's own budget table is not
executable).

So the answer to *"can I change the fault percentage for one algorithm
without editing Java?"* is **no**, and the answer to *"is the run shape the
runbook documents even reachable?"* is also **no**.

### C2 — Rules as data: **missing**

Every threshold is a Java constant carrying a "PROVISIONAL until M6.2"
comment: `LOADGEN_CPU_MAX = 0.70`, `STEAL_MAX = 0.01`,
`RATE_ADHERENCE_MIN = 0.99`, `CONVERGENCE_MAX_REL_DIFF = 0.20`,
`CLOCK_OFFSET_MAX_S = 0.005`, `FAULT_WINDOW_MS = 60_000`
(`ValidityChecker.java:57-69`). M6.2's job is to *fix them numerically from
pilot variance* — which today means editing and recompiling the checker, and
leaves no record of which values judged which run.

### C3 — In-flight collection: **the largest practical gap**

`ValidityChecker` is a **library that nothing calls.** Neither `RemoteRunner`
nor `MatrixRunner` invokes `check()`, so **no campaign run produces a
`validity.json` today.** `PrometheusExporter` (M5.4) does not exist, so no
run produces `metrics/*.csv` either.

Executed against a real committed run dir
(`harness/results/etcd/baseline/size1/rate300`):

```
valid=true
  rate_adherence      SKIP  saturation run (no target rate) — N/A
  window_headroom     SKIP  needs harness self-metrics (M5.3) — not yet exported
  convergence         PASS  tail 280.4 vs head 230.0 ops/s (18.0% diff)
  loadgen_cpu         SKIP  no metrics/ dir (PrometheusExporter M5.4 not run)
  loadgen_steal       SKIP  no metrics/ dir (M5.4 not run)
  node_cpu_steal      SKIP  no metrics/ dir (M5.4 not run)
  container_restarts  SKIP  docker-events restart audit not yet collected (P4.5)
  clock_discipline    SKIP  no metrics/ dir (M5.4 not run)
  fault_ground_truth  SKIP  baseline run (no fault injected) — N/A
  durability          SKIP  per-system correctness probe not implemented (P2.6)
```

**One gate of ten evaluated. Nine SKIPped. The run reports `valid: true`.**
(Fair reading: this is a v1 M0 manifest, so `rate_adherence` SKIPs as
"saturation"; on a current manifest with `rate_ops_s > 0` it would evaluate
too. The honest general statement is therefore **at most 2 of 10 gates can
evaluate on any run today** — `rate_adherence` and `convergence` — and
`fault_ground_truth` is legitimately N/A on baselines. The other seven wait
on M5.4/M5.3/P4.5/P2.6.)
The design is honest about this — SKIP is loud and reasoned, and the report
counts them — but "valid" currently means *"nothing we could check
objected"*, which is not what the word will mean to a thesis reader. Note
also that the one gate that ran passed at **18.0% against a 20% provisional
threshold**: a run still visibly ramping, waved through by a number nobody
has yet justified from data.

Three gates are unconditional SKIPs pending work that is already planned:
`window_headroom` ← M5.3, `container_restarts` ← P4.5's open half,
`durability` ← P2.6.

**Already-paid-for dependencies that would close two of these are declared
and unused:** `micrometer-registry-prometheus 1.14.5` is in the pom and
referenced **nowhere** in `src/` (that is M5.3's :9400 registry), and
`picocli 4.7.6` is in the pom and appears only in a comment
(`Main.java:23`, "M1.3 replaces this with picocli"). Both are shaded into
the 77 MB uber-jar today for zero benefit.

### C4 — Per-simulation record: **partial**

Per **run**: `throughput.csv`, `latency.csv`, `latency.hlog`,
`manifest.json` (v2 — params, environment, image digest, harness version,
`config_hash`, fault mark, failover, honest status), plus `logs/` on fault
runs. This is genuinely good and better than most published work.

Per **simulation** (= one system block, the campaign's real execution unit):
nothing. `MatrixRunner` writes only `campaign-log.jsonl`, and only on
*failure* (`MatrixRunner.java:151-162`). There is no artifact that says
"this block ran these 71 cells, in this seeded order, under these rules,
and here is the verdict on each".

Two known holes in the per-run record: the packet-loss percentage is absent
from both the manifest and `config_hash` (**F53**), and `EventLog`'s
dropped-event counter is absent entirely (**F70**).

### C5 — False-positive attribution: **exists as prose, unattached**

`OBSERVABILITY_AND_EXPECTATIONS.md` carries a real false-positive catalogue
per algorithm — "fsync p99 ×10 spikes = neighbour disk contention
(environment — gate 4, rerun)", "URP > 0 at baseline = the cluster formed
degraded — stop", "rounds ≫ height at fixed load = proposer timeouts, check
CPU steal and NIC before calling it protocol behaviour". This is exactly the
knowledge C5 needs. It is unreachable from a verdict: a `GateResult`'s
`detail` string never points at it.

The same is true in the test suite: **F68** is precisely a false-positive
problem (`KafkaPerfTestParityTest` red-lined twice under load average ~15,
then passed at 1.86× in isolation on the same tree; it passed again in this
session's run). The diagnosis rule exists — in the test's javadoc.

### C6 — Confidence grading: **does not exist, and must not be invented**

Nothing computes it. The temptation is a 0–100 "confidence score"; that
would be pseudo-science in a thesis and I recommend refusing it.

The defensible version already exists in prose: **methodology §6's
four-gate claim framework** — a comparative claim enters the conclusions
chapter only if it is *estimated* (effect-size CI excludes the null),
*mechanistically consistent*, *directionally consistent with published
results*, and *survives its caveats*. That is an ordinal evidence grade with
stated criteria, in the author's own methodology, anchored to Hoefler &
Belli's SC'15 rules. C6's real job is to **mechanize §6**, not to invent a
score.

### C7 — Offline analysis: **foundation exists, fails open**

`analysis/analyse.py` does per-cell bootstrap CIs, percentile *spreads*
(never averaged — methodology §3), listed exclusions, `--selftest` green.
`observability/offline/` replays a Prometheus snapshot on the same
dashboards; `scripts/collect_block.sh` collects and count-verifies
pre-destroy.

But **F54**: the honesty rules fail *open* on fields a v1 manifest lacks.
Re-executed today against the committed M0 tree: **2 included / 0 excluded**,
reporting **229.9 ops/s** for the run `PROJECT_STATE` records at 306.6 —
because a missing `duration_secs` keeps the drain tail and a missing
`environment` is not `"local"`. Laptop data enters the analysis silently.
M6.4 (pooled histograms, Holm correction, ECDFs, the 8 figures) is open.

---

## 3. Prior art surveyed — what to adopt, what to steal, what to refuse

Battle-tested in academia and industry, assessed against *this* repo rather
than in the abstract.

| Prior art | What it does well | Verdict here |
|---|---|---|
| **Jepsen** (Kyle Kingsbury; the reference for distributed-systems correctness testing) | Control node → SSH → db nodes; `generator` schedules ops *and* faults; `nemesis` is a first-class fault process; `checker` reduces a recorded history against a model and **points at the specific operations that failed**; per-test report directory | **Architecturally we already are a Jepsen.** `WorkloadEngine`≈generator, `SshFaultInjector`≈nemesis, `ValidityChecker`≈checker, loadgen VM≈control node. Do NOT adopt (Clojure; its checkers target linearizability, not throughput/latency distributions). **Steal**: the fault schedule belongs *in the simulation spec* next to the workload, not in a separate CLI flag; and a checker must name the offending observation, not just fail |
| **Chaos Toolkit** (declarative chaos engineering) | `experiment.json` with a **steady-state hypothesis** evaluated *before and after* the method, per-probe `tolerance`, `method`, `rollbacks`; and a **run journal** recording `experiment` (verbatim), `status`, `start`/`end`/`duration`, `deviated`, `steady_states.before/after` each with `steady_state_met` + per-probe `tolerance_met`, `run[]`, `rollbacks[]` | **Steal the journal wholesale.** It is exactly C4's missing artifact, and `deviated` + before/after steady-state is exactly the right shape for a fault run (baseline holds → inject → does it recover?). Our `heal()` is already `rollbacks`. Do not adopt the tool (Python, k8s-oriented, cannot drive our drivers) |
| **Gatling** (`Simulation` + `setUp().assertions(...)`) | A simulation is a first-class, versioned, code-declared artifact; `assertions` on global stats (`global.responseTime.percentile(99).lt(250)`, `global.failedRequests.percent.lte(1)`) **fail the build**; an HTML report per run | **Steal two ideas, refuse the framework.** Ideas: (a) the simulation is a named artifact, not CLI arguments; (b) assertions are declared *with* the simulation and their failure is the run's verdict. Refuse adoption: Gatling is an HTTP/JMS/gRPC load DSL on Akka/Netty with no notion of "completes only on consensus commit" — adopting it means rewriting every driver inside a foreign framework, losing the coordinated-omission contract that is pinned by test, and taking on Scala. That fails Karpathy #2 and #3 and discards 177 green tests |
| **OpenMessaging Benchmark** (Linux Foundation; the framework behind published Kafka-vs-Pulsar studies) | Splits `driver-*.yaml` (how clients behave) from `workloads/*.yaml` (what they do); "you just ran a custom workload without touching a line of code" | **Closest domain-matched precedent for C1.** Confirms the split is the right axis: *driver config* and *workload* are separate documents. Do not adopt the code |
| **YCSB** (`workloada`…`workloadf` property files) | The canonical proof that a benchmark's workload belongs in a named, publishable, citable file | Same lesson as above. Our methodology already cites Paxi's Table 3 keyspace — a published spec file is what makes that citable in reverse |
| **Great Expectations** (expectation suites → validation results → human-readable "Data Docs") | Rules are a named *suite*; every validation emits a result document rendered for humans; the suite and the result live together | **Steal the vocabulary and the artifact pairing**: rule *suite* (versioned, published) + validation *result* (per run, references the suite). Do not adopt (Python, dataframe-oriented) |
| **AWS Deequ** | Code-first checks/constraints producing metric-level results on Spark | Reject — Spark-oriented, no fit |
| **Drools / Easy Rules** (Java rule engines) | RETE, DRL, decision tables; rules authored separately from code by non-developers | **Refuse.** Rule engines earn their overhead when non-developers change rules often and rule *interaction* is combinatorial. Here: ~15 rules, one author, changing rarely, each a pure function over a run directory. The literature's own caution applies verbatim — "a rules engine may be a hammer, but not every system is a nail". Adding DRL to a thesis whose credibility rests on a reader seeing exactly what was checked is a net loss |
| **JMH** (annotated benchmark classes) | Compile-time-declared benchmarks, machine-readable JSON output | Not applicable (JVM microbenchmarks) but confirms the Gatling lesson: **specs as typed code, results as machine-readable documents** |
| **Change-point detection literature** (Daly et al., MongoDB, ICPE'20; Mozilla's 25-method study) | The industrial answer to "is this a real regression or noise", with explicit false-positive accounting and p-value tuning | **Do not build this** — it needs a long time-series across builds, which a one-shot thesis campaign does not have. But it validates C5/C6's framing: the field's consensus is that automated verdicts *must* ship with false-positive triage, because a large share of automatically detected changes are not actionable |
| **Hoefler & Belli, SC'15** ("twelve ways to tell the masses") | Report distributions not bare means; report variability; do not cherry-pick; state everything needed to reproduce | Already the methodology's backbone. Relevant here as the **acceptance standard for C4/C7**: the per-simulation record is what makes "state everything needed to reproduce" checkable rather than aspirational |

**Summary verdict: adopt no framework; steal four concrete artifacts** — the
Chaos Toolkit *journal*, the Gatling *simulation + assertions* pairing, the
Great Expectations *suite ↔ validation result* pairing, and the
OpenMessaging *driver/workload split*. Every one of them is expressible in
the code that already exists, in typed Java, with tests.

### 3.1 Where each borrowed idea actually lands

Traceability, so a later session sees *why* an increment is shaped the way
it is instead of re-deriving it — or re-running the survey:

| Borrowed from | The specific idea | Lands in |
|---|---|---|
| Gatling | A simulation is a **named artifact**, not CLI arguments | **S1.2** |
| Gatling | Assertions declared **with** the simulation; their failure is the verdict | **S4.3** + **S2.1a** |
| Chaos Toolkit | The **run journal**: `status`, `start`/`end`/`duration`, `deviated`, per-activity results, `rollbacks` | **S4.1** |
| Chaos Toolkit | **Steady state checked before AND after** the method | **S4.1**'s `deviated` — baseline holds → inject → does it recover? |
| Chaos Toolkit | `rollbacks` as a first-class record | **S4.1** — `heal()` already is this, it just isn't recorded |
| Great Expectations | **Suite ↔ validation-result pairing**, both human-readable | **S2.1a** + **S4.2** |
| OpenMessaging / YCSB | The workload is a **published, citable** document | **S1.2**'s `simulation.json` (D12's serialization half) |
| Jepsen | The **fault schedule belongs in the spec**, beside the workload | **S1.1** |
| Jepsen | A checker must **name the offending observation**, not just fail | **S2.2** |
| Change-point literature | Automated verdicts **must** ship with false-positive triage | **S2.2** — why it exists at all |
| Hoefler & Belli | "State everything needed to reproduce" is an **acceptance standard**, not an aspiration | **S4.1/S4.2** are what make it checkable |

### 3.2 Sources

Recorded here rather than left in a session transcript: an unsourced survey
cannot be cited, and §9 argues this section is thesis material.

- Gatling assertions: <https://docs.gatling.io/concepts/assertions/>
- Jepsen docs: <https://jepsen-io.github.io/jepsen/index.html> · nemesis
  tutorial: <https://github.com/jepsen-io/jepsen/blob/main/doc/tutorial/05-nemesis.md>
- Chaos Toolkit journal: <https://chaostoolkit.org/reference/api/journal/> ·
  experiment API: <https://chaostoolkit.org/reference/api/experiment/>
- OpenMessaging Benchmark: <https://openmessaging.cloud/docs/benchmarks/>
- YCSB core properties: <https://github.com/brianfrankcooper/YCSB/wiki/Core-Properties>
- Great Expectations / Deequ / Soda comparison:
  <https://branchboston.com/great-expectations-vs-deequ-vs-soda-data-quality-testing-tools-compared/>
- Java rule engines (Drools, Easy Rules): <https://www.baeldung.com/java-rule-engines>
- Hoefler & Belli, *Scientific Benchmarking of Parallel Computing Systems:
  twelve ways to tell the masses*, SC'15:
  <https://dl.acm.org/doi/10.1145/2807591.2807644> — **already in `corpus/`
  and cited by methodology §3**
- Daly et al., *The Use of Change Point Detection to Identify Software
  Performance Regressions in a CI System*, ICPE'20:
  <https://dl.acm.org/doi/10.1145/3358960.3375791> (preprint
  <https://arxiv.org/abs/2003.00584>)
- **Guyatt et al., *GRADE: an emerging consensus on rating quality of
  evidence and strength of recommendations*, BMJ 2008;336(7650):924–926:
  <https://doi.org/10.1136/bmj.39489.470347.AD>** — the parent scheme D13's
  grading is adapted from (§3.3)
- **Nosek, Ebersole, DeHaven & Mellor, *The preregistration revolution*,
  PNAS 2018;115(11):2600–2606:
  <https://doi.org/10.1073/pnas.1708274114>** — the citation for the
  expected-vs-observed framework, and for why post-hoc prediction changes
  (HARKing) void it (§3.3)

Surveyed 2026-08-14. Versions are deliberately not pinned: nothing here is
adopted as a dependency, so the citations support *design decisions*, not a
build.

### 3.3 The two open gaps — now CLOSED with citations

Both were closed 2026-08-14. Neither is decorative: each changed something.

**1. D13's grading is adapted from GRADE, not invented.**
Guyatt et al., *GRADE: an emerging consensus on rating quality of evidence
and strength of recommendations*, BMJ 2008;336(7650):924–926
(<https://doi.org/10.1136/bmj.39489.470347.AD>). GRADE rates a body of
evidence on four ordinal levels (high / moderate / low / very low) and
downgrades for **imprecision, inconsistency, indirectness, risk of bias and
publication bias** — that is, an ordinal grade justified by *named
downgrade reasons*, which is exactly D13's "the letter always ships with
the list".

Two things this citation **changes**, not merely supports:

- **The grade attaches to a CELL, not a run.** GRADE is explicit that
  quality is assessed for a *body of evidence*, never a single study. Our
  unit of evidence is the cell (n=5 runs), so S4.3 grades the cell and a
  single run only contributes to it. This resolves an ambiguity the plan
  had left open.
- **Our downgrade reasons should map onto GRADE's, and mostly already do**:
  *imprecision* ← a bootstrap CI that fails to exclude the null;
  *inconsistency* ← per-run spread / the CoV stability check;
  *indirectness* ← the D9 hardware seam and the HotStuff log-derived
  metrics; *risk of bias* ← a FAILed or SKIPped validity gate. The one
  GRADE reason with no analogue is publication bias, which does not apply
  to a single-author campaign that reports every cell — worth saying in the
  thesis rather than silently dropping.

Naming the parent scheme also fixes the letters: **A/B/C/VOID stays**, but
it is described as *adapted from GRADE with campaign-specific downgrade
criteria*, and VOID is our addition (GRADE has no "this evidence cannot
evidence its own claim" level, because F50/F70's failure mode does not
arise in a literature review).

**2. Preregistration is a cited practice, not a house style.**
Nosek, Ebersole, DeHaven & Mellor, *The preregistration revolution*, PNAS
2018;115(11):2600–2606 (<https://doi.org/10.1073/pnas.1708274114>). The
argument this project already relies on, stated by its standard source:
preregistration separates **prediction from postdiction**, constrains
researcher degrees of freedom, and guards against **HARKing** (hypothesising
after results are known) and p-hacking.

What it changes: the D14 note — *"changing a prediction after seeing data
would void the preregistration"* — stops being a house rule and becomes the
citable definition of HARKing. `DATA_ANALYSIS_METHODOLOGY.md` §1/§7 and
`OBSERVABILITY_AND_EXPECTATIONS.md` should cite it where they describe the
expected-vs-observed framework, since that framework *is* preregistration
under another name.

Neither blocked an increment. Both are recorded so they are not discovered at
the viva instead. — ALL THREE MADE BY THE AUTHOR 2026-08-14

> **D12 = Java records + serialized JSON** (option C below).
> **D13 = ordinal grade mechanizing methodology §6.**
> **D14 (F53) = sweep packet loss at BOTH 5% and 30%.**
>
> All three are now locked in `MASTER_PLAN.md` §1 and echoed in
> `PROJECT_STATE.md` §6; D14 also amended `DATA_ANALYSIS_METHODOLOGY.md` §1
> (six factors, not five) and preregistered both expectations in
> `METRICS_AND_SOURCES.md`. The options below are kept as the decision
> record — what was chosen, against what, and why.

### D12 — Are simulation specs **Java records** or a **parsed file**? → **RECORDS + JSON**

This is the sharpest tension in the whole proposal, because the rebuild's
founding lesson is that *typed Java kills the string-surgery bug class at
compile time*, and a YAML spec file reintroduces stringly-typed config —
the exact thing that killed v6.

| Option | For | Against |
|---|---|---|
| **A. Java records** (Gatling/JMH model) — a `Simulations` class of named constants | No parser, no schema, no drift; a typo is a compile error; `MatrixRunner.Block` already IS this shape, just hardcoded; ~zero new code | Changing a simulation needs a rebuild (~30 s); not copy-pasteable into a thesis appendix as-is |
| **B. YAML/JSON file** (OpenMessaging/YCSB model) | Publishable and citable; rerun with no rebuild; readable by non-Java reviewers | A new parser, a new schema, a new failure mode; reintroduces the v6 class unless the parse is fail-closed and golden-tested (which `Inventory.java` shows we can do, but it is real work) |
| **C. Recommended — A plus serialization** | Specs are Java records; the runner **serializes the fully resolved spec to `simulation.json`** in the results tree and hashes it into every manifest | Gets B's publishable artifact with none of B's parsing risk. Same discipline as the goldens: the written text is the reviewable spec |

**Recommendation: C.** It is strictly less code than B, keeps the type
safety the project was rebuilt for, and still produces the file the thesis
appendix needs. If the author later wants file-driven reruns, B can be added
on top of C's schema without redesigning anything.

### D13 — Where is **confidence** anchored? → **ORDINAL GRADE FROM §6**

| Option | Assessment |
|---|---|
| A numeric score (0–100) | **Refuse.** Unjustifiable in a thesis; invites false precision |
| An ordinal grade from methodology §6's four claim gates + validity-gate coverage | **Recommended.** Already the author's own methodology, already anchored to Hoefler & Belli, already prose-complete. Mechanizing it adds no new science, only bookkeeping |
| Nothing — keep it human | Defensible, but then C6 is not delivered and the pilot's threshold-setting stays manual |

**Recommendation: the ordinal grade**, with the grade rule published in the
thesis and stored in every simulation report. Concretely — grades assert
*evidence coverage*, never correctness: e.g. **A** = every applicable
validity gate EVALUATED and PASS, n complete, CI excludes null, expectation
matched; **B** = as A but ≥1 gate SKIP (name them); **C** = a gate FAILed or
n incomplete → observation only, never a conclusion; **VOID** = the run
cannot evidence what it claims (F50/F70 class). The letters are shorthand
for *which criteria were met*, and the report always lists them.

---

## 5. Target design in one page (no code)

What one **simulation** (one system block) produces after this work:

```
results/<system>/
├── simulation.json          the RESOLVED spec, verbatim (D12-C) — workload,
│                            fault schedule, run shape, seed, rule-suite id
├── rules-<suiteVersion>.json the rule suite IN FORCE: every gate, its
│                            threshold, its source (§ reference), its known
│                            false-positive causes
├── journal.json             the Chaos-Toolkit-shaped run record:
│                            status · start/end/duration · deviated ·
│                            steady_state before/after · per-cell verdicts ·
│                            rollbacks (heal) · seeded order actually executed
├── report.md                the human read: what ran, what was applied,
│                            what was observed, what was decided, and WHY —
│                            with each gate's false-positive note inline
└── <scenario>/size<N>/[c<pct>/]<runId>/
    ├── throughput.csv · latency.csv · latency.hlog   (unchanged)
    ├── manifest.json        + loss_percent, events_dropped, simulation_hash
    ├── metrics/*.csv        (M5.4 — the 23 export queries, per run)
    └── validity.json        + confidence grade + per-gate FP attribution
```

Nothing in that tree is new *technology*. It is: one serializer, one report
writer, one rule-suite file, three already-planned collectors (M5.3, M5.4,
P4.5), and the existing `ValidityChecker` fed properly.

---

## 6. The plan — tangible increments

Rules for every increment below, non-negotiable (Karpathy + the project's
working agreement): **one increment per session-step; the failing test is
written first and shown red for the right reason; minimum code that passes;
touch only what the increment names; `mvn21 clean verify` green before the
commit; stop at the checkpoint with done / evidence / not-verified / next.**
Every acceptance below is **laptop-executable** — no increment may be
"verified" only on VMs, because shipping-unverified is how v6 died.

### Ordering constraint (author's call, stated up front)

S0 items change the **goldens** or the **manifest**, and the goldens are
FINAL text for G2. Doing them *after* the G2 read-through means reading the
goldens twice. Doing them *before* delays G2 by roughly two session-steps.
**Recommendation: do S0 first** — it is two cheap increments, and both fix
correctness bugs that would otherwise contaminate the canary.

---

### S0 — Close the two correctness holes that corrupt the record (before G2)

**S0.1 — F70. DONE (TDD red→green, 183 green.)**
*Why*: proven by execution — a saturation fault run that overflows before
the mark writes `fault_injected_at_ms` set, `failover_ms: null`,
`status: complete`, which reads downstream as "never recovered" and silently
drops the fastest trials from the F4 ECDF.
*Deliverable*: `events_dropped` in the manifest; a decision (author's) on
whether `CsvResultsWriter` refuses `complete` or `ValidityChecker` gains an
`event_log_integrity` gate — the F50 precedent argues for the writer, so
resume/validity/analysis read one truth.
*TDD acceptance (red first)*: a `CsvResultsWriter` test with an EventLog
whose capacity is exceeded before the mark — today it writes
`status: complete`; it must write the honest status and carry the drop
count. Plus a `RemoteRunner` test pinning that capacity is derived from the
run shape, not a constant.
*Deps*: none. *Laptop-verifiable*: yes, pure unit.

**S0.2 — F69: sweep HOST fault state at `start()`** (already specified as
NEXT-9). Goldens updated FIRST as the spec.
*Why*: a netem qdisc surviving a killed JVM shapes every later run on that
VM — the F29 stationarity class, host half.
*TDD acceptance*: golden written first, provider matches verbatim; a test
pins the sweep runs on EVERY provisioned node, and that "nothing to undo"
exits are benign while a real failure fails closed.
*Deps*: author's sign-off on the exact destructive commands.

---

### S1 — C1: the simulation becomes a first-class artifact

**S1.1 — Lift the welded inputs into the `Block`, AND make severity part of
run identity.** These are one increment, not two — see the warning below.
*Why*: F53/**D14** + F71 — a block cannot vary packet-loss, and the
runbook's own failover-trial shape is unreachable.
*Deliverable*: `Block` gains the per-scenario run-shape and fault
parameters (including a **list** of loss severities, since D14 sweeps 5% and
30%); `MatrixRunner.specs()` stops hardcoding `+60` and `30`;
`loss_percent` enters the manifest **and** `configHash`'s canonical string;
and run identity gains the severity dimension.

> ⚠ **The identity half is not optional, and D14 is why.** `RunIdentity.dir()`
> is `<system>/<scenario>/size<N>[/c<pct>]/<runId>`, and `MatrixRunner`'s
> runId is `rate<R>r<NN>`. Nothing in either encodes severity — so a 5% and
> a 30% `packet_loss` cell resolve to the **same directory** and the **same
> `config_hash`**. While the percentage was a hidden constant that was a
> latent ambiguity; the moment we sweep it, it becomes an **active
> overwrite** — the second cell silently replaces the first, and
> `alreadyComplete` skips it on resume. That is precisely the v6
> path-collision class that `RunIdentity`'s own javadoc claims is now
> inexpressible. Sweeping severity without shipping identity in the same
> increment makes the data worse than leaving it hardcoded.
>
> Two shapes, author's call at implementation time: a **path segment**
> (`.../packet_loss/size3/loss5/<runId>`, mirroring how `c<pct>` handles the
> D7 conflict ratio — consistent, and conflict already proved the pattern)
> or a **runId component** (`rate1000loss5r01`, mirroring how rate lives in
> the runId because it is not a path segment). The path segment is the
> closer precedent and reads better in the results tree.

*TDD acceptance (red first)*: **three** tests, and the first two must be
seen red together —
(a) two `packet_loss` specs at 5% and 30% produce **different**
`config_hash` values (today: identical — F53 in one assertion);
(b) the same two specs produce **different result directories** (today:
identical — the D14 collision);
(c) a `LEADER_KILL` block can express the runbook §3 shape (180 + 180, fault
at +60), which is unreachable today (F71).
*Deps*: none remaining — D14 is decided. *Laptop-verifiable*: yes, pure
unit + `--dry-run`.
*Also update in this increment*: the `etcd-size3-faults.txt` golden's
`PACKET_LOSS` block, which hardcodes `netem loss 30%`, must show the
severity as a parameter. It needs re-reading for F69 anyway — do both before
the G2 sign-off, not after.

**S1.2 — Named simulations + serialization (D12 option C).**
*Deliverable*: a `Simulations` holder of named, typed block constants (one
per system, plus the failover-trial variant); `MatrixRunner` writes the
resolved spec to `simulation.json` and folds its hash into every manifest.
*TDD acceptance (red first)*: run the same named simulation twice → byte-
identical `simulation.json` and identical hash; change one field → hash
changes (the `config_hash` test's proven pattern, one level up).
*Deps*: S1.1. *Laptop-verifiable*: yes — `--dry-run` already exists and
needs no VMs.

**S1.3 — `campaign-run` exposes the named simulation.**
*Deliverable*: `--simulation <name>` selects it; existing flags override
individual fields, still fail-closed via `requireKnownKeys` (F32).
*Note*: this is the natural moment to spend **picocli 4.7.6**, which is
already in the pom and shaded into the jar for nothing (M1.3). It is not
required — but if M1.3 is ever going to happen, doing it while touching the
CLI costs least.
*TDD acceptance*: the existing `--dry-run` parser pin extended — an unknown
simulation name fails closed naming the valid set.

---

### S2 — C2 + C5: rules become data, with their false-positive causes attached

**S2.1 — Make the rules in force part of the record.** Two sizes; do the
small one first and only escalate on evidence.

*Why*: M6.2's job is to fix thresholds from pilot variance; today that
means recompiling, and no run records which values judged it.

**S2.1a (minimum, recommended first).** Keep the thresholds as Java
constants — but **write them, and each gate's methodology § reference and
known false-positive causes, into `validity.json` itself.** A reader of any
run then sees exactly which rule, at which value, reached which verdict. No
new file, no parser, no schema.
*TDD acceptance (red first)*: a run's `validity.json` must contain the
numeric threshold each gate applied — today it contains only the verdict
text. Second: the existing contract test that pins every consulted metric
name against `export_queries.txt` (the test that caught F40) is extended so
a gate without a declared threshold + § reference fails the suite.

**S2.1b (only if S2.1a proves insufficient).** Externalise the suite to a
versioned resource so M6.2 can retune without a recompile, with the suite id
recorded per run.
*Trigger to escalate*: the pilot actually needs threshold changes faster
than a 5-minute rebuild allows, or a reviewer needs to diff rule sets across
campaign phases. Absent that trigger, S2.1a is the whole job.
*Karpathy check on myself*: my first draft of this plan jumped straight to
S2.1b — an external rule suite — which is a file, a schema, and a parser
bought for a benefit (`no recompile`) that nobody has yet needed. That is
exactly the speculative abstraction the working agreement forbids. S2.1a
delivers the capability the author actually asked for ("print the exact rule
set that judged run X") at a fraction of the cost. Either way: **no rule
DSL** — gate logic stays typed Java under test.

**S2.2 — Verdicts carry their false-positive triage.**
*Deliverable*: a FAIL or a marginal PASS emits the candidate benign causes
from the suite ("fsync p99 ×10 spikes = neighbour disk contention — check
gate 4, rerun before concluding").
*TDD acceptance (red first)*: a synthetic run that trips `node_cpu_steal`
must produce a verdict naming the environment-attribution path; today it
produces a bare threshold message.
*Bonus, same mechanism*: **F68**'s pressure-diagnosis rule (currently in a
test javadoc) becomes machine-readable — the unstable parity gate reports
"observed load average N; band is laptop-scoped; rerun in isolation before
believing a regression" instead of just going red.

---

### S3 — C3: collect correctly while the simulation runs

**S3.1 — `PrometheusExporter` (M5.4, already planned).** Runbook §5:
`query_range`, step 5 s, ±15 s padding, the **23** queries → `metrics/*.csv`
per run.
*TDD acceptance*: against a local Prometheus (a compose file already exists
under `observability/`), a run dir becomes self-contained; a query that
returns an empty series must produce a file that makes the gate FAIL, not a
missing file that makes it SKIP — that distinction is methodology §4's
meta-rule and is the whole point.

**S3.2 — Wire `ValidityChecker` into the run flow.**
*Why*: this is the single highest-value line of code in the plan. Today
**no campaign run produces a validity.json at all.**
*TDD acceptance*: a `RemoteRunner` test asserting the run directory contains
`validity.json` after a cell completes — currently absent.
*Deps*: S3.1 for the metric gates; but the wiring itself does not wait for
it, and should not.

**S3.3 — Harness self-metrics on :9400 (M5.3).** Unlocks the
`window_headroom` gate — one of the three unconditional SKIPs.
*Note*: **micrometer-registry-prometheus 1.14.5 is already in the pom and
used nowhere.** This increment spends a dependency already paid for.
*TDD acceptance*: the in-flight gauge is visible during a `local-run` and
the gate flips from SKIP to an evaluated verdict.

**S3.4 — docker-events audit (P4.5's open half).** Unlocks
`container_restarts` and gives paxi/hotstuff the gate-3 kill witness they
structurally lack.
*TDD acceptance*: the fixture test already specified in P4.5 — a restart
appears in the events audit.

---

### S4 — C4 + C6: the per-simulation journal, report, and grade

**S4.1 — The journal.** Chaos-Toolkit-shaped, written by `MatrixRunner`:
`status`, `start`/`end`/`duration`, the executed seeded order, per-cell
verdict, `deviated`, and the heal/rollback record. Extends
`campaign-log.jsonl` from failures-only to every cell.
*TDD acceptance (red first)*: a block whose cells partly fail produces a
journal listing **every** cell with its verdict — today only failures are
recorded, so a reader cannot tell "skipped on resume" from "never
attempted".

**S4.2 — The report.** `report.md` per simulation: what ran, the rules in
force, what was observed, what was decided, why — the Great Expectations
"Data Docs" idea at thesis scale, generated, never hand-written.
*TDD acceptance*: golden-file test on a synthetic block — the report is
reviewable text, so it is golden-testable exactly like the SSH sequences.

**S4.3 — The confidence grade (D13).** Mechanize methodology §6: grade each
*cell*, publish the grade rule, store it in `validity.json` and the report.
*TDD acceptance (red first)*: table-driven — a cell with all gates PASS and
n=5 grades A; the same cell with one gate SKIP grades B and names it; a
VOID (F50/F70 class) run can never grade above VOID regardless of how clean
its numbers look. That last case is the one that matters.

---

### S5 — C7: offline analysis fit for the thesis

**S5.1 — F54: `analyse.py` must fail closed** (already specified as NEXT-8).
Verified target: the committed M0 tree goes from **2 included / 0 excluded**
to **0 included / 2 excluded**.

**S5.2 — Consume the new record.** `analyse.py` reads `validity.json` and
excludes on grade, listing the reason (never silently); it must also stop
dropping `None` failovers without an exclusion line (the F70 pathway).
*TDD acceptance*: extend `--selftest`'s synthetic tree with a VOID run and
a `failover_ms: null` fault run; both must appear in `excluded.csv`.

**S5.3 — M6.4 proper**: pooled `.hlog` histograms (never averaged
percentiles), Holm–Bonferroni across the comparison family, ECDFs, the 8
figures — regenerating from the archive alone, which is
`IMPLEMENTATION_PLAN.md`'s definition of done.

---

## 7. What this plan deliberately does NOT do

- **No new framework, language, or runtime.** No Gatling, no Drools, no
  Jepsen, no Chaos Toolkit. Four artifacts are copied; zero dependencies are
  added. Two already-declared, currently-unused dependencies get spent.
- **No rule DSL.** Thresholds and text become data; gate logic stays typed
  Java under test.
- **No numeric confidence score.** Ordinal grades from the author's own §6
  criteria, or nothing.
- **No change-point detection / regression-history machinery.** A one-shot
  campaign has no build-over-build series for it to work on.
- **No re-architecture of the engine, drivers, providers, or goldens.**
  177 green tests stay green; the seven goldens change only for F69, which
  needs an author sign-off anyway.

## 8. Honest review of this plan

**What it gets right.** It refuses every framework it surveyed, and the
refusals are argued from this repo's constraints rather than from taste. The
four things it does adopt are artifacts, not code, so they cost no
dependency and no lock-in. It anchors confidence in the author's existing
methodology §6 instead of inventing a metric, which is the difference
between a thesis contribution and a gimmick. Every increment is
laptop-verifiable, which is the specific discipline v6 lacked.

**What is genuinely uncertain.**

1. **Much of this is already in the plan under other names.** Counted
   honestly: 8 of the 17 increments are existing work re-cut around the
   seven capabilities (S0.2=NEXT-9, S1.3=M1.3, S3.1+S3.2=M5.4, S3.3=M5.3,
   S3.4=P4.5, S5.1=NEXT-8, S5.3=M6.4) — and they are the *largest* ones, so
   by effort the share is higher than by count. The genuinely new items are
   S0.1 (F70), S1.1–S1.2 (varying + serialized simulations), S2.1–S2.2
   (rules and false-positive text in the record), S4.1–S4.3 (journal,
   report, grade) and S5.2. The honest framing is **"finish and restructure
   what is planned, plus six new artifacts"**, not "build a new subsystem".
   If the re-cut conflicts with `IMPLEMENTATION_PLAN.md`'s milestone order,
   the plan wins on acceptance criteria and the author decides the order.

2. **S4.2's report is the item most likely to become busywork.** A
   generated `report.md` is only worth its code if the author actually reads
   it instead of the CSVs. If after the pilot it is not being read, delete
   it — the journal is the load-bearing artifact, the report is a
   convenience.

3. **The confidence grade can become theatre.** A grade is only as good as
   the gates behind it, and today **one of ten gates evaluates**. Grading
   before S3 lands would produce confident-looking letters backed by nine
   SKIPs. **S4.3 must not ship before S3.1–S3.2.** That ordering is the
   plan's load-bearing constraint, and the temptation to demo the grade
   early is exactly the "gates holding under impatience" risk
   `IMPLEMENTATION_PLAN.md`'s own honest review names as its deepest
   assumption.

4. **The 20% convergence threshold passed a visibly-ramping run at 18.0%.**
   No threshold in the suite is yet justified by data — that is M6.2's job,
   and until the pilot runs, every grade this plan produces is provisional
   in exactly the way the constants already say they are. Making thresholds
   data (S2.1) does not make them right; it only makes them recorded and
   changeable without a recompile.

5. **Cost of doing nothing is not zero, but it is not catastrophic either.**
   The campaign can run without S1–S5; it would produce per-run CSVs and
   manifests, and validity would be judged by hand from prose. The
   thesis-grade argument for doing this work is C4 + C7: Hoefler & Belli's
   "state everything needed to reproduce" is checkable only if the record
   exists as an artifact. The engineering argument is F50 and F70 — two
   proven cases where a run that could not evidence its own claim was
   written as data. Those are the reason to believe a third such case
   exists and has not been found yet.

6. **Unverified assumption:** that the campaign's real rates stay under the
   ~8.3k ops/s where F70's buffer starts dropping. Nobody has measured
   saturation on the target hardware — the pilot settles it, and S0.1 makes
   it not matter.

---

## 9. §3 is thesis material — do not leave it as an engineering note

The survey in §3 answers a question an examiner is **likely** to ask, and
which the thesis does not currently answer anywhere:

> *"Jepsen, Gatling and the OpenMessaging Benchmark already exist. Why did
> you build your own harness?"*

Today the answer lives only in this planning doc. It belongs in the thesis
as a short subsection — methodology chapter, or related work — and it is a
strong answer rather than a defensive one, because it is specific:

- **Jepsen** is the closest match and the honest framing is that this
  harness *is* Jepsen-shaped (control node → SSH → nodes; generator,
  nemesis, checker). It was not adopted because its checkers answer a
  different question — linearizability/safety — whereas this thesis measures
  throughput, latency distributions and failover time. Saying that out loud
  is stronger than not mentioning Jepsen at all.
- **Gatling / OpenMessaging / YCSB** all assume the client's notion of
  "operation complete" is transport-level. This thesis's entire measurement
  contract is that an operation completes **only on consensus commitment**,
  with per-system semantics (Kafka's acks=all send callback, jetcd's async
  put, CometBFT's `broadcast_tx_commit`, Paxi's `Ballot` header). That
  contract is the reason the harness exists at all — it is the fix for the
  original probe flaws — and no general-purpose load tool encodes it.
- **Rule engines** were considered and refused on complexity grounds, which
  is itself a methodology-chapter-worthy statement about keeping the
  validity layer auditable.

Two consequences for how this document is treated:

1. **Keep §3.2's citations current.** They are the bibliography for that
   subsection.
2. **Close §3.3's two gaps before the viva** — an invented grading scheme
   and an uncited preregistration practice are exactly the surfaces an
   examiner probes, and both are one citation each.

Not an increment: no code, no test. Recorded here so the argument is not
reconstructed from memory a year from now.
