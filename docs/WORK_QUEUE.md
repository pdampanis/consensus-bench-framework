# Work Queue — every open front, grouped into increments, ordered, scored

**Compiled 2026-08-14** against the tree at `eighth-review-tier1` `d7f0c59`
(suite **183 green**). This is the *prioritized cross-front view*: what to do
next and why, across the finding ledger, the simulation/rules plan, the
milestone plan, and the gate ladder — which until now existed only as four
separate lists with no common ordering.

Companion documents, unchanged in authority:
`PENDING_TASKS.md` = the findings ledger (evidence);
`SIMULATION_AND_RULES_ANALYSIS.md` = the design for S-increments;
`IMPLEMENTATION_PLAN.md` = milestone acceptance criteria (wins on those);
`CAMPAIGN_RUNBOOK.md` / `EXECUTION_AND_COST_MODEL.md` = campaign operations.

---

## What the confidence score means (read before using it)

**Confidence here = how likely this item lands as specified, first attempt,
without needing a new decision.** It is a *delivery* estimate.

It is **NOT** the D13/GRADE result-confidence grade. That one rates how far a
measured number can be trusted; this one rates how well-specified a task is.
Do not let the two words collide in conversation.

| Band | Meaning |
|---|---|
| **High 85–95%** | Red test already known or measured; no open decision; laptop-verifiable; no golden change |
| **Med 60–80%** | Approach clear, but a design sub-choice remains, or a dependency is not yet exercised |
| **Low 35–55%** | Needs a decision, or is first-contact with an unmeasured environment, or is a large multi-part build |

---

## The ordering logic (why this order and not the plan's)

Three constraints dominate, and they are not the ones the S0–S5 numbering
implies. **This queue deliberately revises the plan's own order** — stated
plainly rather than quietly:

1. **Goldens are FINAL text for G2.** Anything that edits a golden must land
   *before* the author reads them, or the read-through is done twice. Only
   two open items touch goldens: F69 and the packet-loss severity change.
   They jump the queue for that reason alone, not because they are the most
   valuable work.
2. **The canary is the designated proof of the live scrape path** (README
   caveat). So `PrometheusExporter` should land *before* the canary, not
   after it as `SIMULATION_AND_RULES_ANALYSIS.md` §6 implies — otherwise
   proving the scrape path costs a second provisioning cycle.
3. **Wiring `ValidityChecker` is one integration point and unblocks the whole
   validity story.** It sat mid-plan behind S1 and S2 for no reason. It has
   the best value-to-effort ratio in the entire queue and moves early.

A fourth, softer constraint: **the author-decision items cost no execution
time but block five downstream items.** Batching them into one sitting is
worth more than any single increment below.

---

## The queue

| # | Band | Increment | Items | Blocks / unblocks | Conf. |
|---|------|-----------|-------|-------------------|-------|
| ~~**1**~~ | P0 | ~~**Decision batch**~~ **DONE 2026-08-14** | All seven decided in one sitting — F52 (baseline-only 1% gate) · F55–F57 (docs move; slow_node timeout is a real fix) · F68 (widen to 0.1x–10x, stay in verify) · F71 (runbook shape wins) · F73 (deterministic cells + seeded rotation for failover) · F69 (scoped sweep). Recorded as **MASTER_PLAN D15**; the partition PREDICTION was rewritten too, before any data exists | — |
| ~~**2**~~ | P1 | **S1.1 — severity into Block AND run identity** | D14 plumbing: loss list in `Block`, `loss_percent` into manifest + `configHash`, severity into `RunIdentity`, golden updated | Pre-G2 (touches golden). Without the identity half, D14 makes data *worse* | **80%** | **DONE 2026-08-14**
| ~~**3**~~ | P1 | **S0.2 / F69 — host fault sweep** | `tc qdisc del` / scoped `iptables -D` / `pkill` into remote pre-clean; goldens first as spec | Pre-G2 (touches goldens). Blocked on #1's B5 | **45%** | **DONE 2026-08-14**
| **4** | P2 | **G2 golden read-through** (author, no code) | All 7 goldens vs their header checklists; sign-off line in PENDING_TASKS | **Blocks everything billed.** Must follow #2 and #3 | **90%** |
| **5** | P2 | **P3.5 price check** (author, ~10 min) | `hcloud server-type list` → sync `local.hourly_eur` + runbook §2 | Blocks first apply | **90%** |
| ~~**6**~~ | P3 | **S3.2 — wire ValidityChecker into the run flow** | `RemoteRunner` calls `check()` per cell | **Today no campaign run writes a `validity.json` at all.** Best value/effort in the queue | **90%** | **DONE 2026-08-14**
| ~~**7**~~ | P3 | **DONE** — **Hygiene batch** | F58–F67 · F72 · F74 · F75 · ledger re-scope of F15/F21/F25 · duplicate `NEXT-6` | Removes 6 noise rows; F75 decides picocli/micrometer | **90%** |
| ~~**8**~~ | P4 | **DONE** — **S3.1 — PrometheusExporter (M5.4)** | runbook §5: `query_range`, step 5 s, ±15 s pad → `metrics/*.csv`; empty series must FAIL not SKIP | **Must precede #9** or the live scrape path costs a second provisioning cycle | **60%** |
| **9** | P4 | **P3.4 canary** (first money, <€0.10) | One etcd cell on 2 VMs, end to end | First real-VM contact | **55%** ⬆ |
| | | *Confidence raised from 40% on 2026-08-14* | `HostFaultEffectTest` now proves on real ubuntu-24.04 containers that the faults TAKE EFFECT (netem installs, partition actually stops ping, heal restores it, stress-ng really runs and dies, the F69 sweep clears a leak); `PrometheusExporterTest` proves the export path against a real prom/prometheus:v2.53.0. What is STILL unproven at the canary and cannot be proven locally: sudo availability under the cloud-init user, cloud-init timing, Hetzner private-net routing, and `docker`/`sshd` stderr wording on the real image | |
| ~~**10**~~ | P5 | **DONE** — **S1.2 — named + serialized simulations** | `Simulations` constants; `simulation.json` written + hashed into manifests (D12) | The publishable spec artifact | **85%** |
| ~~**11**~~ | P5 | **S1.3 — `campaign-run --simulation` DONE** | Select by name; an unknown NAME fails closed against the real set, F32's rule applied to a value. **picocli/M1.3 deliberately NOT bundled** — rewriting the whole CLI surface is its own increment with its own risk, and bundling it would have made this one un-reviewable. Still queued as **#11b** | | — |
| ~~**12**~~ | P5 | **DONE** — **S2.1a — rules in the record** | Thresholds + § reference + false-positive text written into `validity.json`; contract test extended | Before M6.2 retunes thresholds | **80%** |
| ~~**13**~~ | P6 | **DONE** — **S3.3 — harness self-metrics :9400 (M5.3)** | micrometer (already in pom, unused) | Unlocks `window_headroom` gate | **70%** |
| ~~**14**~~ | P6 | **DONE** — **S3.4 — docker-events audit (P4.5 open half)** | Restart audit per block | Unlocks `container_restarts` + the paxi/hotstuff gate-3 witness | **65%** |
| ~~**15**~~ | P7 | **DONE (with #12)** — **S2.2 — false-positive triage in verdicts** | Verdicts name candidate benign causes; F68's pressure rule becomes machine-readable | | **75%** |
| **16** | P7 | **S4.1 — the journal** | Chaos-Toolkit-shaped per-simulation record; every cell, not just failures | | **75%** |
| **17** | P7 | **S4.3 — confidence grade (D13/GRADE)** | Per **cell**, ordinal, with named downgrade reasons | **HARD GATE: must not ship before #6, #8, #13, #14** | **60%** |
| **18** | P7 | **S4.2 — the report** | Generated `report.md` per simulation | Lowest value here. Delete if unread after the pilot | **55%** |
| **19** | P8 | **S5.1 / F54 — analyse.py fails closed** | Missing `environment`/`duration_secs` ⇒ exclude-and-list | ⚠ **OWNED BY THE OTHER SESSION** (its increment 4). Do not duplicate | **85%** |
| **20** | P8 | **S5.2 — analysis consumes the new record** | Reads `validity.json`, excludes on grade, stops dropping `null` failovers silently | Depends on #6, #17 | **80%** |
| **21** | P8 | **S5.3 / M6.4 — analysis v2** | Pooled `.hlog` histograms, Holm–Bonferroni, ECDFs, the 8 figures | Largest single build in the queue | **50%** |
| **22** | P9 | **Per-system remote smokes** | One short cell per system on Phase-A infra | | **45%** |
| **23** | P9 | **G3 / M6.1 cross-validation** | Harness vs native tools, ≤15% or explained | Parity historically swung 0.2×–2.8× on laptops (F27/F68) | **40%** |
| **24** | P9 | **M6.2 pilot** | Baseline ×2 per system; **fixes every provisional threshold numerically** | Feeds #12, #17 | **55%** |
| **25** | P9 | **M6.3 campaign** | Full matrix, ~118 h ≈ 5 unattended days, ≈€61 | Most exposure to unknowns | **35%** |
| **26** | P10 | **NEXT-4 — HotStuff fault scenarios** | Preregistration decisions first (target, `faults` semantics, client survivability) | HotStuff is BASELINE-only today | **40%** |
| **27** | P10 | **P2.6 — safety/durability oracle** | Gate 2 is a documented SKIP; Porcupine a stretch goal | | **50%** |
| **28** | P10 | **F8 / P2.0 — scheduler scaling** | Conditional: only if achieved < 99% of target | Do not build speculatively | **80%** |

---

## Hard review — what this pass changed

**Three ledger entries were stale. Verified, not assumed:**

- **F15** ("analyse.py/visualizer absent") — **half wrong.** `analysis/analyse.py`
  is in the repo and runs (`--selftest` green). Only the React visualizer is
  absent, and the methodology now renders figures from CSV via M6.4, so that
  half is arguably obsolete rather than pending. **Re-scope or close.**
- **F21** ("runId unescaped; campaign valsize unpinned") — **mostly done.**
  `MatrixRunner.block()` pins `valueSizeBytes=1024`, and campaign runIds are
  generated as `rate<R>r<NN>`, i.e. `[a-z0-9]+` *by construction*. The
  residual is that the guarantee is structural, not validated. **Re-scope.**
- **F25** ("test pins `privateIp()=="paxi2"`") — **still live but no longer a
  defect.** `NodeHandle`'s javadoc now defines the field per substrate (real
  IP remotely, alias locally where the alias *is* the address), so the test
  pins documented behaviour. **Close with that note.**

**One numbering collision:** there are two `NEXT-6` entries (F50b/c, and the
observability/analysis polish). Cosmetic, but this file is the handoff
contract — fix it in the hygiene batch.

**One ordering error in my own plan, corrected here:** `S3.1` (Prometheus
exporter) and `S3.2` (wire the checker) sat behind S1 and S2 in
`SIMULATION_AND_RULES_ANALYSIS.md` §6. That was wrong. S3.2 is a single
integration point that changes "no run is ever validity-checked" into "every
run is", and S3.1 must precede the canary or the live scrape path goes
unproven until the next provisioning cycle. Both moved up.

**What I am least sure of, honestly:**

- **#9 (canary) at 40%** is the queue's real risk concentration, not #25.
  Everything downstream of it assumes real-VM behaviour that has never been
  observed: sudo availability, `tc`/`iptables`/`ip route` output wording on
  ubuntu-24.04, cloud-init timing, private-net routing. The goldens encode
  *intent*, and golden tests structurally cannot catch semantic SSH errors —
  F28 is the proof (a green golden hiding a 30 s channel stall). Budget a
  session for fallout, and expect the fix to be "adjust one probe string and
  its golden, TDD".
- **#25 (campaign) at 35%** is low not because it is hard but because it is
  long: 5 unattended days is 5 days of unattended failure modes.
- **#17 (grade) at 60%** is the item most likely to *look* finished while
  being hollow. Its hard gate exists for that reason.

**What is deliberately NOT in this queue:** speculative buffer resizing
(F70's follow-on — needs a measured saturation number that does not exist
yet), a rule DSL, change-point detection, and any framework adoption. All
refused with reasons in `SIMULATION_AND_RULES_ANALYSIS.md` §3 and §7.

---

## The one-line answer to "what now"

~~**#1 — the decision batch.**~~ **DONE 2026-08-14** — all seven decided,
recorded as MASTER_PLAN **D15** (+ D14 earlier). **Now: #2 (S1.1)**, which
has absorbed D15.4's run shape and D15.5's targeting, followed by #3 (F69's
scoped sweep). Both touch goldens, so both land before the G2 read-through.
