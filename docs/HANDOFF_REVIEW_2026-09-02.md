# Handoff-pack review — verification ledger, 2026-09-02

The `thesis-handoff-2026-08-20` and `-08-21` packs were reviewed against the
**live tree**, not against the tree they were written for. This file is the
evidence: every claim that changes what we do carries a verdict and the
command that produced it. It is an audit trail, like
`archive/FINDINGS_LEDGER_F1-F76.md` — the *plan* it feeds is
`PROJECT_STATE.md` §6.

## 0. The single most important fact about the pack

**`08-21` supersedes `08-20` entirely** (`diff -rq`: same files, plus
documents 07–13 and `patches/`; only `00-START-HERE`, `CLAUDE.md` and
`MANIFEST.md` differ, and 08-21's are the newer). **Read only `08-21`.**

The pack has two halves with very different evidential standing, and it says
so itself:

| Half | Read the repo? | Standing |
|---|---|---|
| `01`–`06` (literature, skeleton, plan, decisions) | **No** — worked from the experimental plan on trust | Claims about the harness are inherited, not observed |
| `07`–`13` + `patches/` | **Yes**, at `main @ 9e9fbdd` | Most claims executed. **But `9e9fbdd` is 30 commits behind our `main`** |

Everything below re-establishes which findings survive that 30-commit gap.

---

## 1. Verdicts — the pack's own findings (N-series)

`infra/` and `scripts/` were **untouched** by the 30-commit merge
(`git diff --stat 9e9fbdd..main -- infra/` shows only the new
`infra/probes/`), so every infrastructure finding transfers unchanged.

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| **N-16** | Nothing deploys the observability stack | **CONFIRMED, BLOCKING** | `user_data = file("cloud-init.yaml")` is one file for *all* roles (`main.tf:157`); its `runcmd` ends at the JMX-agent download — no `docker compose up`, no copy of `observability/`. `scripts/` holds one file |
| **N-5** | `prometheus.yml` placeholders are never substituted | **CONFIRMED, BLOCKING** | The file says "Replace PRIVATE_* placeholders … or let the WS2 provisioning script template them"; grep across `scripts/ infra/ harness/src/main/` finds no writer. `NODE4` is hardcoded while Phase A provisions 3 |
| **N-7** | `MatrixRunner` has no failure ceiling | **CONFIRMED, BLOCKING** | `MatrixRunner.java:322` `failed++` then continues; no ceiling constant anywhere in the file |
| **N-6** | No free-disk check anywhere | **CONFIRMED** | grep for `df`/disk guard across `scripts/` and `harness/src/main/` returns nothing |
| **N-20** | etcd runs on its default 2 GiB backend quota, no auto-compaction | **CONFIRMED** | `grep -c 'quota-backend-bytes\|auto-compaction' goldens/etcd-size3-start-stop.txt` → **0** |
| **N-17** | `analyse.py` never merges `.hlog` histograms | **CONFIRMED** | The only three matches for `hlog`/`hdrhistogram` are the docstring and an unconditional NOTE. F3 (latency CDF) is unproducible today |
| **N-9** | Cost estimate fails **open** on an unpriced type | **CONFIRMED** | `main.tf:196` `lookup(local.hourly_eur, v.type, 0)` |
| **N-8** | `main.tf`'s own header command cannot produce a plan | **CONFIRMED** | `main.tf:20` uses `-var hcloud_token=dummy-local-check` (18 chars); hcloud requires exactly 64. `LOCAL_TESTING.md §3.3` is correct — only the header is stale |
| **N-11** | `EXECUTION_AND_COST_MODEL §6` rsyncs a `logs/` that does not exist | **CONFIRMED** | line 222 `rsync … :logs/`; logs are written to `<run-dir>/logs/`, already captured by the results rsync |
| **N-14** | `collect_block.sh` hardcodes `results/` while `--out` is an operator argument | **CONFIRMED** | `collect_block.sh:31` |
| **N-19** | Repeated collection duplicates the whole tree per dated dir | **CONFIRMED** | `DEST=$3/$(date …)` + full rsync each time |
| **N-12(a)** | Baseline runs of the six driver systems collect no SUT logs | **CONFIRMED** | `RemoteRunner.java:206` guards `collectSutLogs`/`collectAuxLogs` behind `if (faultRun)` |
| **N-12(b)** | "the `docker events` audit is not built at all" | **OBSOLETE — closed by the merge** | `RemoteLogs.dockerEvents` exists; `RemoteRunner:213` calls it for *every* run; `gateContainerRestarts` reads `events/`. True at `9e9fbdd`, false now |
| **N-10** | `runcmd` races the docker daemon | **PLAUSIBLE, not executed** | Nobody has booted the image. The lint caught the shape; only a real boot settles it |
| **N-13** | `RemoteLogs` holds ~2× the log in heap | **PLAUSIBLE** | Unmeasured; HotStuff logs are the risk |

### The consequence chain N-16/N-5 create, stated plainly

With no Prometheus on the obs VM, `RemoteRunner.exportMetrics` still creates
`metrics/` and `PrometheusExporter` still writes **header-only** files for
every query. `ValidityChecker` then sees `hasMetrics == true` with every
series empty, and the §4 meta-rule makes that a **FAIL**, not a SKIP. So:

> every run of the campaign → `valid: false` → every cell grades **C** →
> `analyse.py` excludes **every run**. The campaign produces zero analysable
> data and bills in full.

This is why N-16 is first in the queue and why the canary must prove
`up{role=...}` before any block runs.

---

## 2. Verdicts — the author's own registry (C/M-series)

From `reference/experimental-plan-v-final.txt` §5. These were written against
`eighth-review-tier1`, i.e. essentially the tree we now have, and they are
**sharper than the pack's N-series**. Two of them delete whole systems' data
while the suite is green.

### C1 — every HotStuff cell is discarded. PROVEN BY EXECUTION.

`RemoteRunner.writeHotStuffResults` emits a manifest with **no `error_rate`**
(`grep -c error_rate` over the template → 0). `gateBaselineErrorRate` treats a
missing field as FAIL ("an unmeasured error rate is not a zero one"), and
HotStuff's scenario is *always* `baseline`, so the SKIP branch never fires.

Executed against the shipped jar, on a manifest copied verbatim from the
emitting code:

```
valid = false
  FAIL  baseline_error_rate :: manifest carries no numeric error_rate …
```

and then through the analysis:

```
analysed 0 runs into 0 cells (1 excluded)
dir,reason
…/hotstuff/baseline/size4/rate1000r01,"validity.json: invalid (baseline_error_rate)"
```

**Chapter 11 gets nothing.** Note the mechanism, because it is instructive:
C1 is a *side effect of F52/D15*, the gate the eighth review added to catch a
49 %-error baseline. The repo's own ledger did not catch the interaction; the
author's registry did.

A second defect surfaced in the same run: the HotStuff manifest also omits
`window`, so `window_headroom` SKIPs as "pre-M5.3 format". Fix both together.

### M2 — every CometBFT fault run is discarded. CONFIRMED.

`export_queries.txt:38` defines `cmt_rounds` as `cometbft_consensus_rounds` —
a **gauge** that returns to 0 once a height commits. `ValidityChecker:542`
routes `tendermint` to `counterIncreasesInWindow` (`last > first`), so a chain
that took extra rounds *and recovered* reads 0 → 0 and gate 3 FAILs. The fix
is the branch that already exists one line away: `seriesExceedsZeroInWindow`,
exactly as `kafka_urp` uses.

### M3 — the "9 of 11 gates" figure is not what a `remote-run` produces. CONFIRMED.

`RemoteRunner.java:199` runs `ValidityChecker.check`; `:213` writes
`events/`. The check therefore reads a directory that does not exist yet, so
`container_restarts` SKIPs and paxi/hotstuff `fault_ground_truth` SKIPs on
every `remote-run`. The campaign path is saved only because
`MatrixRunner.gradeCells` re-runs the check at block end and overwrites
`validity.json`. Fix is to move the two collectors above line 199.

### The rest, as recorded (not independently re-executed)

| # | Claim | Verdict |
|---|---|---|
| **C4** | `KafkaDriver` pins RF=3 / 6 partitions, so E-SCALE does not measure what it claims | **ACCEPTED — touches goldens, see §4** |
| **C3** | Ops that hit the 5 s driver bound leave the histogram entirely, unevenly by system | **ACCEPTED** — add the timed-out count to the manifest |
| **C2** | `analyse.py` never opens `summary.txt`, so a HotStuff cell contributes `{'n': N}` | **ACCEPTED — declared, not fixed** |
| **M1** | Exporter writes series-major CSV; the checker ignores the instance column, so the counter check compares node1's first sample to node3's last | **ACCEPTED** — `ValidityChecker`'s own javadoc admits the column is ignored |
| **M4** | Accumulated open-loop backlog is discarded at loop exit → p99/max understated, worst for the system that stalled most | **ACCEPTED** |
| **M6** | Warmup is judged by *completion* time while latency is charged to *intended* time | **ACCEPTED** |
| **M5/M8/M9** | `events_dropped` is written but no gate reads it; grade **A** is unreachable (`durability` always SKIPs); the SKIP set is read from `reports.get(0)` only | **CONFIRMED independently** — see §3 |

---

## 3. Found by this review, in neither source

1. **`ConfidenceGrade` reads the SKIP set from one arbitrary run.**
   `ConfidenceGrade.java:110` derives the cell's skipped gates from
   `reports.get(0)`. `MatrixRunner.specs()` shuffles, so *which* run that is
   is non-deterministic; a cell whose run 3 lost its metrics still grades on
   run 0's gates. (The author's M9 names the same line — independent
   agreement, and no test covers it: `ConfidenceGradeTest:51` uses a uniform
   SKIP set.)
2. **`baseline_error_rate` is enforced but appears in no methodology
   document.** Its `GateSpec` cites "§4.1 / F52", yet `error_rate` and `F52`
   appear nowhere in `DATA_ANALYSIS_METHODOLOGY.md` or
   `METRICS_AND_SOURCES.md`. Under our own authority order the methodology
   doc wins on method, so this is drift — the mirror of F40.
3. **The merge commit's gate is now green.** `38eb11f` was recorded as "never
   been green". Executed 2026-09-02: `Tests run: 243, Failures: 0, Errors: 0`,
   BUILD SUCCESS, **05:48**, 37 classes run against 37 on disk. F76 did not
   recur.
4. **`seventh-review-tier1` is not a strict subset of `main`.**
   `PROJECT_STATE §7.1` says it is and §7.3 deletes both branches together;
   `git log main..seventh-review-tier1` returns `81794f7`
   (`docs/SESSION_CLOSE_2026-08-15.md`). The knowledge survives in §5; the
   file does not.
5. **`export_queries.txt` has 23 queries**; `README.md:59` and
   `LOCAL_TESTING.md:430` say 25 — and LOCAL_TESTING states it as *expected
   output*, so following that doc shows a false failure.

---

## 4. The sequencing defect in the pack's plan — read this before scheduling

`03-BENCHMARK-PLAN.md §1` puts **G2 at step 1** and **C4 at step 2**, where
C4's definition of done is "goldens and docs updated".

**That invalidates the G2 signature the moment it is given.** Our own
`PROJECT_STATE §7.2` gate 6 states the rule — *"Goldens re-read if touched"* —
and flags that it already bit us once.

Two changes on the list touch golden text:

* **C4** — Kafka RF / `min.insync.replicas` / partition count → `kraft` and
  `kafka_zk` goldens.
* **N-20** — etcd `--quota-backend-bytes` / auto-compaction → both etcd
  goldens.

**Therefore: every golden-touching change lands *before* G2, not after.**
This is the organising constraint of Phase 0 in `PROJECT_STATE.md` §6.

---

## 5. Patch applicability — tested, not assumed

`git apply --check` fails for all five (the patches are relative to their
containing directory). Re-tested with the strip levels `patches/README.md`
documents:

| Patch | `patch --dry-run` | Action |
|---|---|---|
| `main-tf-hardening.patch` (`infra/`, `-p1`) | **clean** | apply |
| `cloud-init-hardening.patch` (`infra/`, `-p1`) | **clean** | apply |
| `collect_block-hardening.patch` (`scripts/`, `-p1`) | **clean** | apply |
| `MatrixRunner-circuit-breaker.patch` (`-p2`) | **2 of 3 hunks FAILED** | **re-implement** |
| `Main-breaker-exitcode.patch` (`-p1`) | **2 of 2 hunks FAILED** | **re-implement** |

The two Java patches are stale because the merge rewrote both files
(`MatrixRunner` +276 lines, `Main` +61). The *design* is sound and should be
kept; the diff cannot be. Re-implementation must additionally:

* preserve the `journal(...)` calls and the `gradeCells(b)` tail that did not
  exist when the patch was written;
* decide whether `gradeCells` still runs after an abort (recommend **yes** —
  grade what exists);
* handle **N-18**: `runHotStuff` throws `UnsupportedOperationException` for
  every non-BASELINE cell, so three such cells in a row would trip the breaker
  on a by-design refusal. Count unsupported cells as *skipped*, not *failed*.

`Summary` gains an `aborted` component; nothing constructs `Summary` outside
`MatrixRunner`, so that is safe.

The scripts (`render_obs_config.sh`, `disk_guard.sh`, `campaign_watchdog.sh`,
`cloudinit_lint.py`, `preflight.sh`, `node_facts.sh`) are new files and drop in
as-is. The `tests/*.tftest.hcl` suites were executed by the pack (8 runs) and
install alongside the `main.tf` patch — **install them together or start red**,
because two runs only pass with the patch.

---

## 6. Campaign shape — measured, not argued

The pack's **N-1/D-6** says `Simulations.standard` runs the full Cartesian
product. Verified by executing the shipped jar's own dry run:

```
campaign-run --system etcd --simulation standard --rates 1000,2000,3000,4000 --dry-run
  → 120 cells:  20 BASELINE · 40 PACKET_LOSS · 20 each of the other three
```

It is **worse than the pack measured**, because D14's severity sweep (added
after `9e9fbdd`) doubles PACKET_LOSS: 100 cells at `9e9fbdd`, 120 now, against
a cost model that assumed **71**.

The two-block alternative needs **no code at all** — both invocations already
exist:

```
--scenarios baseline --rates 1000,2000,3000,4000 --reps 5        → 20 cells
--scenarios packet_loss,network_partition,slow_node,double_kill \
            --rates 3000 --reps 5                                 → 25 cells
```

**45 cells against 120**, a 62 % reduction, and the discarded cells are ones
no planned figure consumes. Adopt it (`PROJECT_STATE` D16).

---

## 7. What this review did NOT verify

* **No Hetzner API call of any kind.** `cpx21`'s withdrawal, `cx33`'s
  availability and every price are taken from the pack, which took them from
  third-party trackers. **P3.5 / Canary Stage 0 is the only authority** —
  `hcloud server-type list` and `hcloud pricing` settle all of it for free.
* **No `terraform` run.** The pack used OpenTofu 1.10.6; `terraform test` has
  never executed here.
* **No boot of `cloud-init.yaml`.** N-10 stays plausible-not-proven.
* **C4, C3, M1, M4, M6** were accepted from the author's registry on the
  strength of its file:line accuracy elsewhere; they were **not** independently
  re-executed here.
* **The literature half (`01`–`06`) was not audited.** Its bibliography,
  chapter plan and citation verdicts are outside this pass; the only items
  taken from it are the ones with a bearing on the campaign
  (D-5 n=20, D-6 two blocks, D-9 corpus purge).
* **The Greek-language originals** in `reference/` were read only for the
  defect registry (§5 of the plan), not in full.
