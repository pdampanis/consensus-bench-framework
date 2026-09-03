# PROJECT STATE — the single driving document

**This is the only document that drives the project.** Decisions, open
issues, pending work, and the route to `main` all live here. Everything else
in `docs/` is *reference* — consulted when this file sends you there, never
the thing you plan from. If this file and a reference file disagree about
what to DO, this file wins; if they disagree about a METHOD, the reference
wins (see §8).

**Last verified: 2026-09-02.** `mvn21 clean verify` → **`Tests run: 243,
Failures: 0, Errors: 0`, BUILD SUCCESS, 05:48**, on the **merge commit
`38eb11f`** in `~/Downloads/cbt-main`. 37 test classes ran against 37 on
disk. Count read from Maven's own summary line, never from arithmetic over
report files (F76) — which did **not** recur.

> **This closes the one gate that was open on the merge.** `38eb11f` was
> recorded as "started and KILLED mid-run… it has NEVER been green". It is
> green now, so §7.2 gate 1 holds for the tree that would be published.
> Gate 6 (goldens re-read) is still open and now has *more* to cover: §6
> Phase 0 changes two further goldens deliberately, **before** G2, so the
> signature is given once against final text.

> **2026-09-02 — the handoff pack was reviewed against this tree.**
> `thesis-handoff-2026-08-21` (which supersedes `-08-20` entirely) read the
> repo at `main @ 9e9fbdd`, **30 commits behind**. Every finding was
> re-verified here; the ledger is `docs/HANDOFF_REVIEW_2026-09-02.md`. Six
> blocking defects survive and are now §5's first table — two of them
> (**C1**, **M2**) silently discard *all* HotStuff and *all* CometBFT fault
> data while the suite stays green, and C1 is proven end-to-end by
> execution.

> **2026-09-03 — three further blockers found, none of them in any plan.**
> The loadgen cannot run the harness as provisioned: **no JRE** in
> `cloud-init.yaml`, **no private SSH key** on the loadgen (Terraform installs
> only the public one), and **nothing ships the jar**. See §5's `B-1/2/3` row.
> Phase 2 is also rewritten: the canary is now performed **by hand at n=3**,
> per `docs/FIRST_MANUAL_DEPLOY.md`, and the two are one session.

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

At the start of that session `ValidityChecker` was a library *nothing
called*, so **no campaign run produced a `validity.json` at all**, and a run
measured against it evaluated ONE gate and still reported `valid: true`.
Measured again on 2026-08-15 with the full collector set: **9 of 11
evaluate**.

> **That figure is true in the test harness and NOT true of a deployed
> campaign** (2026-09-02). Three things stand between it and reality, all in
> §5: **M3** — a `remote-run` runs the check at `RemoteRunner:199` and writes
> `events/` at `:213`, so two gates SKIP on a directory that does not exist
> yet (the campaign path is saved only because `gradeCells` re-runs the check
> at block end); and **N-16 + N-5** — nothing deploys Prometheus and nothing
> substitutes `prometheus.yml`'s placeholders, so on real VMs every metric
> series is empty and the §4 meta-rule turns each one into a **FAIL**. The
> honest figure until Phase 0 lands is *"9 of 11 in a test fixture; unproven
> on a VM"*. Canary check 2.9/2.13 is what converts it.

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

### Where the work lives — VERIFY THIS, do not trust it

Work on this project has lived on more than one branch and worktree at once,
so the first question of any session is "am I looking at all of it?".
**Answer it by executing, not by reading the table below** — the table is a
snapshot with a date on it, git is the truth:

```bash
git worktree list                     # checkouts and their branches
git branch -vv --all                  # every branch, local and remote
for b in $(git for-each-ref --format='%(refname:short)' refs/heads); do
  printf '%-28s %s ahead of main\n' "$b" "$(git rev-list --count origin/main..$b)"
done                                  # unmerged work on OTHER branches
```

If what you find disagrees with this table, **git wins — then fix the table.**
A stale coordination map is worse than none, because it is trusted.

**Snapshot, 2026-09-02** (the 2026-08-15 table below was already wrong on
two counts — there are **three** worktrees, not two, and the merge to `main`
exists locally):

| Worktree | Branch | State |
|---|---|---|
| `~/Downloads/cbt-main` | `main` → `ninth-review-handoff` | **CANONICAL.** Holds the merge `38eb11f` (30 ahead of `origin/main`, **not pushed**) and this review's branch |
| `~/Downloads/cbt-eighth` | `eighth-review-tier1` | Tree-identical to the merge; pushed |
| `~/Downloads/consensus-bench-thesis` | `seventh-review-tier1` | **STALE** — opening the repo at its natural path lands you here, on a superseded `PROJECT_STATE.md`. Carries **one commit not in `main`** (`81794f7`, `SESSION_CLOSE_2026-08-15.md`), so §7.1's "strict subset" claim is false and §7.3's branch deletion would lose that file |

**Superseded snapshot, 2026-08-15:**

| Worktree | Branch | Ahead of `main` | What is on it |
|---|---|---|---|
| `~/Downloads/cbt-eighth` | `eighth-review-tier1` | **28** | **All current work.** The eighth read-through (F70–F76), decisions D12–D15, the S0–S5 increments, the full validity layer, the doc consolidation |
| `~/Downloads/consensus-bench-thesis` | `seventh-review-tier1` | 7 | The seventh review (F50–F69) — **idle since 2026-08-14 and a strict SUBSET of the branch above**, so it needs no separate merge. Kept only as an independent copy until the merge lands |

**Expected state after §7's merge:** one worktree, on `main`, and both
branches deleted. If you are reading this and `git worktree list` still shows
two, the merge has not happened yet — it is queue item §6 #1–#3.

**Why two existed at all:** two Claude sessions worked concurrently on
2026-08-14 sharing ONE checkout, where the failure mode is a *silent lost
update* (a whole-file overwrite), not a merge conflict git could catch. Git
refuses one branch in two worktrees, so the *mid-increment* session kept its
path and the other moved. A third concurrent session gets
`git worktree add ../cbt-<topic> -b <topic>` — never a second session in an
existing checkout.

### Handing over mid-increment

If a session stops in the middle of something, **record it here** before
stopping: what is in flight, on which branch and worktree, what was verified,
and what the next step was. The alternative is the next session re-deriving
it from commit messages — or redoing it. Nothing is in flight as of
2026-08-15; the tree is clean on both worktrees and every branch is pushed.

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
| **D16** | **The campaign runs the runbook's shape, NOT `--simulation standard`.** `Simulations.standard` emits a full Cartesian product — measured, **120 runs** at four rates — while `EXECUTION_AND_COST_MODEL` §"per-system budget" plans **76 runs ≈ 12.0 h** (sat-search 30 min + sat 5×10 + rate-sweep 15×10 + faults 25×10 + failover 30×8). **The cost model is right and the code does not implement it.** The saving is modest (≈12 h → ≈11.5 h); the point is *correctness of shape*, not budget — `standard` spends 60 extra runs on fault×rate combinations no planned figure consumes. Beware the units: 120 **runs** = 24 **cells** at n=5, and the "71 h" in the cost model is *hours*, not runs (handoff N-1/D-6; evidence in `HANDOFF_REVIEW_2026-09-02.md` §6) |
| **D17** | **n=20 for Paxos vs EPaxos at TWO conflict rates**, n=5 everywhere else. The registered prediction is a *crossover*, so one rate tests neither end; at n=5 Mann–Whitney bottoms out at p≈0.008 and is decorative. Marginal ≈10 machine-hours, ≈€3–6. Elsewhere: effect sizes, bootstrap CIs and printed raw points, **no significance tests** (Kalibera & Jones). The load memo's original n=10 request and its reduction to n=5 are recorded in Appendix Δ rather than silently declined |
| **D18** | **Any change to golden text lands BEFORE the G2 read-through.** The signature is given once, against final text. **Exactly one open item touches golden text: N-20** (the etcd quota). C4 does **not** — `RemoteSshProvider.java:352` already computes `rf = Math.min(3, clusterSize)` and `txnMinIsr`, which at size 3 is byte-identical to `kraft-size3-start-stop.txt`; the partition count is client-side in `KafkaDriver`. Everything else in Phase 0 is there for the older rule — *do first whatever cannot be fixed after the VMs are billed* |

---

## 5. Open issues

Closed findings F1–F76 with their evidence live in
`docs/archive/FINDINGS_LEDGER_F1-F76.md` — the audit trail, kept because code
comments reference F-numbers. The 2026-09-02 review of the
`thesis-handoff-2026-08-21` pack is in `docs/HANDOFF_REVIEW_2026-09-02.md`,
which carries the evidence. **Read that file's §7 before trusting this
table**: C4, C3, M1, M4, M6 were accepted from the handoff author's registry
and re-verified at file:line by a later review pass, but the *rest* of §5's
rows are backed by an executed command and these five were not, originally.
Only what is still OPEN is here.

### Blocking — proven, and each one silently destroys data or money

| # | Issue | Proof | Fix |
|---|---|---|---|
| **C1** | Every **HotStuff** run is discarded. Its manifest carries no `error_rate`; `gateBaselineErrorRate` FAILs on a missing field; HotStuff's scenario is always `baseline`, so the SKIP never fires | **Executed**: `valid=false`, then `analyse.py` → `0 runs, 1 excluded (baseline_error_rate)` | SKIP for the systems that have no engine-measured error rate, via the existing `hasNoThroughputSeries` seam; add `window`, which *is* available from `spec.window()`. **An `error_rate` field is NOT implementable** — `HotStuffSummary`'s nine fields carry no error or failed-op count, so the SKIP branch is the only real option |
| **C2** | **Fixing C1 alone still gives Chapter 11 nothing.** `analyse.py` never opens `summary.txt`; every number it builds comes from `read_throughput`/`read_latency`, and HotStuff writes neither | **Executed** with C1 patched: `valid=true`, and `cells.csv` → `hotstuff,baseline,4,0.0,1000,1` — an `n` and nothing else | Add a `summary.txt` reader and a **separate** HotStuff table. No shared latency figure (Level Γ) |
| **M2** | **CometBFT `kill`/`double_kill` fault runs are discarded.** `cmt_rounds` is a *gauge* that returns to 0 on commit, checked as a counter (`last > first`) | `export_queries.txt:38` + `ValidityChecker.java:591-592`. **Not "every" fault run** — `:593` also passes on a `node_up` drop, which rescues partition and packet_loss; kill and double_kill are the bite, and the gate's own javadoc says `node_up` cannot witness a `docker kill` | Route `tendermint` to `seriesExceedsZeroInWindow`, the branch `kafka_urp` already uses |
| **N-16** | **Nothing deploys the observability stack.** One `cloud-init.yaml` for all roles; no `docker compose up`, no copy of `observability/` | `main.tf:157`; `scripts/` holds one file | Write `scripts/deploy_obs.sh` |
| **N-5** | **`prometheus.yml` placeholders are never substituted**, and `NODE4` is hardcoded at n=3 | No writer anywhere in `scripts/ infra/ harness/src/main/` | Install `patches/scripts/render_obs_config.sh` |
| **N-7** | `MatrixRunner` has **no failure ceiling** — a systemic fault bills all night and produces nothing | `MatrixRunner.java:322` | Re-implement the breaker (the shipped patch is stale, §6 P0.9) |
| **B-1/2/3** | **The loadgen cannot run the harness at all.** Three things nothing provisions: (1) **no JRE** — `cloud-init.yaml`'s package list has no JDK and `grep -rniE 'jdk\|openjdk' infra/ scripts/` returns **0** hits, yet `RemoteRunner` SSHes to `10.0.0.11+` and so can only run **on** loadgen; (2) **no private key on loadgen** — Terraform installs only the public key (`main.tf:43`) while `Inventory` hands `SSH_KEY=~/.ssh/hetzner_thesis` to `SshjExecutor`; (3) **nothing ships the jar** — no script copies it, and `RemoteRunner.java:388` reads `observability/export_queries.txt` relative to CWD while its javadoc credits `collect_block.sh`, which only copies results *back*. | Greps + file:line, this review. (2) is recorded **only** in `docs/archive/HONEST_REVIEW_V6.md:36-39`, i.e. in a DEPRECATED file, and appears in no current plan | `scripts/bootstrap_loadgen.sh` — §6 Phase 0.15 |
| **M3** | The **"9 of 11 gates" figure is not what a `remote-run` produces** — the check runs at `:199`, `events/` is written at `:213` | file:line | Move both collectors above the check |

**N-16 + N-5 together are the campaign-killer.** With no Prometheus, the
exporter writes header-only files, `hasMetrics` is true, and the §4 meta-rule
turns every empty series into a **FAIL** — so every run is `valid: false`,
every cell grades **C**, and `analyse.py` excludes the entire campaign. The
run still bills in full.

### Serious — correctness of what gets recorded

| # | Issue | Fix |
|---|---|---|
| **C4** | E-SCALE does not measure what it claims. **The finding as written is half wrong** — `KafkaDriver.java:95` already does `rf = Math.min(3, bootstrap.size())`, so RF is not "pinned at 3". The two real defects are the **cap at 3** (so a size-5 or size-7 scalability cell still replicates to 3 and E-SCALE compares nothing) and **`PARTITIONS = 6`** (`KafkaDriver.java:61`), which measures six Raft groups against etcd's one | Remove the cap; `min.insync.replicas = ⌊n/2⌋+1`; 1 partition for E-SCALE plus one *declared* 6-partition production-shaped cell. **Client-side only — does NOT touch goldens** |
| **N-20** | etcd runs on its **default 2 GiB backend quota** with no auto-compaction; ~1.95 M writes at 1 KB exhausts it, so any cell above ~4,000 ops/s for 480 s trips `NOSPACE` mid-run | Set the quota and auto-compaction in the etcd recipe. **Touches goldens.** Measure `etcd_mvcc_db_total_size_in_bytes` on the canary |
| **C3** | Operations that hit the 5 s driver bound leave the histogram entirely — unevenly (Paxi loses ~100 % of its tail mass, etcd a few percent) | Second histogram at the bound; report `p99_success` **and the timed-out count** in the manifest and every fault caption |
| **M1** | The exporter writes series-major CSV and the checker **ignores the instance column**, so the counter check compares node 1's first sample to node 3's last | Group by series before comparing |
| **M4** | Accumulated open-loop backlog is discarded at loop exit — understates p99/max, worst for the system that stalled most, i.e. for the ranking itself | Record the remainder as `omitted_by_schedule` |
| **M6** | Warmup is judged by *completion* time while latency is charged to *intended* time | Judge by intended. One line |
| **M5/M8/M9** | `events_dropped` is written and no gate reads it; grade **A** is unreachable (`durability` always SKIPs); the SKIP set is read from `reports.get(0)` only | Three small fixes; see also §5 note below |
| **N-6** | No free-disk check anywhere | `patches/scripts/disk_guard.sh` |
| **N-17** | `analyse.py` never merges `.hlog` histograms, so figure **F3** cannot be produced and the methodology's headline latency claim is half-made | Implement the merge with a `--selftest` case first |
| **N-18** | `runHotStuff` throws `UnsupportedOperationException` for every non-BASELINE cell — three in a row would trip the new breaker on a by-design refusal | Count unsupported cells as *skipped*, never *failed* |

### Carried from before, unchanged

| # | Issue | Next |
|---|---|---|
| **F75** (half) | `picocli` declared, shaded, used by nothing | Spend it (M1.3) or drop it. P3.11b |
| **F76** | A `verify` once ran 90 of 177 tests and still reported success | **Watch.** Did **not** recur on 2026-09-02 (37 of 37 classes, 5:48). If it returns, capture `target/surefire-reports/` *before* re-running |
| **N1** | `error_rate > 0.5 ⇒ failed` could exclude the preregistered paxi wedge and DOUBLE_KILL | Verify at the pilot |
| **N2 / D-9** | `corpus/` tracks the signed application PDF with ΑΦΜ, phone and email, and the repo is private *today* | **Purge from the working tree and from history now.** The only item that strictly gets worse with delay |

**Two documentation defects found in this review**, both cheap: the
`baseline_error_rate` gate is enforced but appears in **no** methodology
document (its `GateSpec` cites "§4.1 / F52"; neither string exists in
`DATA_ANALYSIS_METHODOLOGY.md` or `METRICS_AND_SOURCES.md` — the mirror of
F40), and `export_queries.txt` has **23** queries where `README.md:59` and
`LOCAL_TESTING.md:430` say 25 — the latter as *expected output*, so following
that doc shows a false failure.

**Decisions the author still owes** — each blocks a queue item:

- **P2.6 scope** — the durability oracle. The one gate that SKIPs for want of
  a *decision*, and the reason grade **A** is unreachable.
- **HotStuff fault preregistration** — target semantics, the `faults` field,
  and whether the upstream client survives a mid-run kill.
- **D-11** — Phase C on `ccx23` (2 physical cores) or `ccx33` (4), against
  HotStuff's published 8. ≈ €9 gross. Decide *after* the pilot measures
  whether HotStuff is crypto-bound here.
- **D-4** — the E-CTRL deadline. If controller failover is not merged and
  green by that date, Ch. 5 and Ch. 8 become analytical chapters with a
  stated reason. Write that sentence *before* the deadline.

---

## 6. The plan — six phases to the campaign

**The organising constraint, and it governs the whole ordering:** *anything
that changes golden text must land before the G2 read-through.* The handoff
pack's own plan puts C4 after G2, which would invalidate the signature the
moment it is given — §7.2 gate 6 exists for exactly this. Phase 0 therefore
absorbs every golden-touching change.

The second constraint is the old one: **do first whatever cannot be fixed
after the VMs are billed.**

Phases 0–1 cost **€0**. Phase 2 costs about the price of a coffee. Nothing is
billed before Phase 2.

### Phase 0 — free, and blocking. Everything that must precede the goldens.

TDD throughout: failing test first, `mvn21 clean verify` green with the count
read from Maven's own summary line, one commit per step.

| # | Step | Touches goldens? | Done when |
|---|---|---|---|
| **0.1** | **C1** — the gate SKIPs for systems with no engine-measured error rate (`error_rate` is **not** derivable from the HotStuff SUMMARY); add `window` from `spec.window()` | no | A HotStuff-shaped manifest reaches `valid: true`; a red test first, on the manifest **production actually writes**. **Not done until 0.5b too** — `valid: true` alone still yields a cell of `{n: 1}` |
| **0.2** | **M2** — `tendermint` uses the gauge witness | no | A recovered-chain fixture (rounds 0→2→0) passes gate 3 |
| **0.3** | **M3** — collectors move above the validity check | no | A `remote-run` fixture evaluates `container_restarts` instead of SKIPping it |
| **0.4** | **N-20** — etcd backend quota + auto-compaction. **The only golden-touching item in the plan.** Size it from published guidance now and accept that canary 2.16 may revise it (see the note under Phase 3) | **YES** | Golden updated first as the spec, provider matches verbatim |
| **0.5** | **C4** — remove `KafkaDriver`'s RF cap at 3, set `min.insync.replicas = ⌊n/2⌋+1`, 1 partition for E-SCALE plus one declared 6-partition cell | no — client-side | A size-5 cell replicates to 5; `kraft-size3` golden **unchanged** (verify by running the golden test, do not assume) |
| **0.5b** | **C2** — `analyse.py` reads `summary.txt`; HotStuff gets its own table | no | A HotStuff cell yields real numbers, not `{n: 1}` |
| **0.6** | **N-16** — write `scripts/deploy_obs.sh`: scp `observability/` + the rendered config to obs → `docker compose up -d` → wait `/-/ready` → assert **every target `up`** | no | Runs against a local Prometheus; refuses to return 0 with any **expected** target down. **"every target up" is unachievable as written**: `prometheus.yml` declares jobs for all seven systems while only one SUT runs, and the `harness` job (:9400) is live only *during* a cell. Scope the assertion to the `node` job plus the active SUT's job |
| **0.7** | **N-5** — install `patches/scripts/render_obs_config.sh` | no | Renders for n=2, 3, 4, 7; `promtool check config` green (install promtool) |
| **0.8** | **`main.tf` hardening** — `cpx21`→`cx33`, `ccx33` 0.1923→0.2219, IPv4 line item, **cost fails closed** on an unpriced type. Patch applies clean (`infra/`, `-p1`) | no | `terraform validate`, `fmt -check`, and the 8 `tftest` runs green — install `patches/tests/` **in the same commit** |
| **0.9** | **cloud-init hardening** — docker-daemon readiness, log ceiling, `fio`/`iperf3`/`ethtool`/`rsync`. Patch applies clean | no | `cloudinit_lint.py` exit 0 |
| **0.10** | **N-7 + N-18** — **re-implement** the circuit breaker (the shipped patch is stale: 2/3 and 2/2 hunks fail). Abort on 3 consecutive or >25 % of ≥8 attempted; `heartbeat.json` after every cell; exit 2 | no | Breaker unit tests; `journal(...)` and `gradeCells(...)` preserved; unsupported cells counted as **skipped** |
| **0.11** | **N-6 + collection hardening** — `disk_guard.sh`, `campaign_watchdog.sh`, `collect_block-hardening.patch` (clean), one `REMOTE_RESULTS` name shared by harness and scripts | no | The watchdog is **seen to fire once** |
| **0.12** | **M5/M8/M9** — a gate reads `events_dropped`; the SKIP set aggregates across *all* reports, not `reports.get(0)`; grade **A** made reachable | no | A cell with heterogeneous SKIPs grades correctly — the case no test covers today |
| **0.13** | **D-9 / N2** — purge `corpus/` of the signed application PDF, **from history** | no | `git log --all -- corpus/<file>` empty; force-push while private |
| **0.15** | **B-1/2/3** — `scripts/bootstrap_loadgen.sh`: install `openjdk-21-jre-headless` + `rsync`, push the private key to `/root/.ssh/`, push the jar, `deploy/inventory.env` and `observability/export_queries.txt` into the CWD the harness runs from. Add the JRE to `cloud-init.yaml` in the same commit (it is the cheaper half). Fix `RemoteRunner.java:388`'s javadoc, which names the wrong script | no | `java -version` and a loadgen→node SSH both succeed on a freshly applied cluster |
| **0.14** | Doc corrections: query count 23, P3.4's `< €0.10`, P3.5's type list, VAT, `hcloud primary-ip list` in runbook §7, N-11's phantom `logs/`, the `baseline_error_rate` clause into the methodology | no | — |

**Exit criteria:** `mvn21 clean verify` green with the count on Maven's own
line and ≥ 4 minutes; `terraform validate` + `tofu/terraform test` green;
every golden-touching change landed.

### Phase 1 — free. The gate and the dress rehearsal.

| # | Step | Owner |
|---|---|---|
| **1.1** | **G2 — read all seven goldens against their header checklists and sign off, dated.** Phase 0 changed the etcd and Kafka goldens on top of the three already changed (F69 sweep, derived `slow_node` timeout, packet-loss severity) | **AUTHOR** |
| **1.2** | **Canary Stage 0 — €0.** `hcloud server-type list` · `hcloud server-type describe cx33` · **`hcloud server-type list -o json | jq '.[].prices'`** (net *and* gross per location, straight from the API; `hcloud pricing` is a recent-CLI convenience and may not exist on your version) · `hcloud location list` · `terraform plan -var consensus_node_count=2` | **AUTHOR** |
| **1.3** | **P3.5** — transcribe every price from `hcloud pricing` into `local.hourly_eur`. **Any disagreement with the handoff's cost model resolves in favour of `hcloud pricing`** | **AUTHOR** |
| **1.4** | `preflight.sh` green; one real boot of `cloud-init.yaml` under multipass/QEMU (closes N-10); record `df -h /` on the booted VM as the disk baseline | either |

**Pass criteria:** `cx33` appears and **`cpx21` does not** — confirming the
blocking finding at zero cost. `terraform plan` shows **`6 + N_vms`**
resources (6 singletons + one `hcloud_server` per VM), so the canary's
`consensus_node_count=2` → 4 VMs → **10 to add**. `LOCAL_TESTING.md §3.3`
records the formula by execution: 11 at n=3, 15 at n=7, 12 at n=4. **Do not
expect 12** — that figure belongs to the 6-VM Phase-C shape.
**If Stage 0 fails, stop.** A wrong type here is a five-minute edit; the same
type at Stage 1 is a failed apply on the day you first spend money.

### Phase 2 — the canary, driven BY HAND. ≈ €1.10 gross. First contact.

**Revised 2026-09-03 — the canary is performed manually, at n=3, and the two
are the same session.** Running a hand-driven walk *and* a scripted canary
would pay twice for one answer. The step-by-step commands are
**`docs/FIRST_MANUAL_DEPLOY.md`**; the 18 checks below stay exactly as they
are and serve as its checklist.

**n=3, not n=2.** Quorum on two nodes is two, so `LEADER_KILL` at n=2 leaves
*zero* quorum — check 2.14 could only ever prove fault *mechanics* there
(the caveat is spelled out in its row). At n=3 the survivors re-elect,
`throughput.csv` shows the gap and the recovery, and the SSH sequence is
diffable line-for-line against `goldens/etcd-size3-start-stop.txt`. The cost
of the difference is ≈€0.26. `terraform plan` then shows **11**, not 10 —
`6 + N_vms` with 5 VMs, the figure `LOCAL_TESTING.md §3.3` already records by
execution at n=3.

**Phase 0.15 is a hard prerequisite** — or its three steps are done by hand
in the session (`FIRST_MANUAL_DEPLOY.md` S7), which is the point of doing it
manually once.

Original n=2 shape, kept for reference: **Phase-A topology with `consensus_node_count=2`** — 2×`ccx13` + loadgen
+ obs = 4 servers. **€0.2203/h for the VMs alone; €0.2230/h once Phase 0.8's
patch adds the primary-IPv4 line item**, which is what `terraform plan` will
print — expect the second number, not the first. Two hours ≈ €0.45 net /
€0.55 gross; **budget three hours = €0.67 net / €0.83 gross** and plan for
that, not for the two-hour figure. Every euro here depends on prices this
project has **never read from Hetzner** — Phase 1.3 replaces them all. The repo's old `< €0.10` is unachievable — Hetzner rounds
partial hours **up**, so any apply at all costs one full hour.

**Set a phone timer for three hours the moment you press apply.**

| # | Check |
|---|---|
| 2.1 | `terraform apply -var consensus_node_count=2` succeeds; **10** resources (`6 + N_vms`, see Phase 1) |
| 2.2 | `deploy/inventory.env` correct; `CONSENSUS_NODE_COUNT=2` |
| 2.3 | SSH from the laptop, no password prompt |
| 2.4 | `cloud-init status --wait` → `done`, no failed units |
| 2.5 | `docker chrony stress-ng xxd jq curl fio iperf3 ethtool` all resolve |
| 2.6 | The four pinned digests are pre-pulled |
| 2.7 | **`sudo` semantics** — one real `tc` and one real `iptables` as the harness runs them |
| 2.8 | Private-net routing; **record the interface name** `ip -o route get` resolves |
| 2.9 | **`deploy_obs.sh` brings Prometheus up and every target reports `up`** — the N-16/N-5 regression check |
| 2.10 | `up{role="consensus"}` and `up{role="loadgen"}` both non-empty |
| 2.11 | `chronyc tracking` inside the 5 ms gate on every node |
| 2.12 | No provider command approaches the 30 s SSH bound |
| 2.13 | **One real etcd cell end-to-end** → all four artifacts, `environment == "hetzner"`, **and `validity.json` with the metric gates actually PASSing**. **`--size 2` is mandatory and is not the default**: `Main.java:101` defaults `--size` to `SystemUnderTest.ETCD.defaultClusterSize()` = 3, and `RemoteSshProvider.java:126` then throws `clusterSize must be 1..2 (provisioned nodes), got 3`. Verified by execution against a 2-IP inventory |
| 2.14 | **Each fault type does what it claims — MECHANICS ONLY at n=2.** Quorum on two nodes is two, so `kill` leaves *zero* quorum and `partition` gives a 1\|1 split with no majority: "the loadgen→leader path still works" is vacuous here, because the leader can commit nothing. `DOUBLE_KILL` would kill both. This checks that the rules are installed and healed, **not** the D15 semantics — those wait for Phase 4's smokes at size 3. Verify the *effect*, not the exit code: `kill` → container gone and `up{}` drops · `packet_loss` → netem on the *resolved* iface, ping loss visible · `partition` → pairwise DROP rules **and the loadgen→leader path still works** · `slow_node` → `stress-ng` running, node CPU rises. Then `heal()` and confirm every rule is gone |
| 2.15 | **`node_facts.sh` on all four VMs, committed.** fsync p50/p99, single-core scalar, inter-node RTT matrix, threads-per-core. **This expires** — after the campaign the hardware is gone and unrecoverable |
| 2.16 | **N-20 measurement** — `etcd_mvcc_db_total_size_in_bytes` across the cell, to size the quota with evidence |
| 2.17 | **N-15 measurement** — bytes of SUT log per node per second, and the gzip ratio, to close the one unmeasured volume term |
| 2.18 | `terraform destroy`; **`hcloud server list` AND `hcloud primary-ip list` both empty**; record the actual cost from the console |

**Green** = 2.1–2.18 pass, evidence committed to `docs/CANARY_EVIDENCE.md`,
cluster destroyed. **Amber** (understood, local fix) — destroy, fix, re-run;
a second canary is another €0.44 and that is the whole point of spending
€0.55. **Never carry an amber into a €14 phase.** **Red** in the fault
injector or the private network — destroy immediately and treat it as a code
task.

### Phase 3 — free. What the canary taught, plus what changes the record.

Only now, with real numbers in hand.

| # | Step |
|---|---|
| **3.1** | **Only if canary 2.16 contradicts 0.4's figure**: resize the etcd backend quota. This is the one place the plan can be forced to break **D18**, and pretending otherwise would be worse than saying it: 0.4 must pick a quota before anyone has measured `etcd_mvcc_db_total_size_in_bytes`. Mitigation — size 0.4 generously enough that measurement is expected to *confirm* it; if it does not, the etcd golden is re-read and **re-signed**, and that is a known, bounded cost, not a surprise |
| **3.2** | **C3** — second histogram at the driver bound; timed-out count into the manifest |
| **3.3** | **M4** — `omitted_by_schedule` into the manifest |
| **3.4** | **M6** — warmup judged by intended time |
| **3.5** | **M1** — group metric CSV by series before the counter comparison |
| **3.6** | **Hardware provenance** — `node_facts.sh` wired per apply, a 12-char `hw_fingerprint` in every manifest, and two gates: **12** intra-run homogeneity (fail-closed) and **13** cross-block continuity (warn + downgrade) |
| **3.7** | **N-17 / M7** — implement `.hlog` merging in `analyse.py`, `--selftest` case first. Without it there is no F3 and the methodology's headline claim is half-made |
| **3.8** | `docs/PREDICTIONS.md`, append-only, committed **before** any campaign run |

### Phase 4 — G3 and the pilot. First real billing.

| # | Step |
|---|---|
| **4.1** | Per-system remote smokes, one short cell each |
| **4.2** | **G3** — harness vs `kafka-producer-perf-test`, `etcd benchmark`, Paxi's benchmarker. A ratio table with an explanation for every value outside 0.8–1.25× |
| **4.3** | **M6.2 pilot** — n=2 baseline per system; fix **every** `PROVISIONAL` threshold numerically from pilot variance |
| **4.4** | Decide **D-11** (`ccx23` vs `ccx33`) on the pilot's evidence, not on taste |
| **4.5** | Re-check **N1** — does `error_rate > 0.5 ⇒ failed` delete the preregistered paxi wedge? |

**Exit:** no `PROVISIONAL` constant remains.

### Phase 5 — the campaign. ≈ €80 gross, hard cap €100.

**Run the runbook's shape (D16), not `--simulation standard`.** Measured on
the shipped jar: `standard` at four rates emits **120 runs** (D14's severity
sweep doubles PACKET_LOSS), ≈20 h/system. `EXECUTION_AND_COST_MODEL`'s
per-system budget plans **76 runs ≈ 12.0 h** and already counts both
packet-loss severities. The code does not implement the plan; the plan is
right. Units matter: 120 **runs** = 24 **cells** at n=5.

**The saturation search comes first and is not optional** — the sweep rates
R1..R4 are 25/50/75 % of a *measured* saturation, which is why
`Simulations.java` keeps rates an operator input. Budget it:

```bash
# block 0 — saturation search, THEN saturation at n=5. R1..R4 are derived
#           from its result (25/50/75% + saturation). ~30 min + 5x10 min.
campaign-run --system <s> --scenarios baseline --rates 0 --reps 5

# block 1 — baseline across the derived rate sweep              → 15 runs
campaign-run --system <s> --scenarios baseline --rates R1,R2,R3 --reps 5
# block 2 — every fault at ONE rate (packet_loss x2 severities) → 25 runs
campaign-run --system <s> --rates R2 --reps 5 \
  --scenarios packet_loss,network_partition,slow_node,double_kill
# block 3 — the failover distribution                           → 30 runs
campaign-run --system <s> --simulation failover --rates R2
```

That is 5 + 15 + 25 + 30 = **75 runs ≈ 11.5 h**, against the cost model's 76
≈ 12.0 h — i.e. this reproduces the plan the repo already costed, which is
the point. It is **not** a budget saving; `--simulation standard`'s extra 45
runs buy fault×rate combinations no figure consumes.

Plus **n=20 for Paxos vs EPaxos at two conflict rates** (D17) — the only cell
pair that yields a *finding*, and the registered prediction is a crossover, so
one rate tests neither end. Marginal ≈10 machine-hours, ≈€3–6.

**No block runs without the breaker and the watchdog armed**, and the watchdog
must have been seen to fire once (0.11).

**If time runs short, cut in this order:** the 6-partition cell → PACKET_LOSS
at 30 % → sizes 5 and 7 for KRaft → DOUBLE_KILL. **Never cut** the Level Α
conflict sweep, the failover trials, or the baseline.

**The real financial risk is not the campaign — it is a forgotten cluster.**
Phase C at ≈€7/day costs €133 in a week, more than the entire planned
campaign. Destroy discipline is the only cost lever that matters.

### Phase 6 — analysis and writing.

`M6.4` analysis v2 (pooled histograms, Holm–Bonferroni, ECDFs, the eight
figures), then the chapters. **Five chapters and two tables are writeable
before any measurement exists** — the handoff's `02-THESIS-SKELETON.md §5` is
the reference, and Ch. 9 first needs the Castro & Liskov PDFs, which are not
in `corpus/`.

### What is deliberately NOT in this plan

`S4.2` report generator (delete it if unread after the pilot) · `M1.3` picocli
· HotStuff fault scenarios (needs the preregistration decisions in §5) ·
`P2.6` durability oracle (needs the scope decision) · `F8/P2.0` scheduler
scaling (build **only** if G3 shows achieved < 99 % of target).

## 7. Merge runbook — `eighth-review-tier1` → `main`

> **STATUS, 2026-09-02 — read this before the runbook below, which was
> written when the merge had not happened.** Three of its statements are now
> false:
>
> 1. **The merge already exists**, locally, as `38eb11f` in
>    `~/Downloads/cbt-main` — so §7.1's "`main` has **not** moved" and
>    §7.3's steps 0–2 describe work that is done. It is **not pushed**
>    (`origin/main` is still `9e9fbdd`, 30 behind).
> 2. **Gate 1 now holds.** `mvn21 clean verify` on `38eb11f` → 243 tests,
>    BUILD SUCCESS, 05:48, 37/37 classes. See the §0 header.
> 3. **`seventh-review-tier1` is NOT a strict subset.**
>    `git log main..seventh-review-tier1` → `81794f7`
>    (`docs/SESSION_CLOSE_2026-08-15.md`). §7.3's step 5 deletes both
>    branches; doing that as written **loses that file**. Cherry-pick it
>    first, or accept the loss knowingly — its substance (F76 and the
>    read-the-count-from-Maven mitigation) is already carried in §5 and
>    §7.2 gate 1, so the loss is of the record, not the knowledge.
>
> **What still blocks the push:** gate 6, the goldens. Do not push `main`
> and do not delete any branch until §6 Phase 0 is complete and Phase 1.1
> (G2) is signed — Phase 0 deliberately changes two more goldens (N-20,
> C4), so signing now would mean signing twice. Keep a `pre-merge-eighth`
> tag until the canary is green: a deleted rollback target is not a
> rollback target.

### 7.1 The situation as of 2026-08-15 (superseded — see the status note above)

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
| 1 | **Suite green, count verified** ✅ **2026-09-02** | `cd harness && mvn21 clean verify` → BUILD SUCCESS with the count read from Maven's **`Tests run:` summary line**, ≥ 243. A suspiciously fast run (<4 min) is a FAILED gate until the count is confirmed — that is F76. **Held on `38eb11f`: 243, 05:48, 37/37 classes.** Re-run after Phase 0 |
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

# 5. Retire the branches. WARNING (2026-09-02): `seventh` is NOT contained
#    in the merge — it carries 81794f7 (docs/SESSION_CLOSE_2026-08-15.md).
#    Check first, and cherry-pick or accept the loss deliberately:
git log --oneline main..seventh-review-tier1     # must be EMPTY before deleting
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
| **`FIRST_MANUAL_DEPLOY.md`** | **The Phase 2 session, command by command.** The author's hand-driven canary at n=3: tooling, key, token, price gate, apply, loadgen bootstrap, obs by hand, one baseline + one `leader_kill` cell, collection, analysis, destroy |
| `HANDOFF_REVIEW_2026-09-02.md` | **Evidence ledger** for the `thesis-handoff-2026-08-21` review: every finding's verdict against *this* tree, what was executed, and what was not |
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
