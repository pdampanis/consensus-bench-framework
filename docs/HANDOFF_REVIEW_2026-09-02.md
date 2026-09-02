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

### C1 — every HotStuff run is discarded. PROVEN BY EXECUTION. But fixing it is not enough — see C2.

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
`window`, so `window_headroom` SKIPs as "pre-M5.3 format". `window` *is*
available from `spec.window()`; **`error_rate` is not** — `HotStuffSummary`'s
nine fields carry no error or failed-op count, so the gate-side SKIP is the
only implementable fix.

**And C1 alone buys nothing.** Executed with the manifest patched:
`valid = true`, and then `analyse.py` produces
`hotstuff,baseline,4,0.0,1000,1` — an `n` and no numbers. `analyse.py` builds
every figure from `read_throughput`/`read_latency`; HotStuff writes neither,
and nothing in the file opens `summary.txt`. **That is C2, and it must ship
with C1 or Chapter 11 is no better off.**

### M2 — CometBFT `kill`/`double_kill` fault runs are discarded. CONFIRMED, but narrower than stated.

`export_queries.txt:38` defines `cmt_rounds` as `cometbft_consensus_rounds` —
a **gauge** that returns to 0 once a height commits. `ValidityChecker:591-592`
routes `tendermint` to `counterIncreasesInWindow` (`last > first`), so a chain
that took extra rounds *and recovered* reads 0 → 0 and gate 3 FAILs. The fix
is the branch that already exists one line away: `seriesExceedsZeroInWindow`,
exactly as `kafka_urp` uses.

**Not "every" fault run.** `:593` also passes the gate on a `node_up` drop,
which rescues `partition` and `packet_loss`. The bite is `kill` and
`double_kill` — where the gate's own javadoc says `node_up` cannot witness a
`docker kill`. The first draft of this file said "every"; that was
overstatement.

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
| **C4** | "`KafkaDriver` pins RF=3 / 6 partitions" | **HALF WRONG, and this review propagated it.** `KafkaDriver.java:95` already does `rf = Math.min(3, bootstrap.size())` — RF is not pinned. The real defects are the **cap at 3** (a size-5/7 scalability cell still replicates to 3, so E-SCALE compares nothing) and **`PARTITIONS = 6`**. **It does NOT touch goldens** — see §4 |
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

**Exactly one** change on the list touches golden text:

* **N-20** — etcd `--quota-backend-bytes` / auto-compaction → both etcd
  goldens. **This one is real.**
* **C4 does NOT** — and the first draft of this file said it did. Golden text
  is only what `RemoteSshProvider` emits, and `RemoteSshProvider.java:352`
  already computes `rf = Math.min(3, clusterSize)` with
  `txnMinIsr = rf >= 3 ? 2 : 1`; at size 3 — the only size a Kafka golden
  exists for — the corrected values are **byte-identical** to
  `kraft-size3-start-stop.txt` (`REPLICATION_FACTOR=3`, `MIN_ISR=2`). The
  partition count lives in `KafkaDriver.java:61`, client-side, and appears in
  no golden.

**The rule still holds — every golden-touching change lands before G2 — but
it binds one item, not two.** Everything else in Phase 0 is scheduled by the
older and sounder rule: *do first whatever cannot be fixed after the VMs are
billed*. Stating that honestly matters, because a constraint that sounds
load-bearing and is not will be trusted the next time it is invoked.

**And it cannot be fully honoured.** N-20's quota must be chosen in Phase 0,
before canary check 2.16 measures `etcd_mvcc_db_total_size_in_bytes`. If the
measurement contradicts the guess, that golden is re-read and re-signed. The
plan says so at Phase 3.1 rather than hiding it.

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
  → 120 RUNS (= 24 cells at n=5):  20 BASELINE · 40 PACKET_LOSS · 20 each of the rest
```

It is **worse than the pack measured**, because D14's severity sweep (added
after `9e9fbdd`) doubles PACKET_LOSS: 100 runs at `9e9fbdd`, 120 now.

**Two corrections to the first draft of this section, both mine.**

1. **"against a cost model that assumed 71" was a misreading**, inherited
   from the pack (`08-COST-MODEL.md:53`, "71 runs each ≈ 11.2 h").
   `EXECUTION_AND_COST_MODEL.md:170` reads **"Phase A total | ~71 h"** — that
   is *hours across five systems*, not runs. The repo's live per-system
   budget is the table at `:134-138`: sat-search 30 min + sat 5×10 +
   rate-sweep 15×10 + faults 25×10 + failover 30×8 = **76 runs ≈ 12.0 h**,
   and it **already counts both packet-loss severities**.
2. **"cells" should read "runs" throughout.** D13 defines a cell as the n=5
   body of evidence, and `MatrixRunner.gradeCells` groups by everything
   except `runId`. 120 runs = **24 cells**; the alternative's 75 runs = 15
   cells. Multiplying the wrong noun by 5 gets the budget five times wrong.

So the honest claim is **not** a 62 % saving. It is: **`Simulations.standard`
does not implement the campaign the repo costed.** The runbook's shape is
~11.5 h against `standard`'s ~20 h, and the 45 extra runs buy fault×rate
combinations no planned figure consumes. That is a correctness argument about
experimental design, and it stands on its own.

The runbook's shape needs **no code at all** — the invocations already exist,
and the saturation search that *defines* R1..R3 comes first:

```
--scenarios baseline --rates 1000,2000,3000,4000 --reps 5        → 20 cells
--scenarios packet_loss,network_partition,slow_node,double_kill \
            --rates 3000 --reps 5                                 → 25 cells
```

With the saturation block (5 runs) and the failover block (30), that is
**75 runs ≈ 11.5 h** — reproducing the 76 ≈ 12.0 h the cost model already
plans. Adopt it (`PROJECT_STATE` D16).

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
  strength of its file:line accuracy elsewhere. **A follow-up adversarial pass
  (§8) checked all five at file:line: C3, M4, M6 and M1 hold; C4 was half
  wrong and is corrected above.** M4 needs restating precisely — in-flight
  operations are *not* lost (the drain plus the `durationSecs + 6` bucket
  array per F23); what is discarded is the *schedule* backlog, because
  `nextIntendedNanos` stops advancing at loop exit. M1 is worse than stated:
  because `inWindow` preserves file order, the two samples the counter check
  compares are not even monotonic in time.
* **No cost figure in this repo has ever been read from Hetzner.** Every
  euro — `cx33 = 0.0136`, `ccx33 = 0.2219`, the €0.55 canary, the ≈€80
  campaign — traces to third-party trackers via the pack, which says so
  itself. `hcloud pricing` at Phase 1.3 is the only authority.
* **Terraform resource counts** were taken from `LOCAL_TESTING.md §3.3`'s
  recorded plans (11/15/12) and the `6 + N_vms` formula derived from
  `main.tf`; no `terraform plan` was run here.
* **The literature half (`01`–`06`) was not audited.** Its bibliography,
  chapter plan and citation verdicts are outside this pass; the only items
  taken from it are the ones with a bearing on the campaign
  (D-5 n=20, D-6 two blocks, D-9 corpus purge).
* **The Greek-language originals** in `reference/` were read only for the
  defect registry (§5 of the plan), not in full.

---

## 8. The adversarial pass on THIS document, 2026-09-02

This file and `PROJECT_STATE.md` §5–§6 were put through a hostile review with
the repository as ground truth, in the same spirit as the pack's own two
passes. It reproduced the load-bearing claims — C1 end-to-end (including that
`MatrixRunner.gradeCells` offers **no rescue path**, since it re-runs the same
check), the N-16/N-5 chain (built the header-only-CSV fixture: 5 FAILs, then 0
runs analysed), the 120/20/25/30 dry-run counts, the patch table, 23 queries,
the 243-test gate and the `main..seventh` commit.

**It also found six blocking defects in my work.** All are corrected above and
in §6 of `PROJECT_STATE.md`; recording them rather than quietly folding them
in is the point:

| # | Defect | Where it came from |
|---|---|---|
| 1 | `terraform plan` shows **10** resources at `consensus_node_count=2`, not 12 — the count is `6 + N_vms` | Copied the pack's 6-VM Phase-C figure. Phase 1 told the operator to **stop** on a mismatch its own arithmetic guaranteed |
| 2 | **C4 does not touch goldens**, and "`KafkaDriver` pins RF=3" is false | Accepted from the registry without checking; then built D18's justification on it |
| 3 | "71" is **hours**, not runs; "cells" should read **runs**; the saving is ~4 %, not 62 % | Propagated the pack's misreading of `EXECUTION_AND_COST_MODEL:170` |
| 4 | The two-block design **deleted the saturation search** that derives its own rate points | Omission |
| 5 | The canary is **not invocable** without `--size 2` — `Main` defaults to 3 and the provider throws | Never ran the command |
| 6 | Fixing C1 still leaves Chapter 11 with `{n: 1}`; **C2 appeared nowhere in the plan** | Accepted C2 in §2 and forgot to schedule it |

**The lesson, and it is the project's own:** this review applied an exacting
standard to the things it already suspected, and none at all to the numbers
the operator would stand on at the spend gate. Every one of those six was
checkable offline in under a minute. A document whose rigorous sections make
its unrigorous ones look trustworthy is more dangerous than one that is
uniformly rough — so the figures in §6 and in Phase 1/2 now carry their
provenance at the point of use, not only in this section.
