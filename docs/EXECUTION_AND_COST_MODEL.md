# Execution & Cost Model — One System at a Time, and What the Campaign Really Costs

Created 2026-07-09 to answer, with evidence, four operator questions:
(1) does the harness test ONE consensus system at a time? (2) would a
separate Terraform per algorithm save money? (3) exactly which servers, for
how long, per algorithm and scenario? (4) how does every produced artifact
get back to the laptop so the cluster can be destroyed and analysis runs
locally at zero cloud cost?

Companion to `CAMPAIGN_RUNBOOK.md` (which keeps the operational summary —
its §2 phase table is the condensed view of §5 here). Authority order
unchanged: live code > plan+methodology > this file. Nothing here replaces
an existing decision; D1–D11 stand.

---

## 1. The direct answer: one system under test at a time — by design

The harness benchmarks **exactly one system per run, and one system at a
time on the cluster**. This is not an accident of the current skeleton; it
is load-bearing design, visible at every layer:

- **Harness**: `WorkloadEngine` is constructed with ONE `ConsensusDriver`
  (`WorkloadEngine.java` constructor); `Main` builds one driver per
  invocation. There is no code path that drives two systems concurrently.
- **Methodology §1**: "run order is randomized *within each system block*"
  — the experimental design is a sequence of per-system blocks, each block
  a batch of runs against that system only.
- **Scenario typing**: `Scenario.mutatesCluster()` forces a **fresh cluster
  per repetition** for fault runs — clusters are disposable per cell, which
  only makes sense in a serial model.
- **Infrastructure**: one loadgen VM drives the open-loop schedule. Driving
  two systems at once from one loadgen would split its CPU between two
  measurement loops and trip the client-not-bottleneck validity gate by
  construction.

What "deploy" means, precisely: `terraform apply` creates the **substrate**
(VMs, network, monitoring). The system under test lives in **containers
that the harness starts and stops per block/cell** on those VMs
(RemoteSshProvider, M4 — designed, not yet built; locally this is exactly
what `LocalDockerProvider` already does). So the cluster is shared
infrastructure; the SUTs cycle through it one at a time:

```
 terraform apply (phase shape)                          terraform destroy
 ──────┬──────────────────────────────────────────────────────┬─────────►
       │  etcd block   KRaft block   Kafka+ZK block   Paxi …  │   time
       │ ┌──────────┐ ┌───────────┐ ┌─────────────┐           │
 VMs:  │ │etcd ctnrs│→│KRaft ctnrs│→│broker+zk ctn│→ …        │
       │ └──────────┘ └───────────┘ └─────────────┘           │
       │  (containers started/stopped by the harness per cell;│
       │   fresh cluster per mutating repetition)             │
```

**Never two SUTs concurrently.** Two systems on the same nodes would
contend for the same 2 vCPUs (both runs invalid: stationarity + resource
gates); two systems on separate simultaneous clusters would need a second
loadgen and buy nothing (see §3).

## 2. Would a Terraform per algorithm save money? No — and here is the math

Hetzner bills per hour **for every server that exists, powered on or off;
`destroy` is the only off-switch.** Cost is therefore
`Σ (server_type_rate × hours_it_exists)` — nothing else.

The total VM-hours needed to run N systems' blocks is the same whether the
blocks run (a) serially on one apply, (b) each on its own apply, or (c) in
parallel on N clusters. What changes is overhead:

| Model | Extra cost | Validity | Verdict |
|---|---|---|---|
| **(a) One apply per phase, systems serially** | none — loadgen+obs amortized over all blocks; one boot/teardown | Hardware held constant across every compared system (Hoefler/Belli: fix the environment within a comparison); one Prometheus timeline | **Recommended default** |
| (b) One apply per system ("session mode") | ≈ +0.5 h boot/teardown per session (~€0.15 each) — negligible | Each system lands on **different physical hosts** (spread group re-places on every apply): a hardware-placement confound *between* systems that model (a) doesn't have; mitigated (dedicated vCPU, same type, same DC) but real | **Supported fallback** — for interrupted work, one-system re-runs, or budget spread over weeks. The confound gets one sentence in §7 of the methodology when used |
| (c) N parallel clusters | +N−1 loadgens (+obs or scrape complexity), same total node-hours | Cross-cluster network contention in one DC; N Prometheus timelines to merge; the loadgen fleet doubles the calibration surface | **Rejected** — pays more to add threats |

So: **per-algorithm Terraform is already unnecessary** — and the split that
*does* matter is already implemented: `infra/main.tf` is phase-parameterized
(`consensus_node_count`, `consensus_node_type`), because the only real
infrastructure differences are node COUNT (D8 scalability needs 7) and node
CLASS (D9 BFT needs ccx23) — not which algorithm runs. Same file, three
variable sets, verified by dummy-token plans (PROJECT_STATE §3).

Session mode needs zero new code: `terraform apply` → run one system's
block → collect (§6) → `terraform destroy`. The campaign runner (M3.3) must
simply support running a single system block — added to its requirements.

## 3. Why serial-shared beats everything on validity, not just cost

- **Comparability**: every CFT system measured on the *same* physical
  placement, same NICs, same disks. The thesis's headline F6 (KRaft vs
  Kafka+ZK) compares two blocks run hours apart on identical hardware —
  the strongest version of that comparison we can buy.
- **Isolation**: a block owns the whole cluster. No cross-SUT cache,
  network, or disk interference — nothing for a reviewer to attack.
- **The one caveat of serial**: time-correlated drift *across* blocks (the
  DC could be busier on Tuesday than Monday). Mitigations already designed:
  dedicated vCPUs (steal gate ~0), randomized scenario order within blocks,
  CPU-steal + stationarity validity gates per run, and n=5 spread through
  the block. Residual risk is documented in methodology §7 — and it is
  strictly smaller than model (b)'s placement confound, since at least the
  hardware is identical.

## 4. Servers per algorithm — exactly what each block needs

Always present: **loadgen (ccx13, D11)** + **obs (cpx21)**. Consensus nodes
by system (from `SystemUnderTest` + D8/D9/D10):

| System | Consensus nodes | Node type | Phase | Notes |
|---|---|---|---|---|
| etcd | 3 | ccx13 | A | jetcd driver (done, P2.1) |
| KRaft | 3 | ccx13 | A | combined controller+broker |
| Kafka+ZK | 3 | ccx13 | A | ZK colocated on broker nodes (D10) — still 3 VMs |
| Paxos (Paxi) | 3 | ccx13 | A | + D7 conflict sweep cells |
| EPaxos (Paxi) | 3 | ccx13 | A | + D7 conflict sweep cells |
| etcd, KRaft @ size 5,7 | 7 | ccx13 | B | provisioned only for these cells (D8) |
| CometBFT | 4 | ccx23 | C | n=3f+1, f=1 (D9 upsized) |
| HotStuff | 4 | ccx23 | C | least instrumented — logs ARE its metrics (§6!) |

Phase shapes (one `main.tf`, variables): A = 3×ccx13+support (€0.292/h);
B = 7×ccx13+support (€0.567/h); C = 4×ccx23+support (€0.636/h).
Phase A deliberately provisions 3 consensus nodes, not 4 — every CFT block
needs exactly 3 (v6's always-4 shape paid for an idle node all campaign).

## 5. Time and cost per algorithm — the full budget

Per-run arithmetic (methodology §1; *pilot-refined* at M6.2): standard run
= 180 s warmup + 300 s measurement + ~2 min recycle ≈ **10 min**; failover
trial = 180 s + 180 s + recycle ≈ **8 min**.

**One CFT/BFT system block** (baseline + faults + failover):

| Work item | Runs × min | Minutes |
|---|---|---|
| Saturation search (closed-loop sweep) | ~1 × 30 | 30 |
| Saturation, n=5 | 5 × 10 | 50 |
| Rate sweep 25/50/75% × n=5 | 15 × 10 | 150 |
| Faults: packet_loss **×2 severities (D14: 5% and 30%)**, partition, slow_node, double_kill × n=5 | 25 × 10 | 250 |
| Failover: leader_kill ≥30 trials | 30 × 8 | 240 |
| **Block total** | **76 runs** | **720 ≈ 12.0 h** |

*D14 delta (2026-08-14):* sweeping packet-loss severity adds 5 runs
(~50 min, ≈ **+€0.25**) per system block — so per-system hours move 11.2 →
12.0, and the campaign total moves ~+6 h / **≈ +€2** against the ~€61
contingency-inclusive figure below. Well inside noise; the per-system and
phase tables that follow are stated at the pre-D14 11.2 h basis and should
be read with this delta applied.

*F71 RESOLVED 2026-08-14 (D15.4):* the failover row's 8-minute trial shape
(180 warmup + 180 measurement, fault at +60) is **confirmed as correct** —
it gives 120 s of post-fault observation against the code's 60 s and
satisfies methodology's ±60 s window with room after it. This table is
therefore right and the CODE is what changes; `campaign-run` cannot express
this shape today, which is the plumbing half of D15.4. Net effect on the
budget: none here, and ~5 h CHEAPER than the shape the code currently runs.

**D7 conflict addendum** (Paxos and EPaxos only): per extra c-point
(2%, 10%): re-search 30 + saturation 50 + sweep 150 = 230 min. Two points →
**+7.7 h per Paxi system**.

**Per-system cost** (block on phase-A shape, €0.292/h; standalone session
adds ~€0.15 boot/teardown):

| System | Hours | € (in-phase) |
|---|---|---|
| etcd | 11.2 | 3.3 |
| KRaft | 11.2 | 3.3 |
| Kafka+ZK | 11.2 | 3.3 |
| Paxos + conflict | 18.9 | 5.5 |
| EPaxos + conflict | 18.9 | 5.5 |
| **Phase A total** | **~71 h (~3 days)** | **~€21** |
| etcd+KRaft @ {5,7}, baseline-only (4 cells × ~230 min) | ~15 h | ~€8.7 (at €0.567/h) |
| CometBFT | 11.2 | 7.1 (at €0.636/h) |
| HotStuff | 11.2 | 7.1 |
| **Phase C total** | **~22 h (~1 day)** | **~€14** |
| G3 calibration + M6.2 pilot (phase-A shape) | ~10 h | ~€3 |
| **Campaign total** | **~118 h ≈ 5 unattended days** | **≈ €47; ≈ €61 with 30% rerun contingency** |

Price basis: post-2026-06 Hetzner repricing (ccx13 €0.0689/h, ccx23
€0.1378/h; cpx21 unconfirmed — task P3.5 verifies via `hcloud server-type
list` before first apply). Billing reminders that dominate real cost risk:

- A **forgotten cluster** costs ~€7/day (phase A shape) — a weekend ≈ €21,
  as much as all of Phase A. The discipline (runbook §7): destroy after
  every phase/session, verify `hcloud server list` is empty. This, not the
  benchmark design, is where money actually leaks.
- Powered-off servers still bill. Detached volumes/floating IPs would bill
  too — our TF creates none (destroy is provably complete).
- Traffic is a non-issue: repatriating all artifacts is a few GB against
  20 TB/month included egress.

**The honest cost conclusion**: the entire scientific campaign costs less
than €70. No design parameter (300 s windows, n=5, ≥30 trials, dedicated
vCPUs, warmups) is worth weakening to save single-digit euros — the only
cost lever worth operating is *destroy discipline*, and the only structural
choice that matters (serial on shared infra, phase-shaped TF) is already
the cheap one. Cutting n=5→3 would save ~€4 and cost the CIs their meaning.

## 6. Getting everything off the cluster — then destroy, analyze locally

Design intent (already in the plan): **analysis is 100% local** — analyse.py
v2 + figures consume the results tree; Grafana is live monitoring only.
Nothing on any VM may be the sole copy of anything at destroy time.

Artifact inventory — what exists, where, and its collection status:

| Artifact | Where produced | Collection status |
|---|---|---|
| `throughput.csv`, `latency.csv`, `latency.hlog`, `manifest.json` per run | loadgen, results tree | ✅ implemented (harness writes them) |
| `metrics/*.csv` (Prometheus query_range per run) | loadgen (PrometheusExporter, M5.4) | ⬜ designed (P4.2) |
| `validity.json` per run | loadgen (ValidityChecker, M5.5) | ✅ built + hardened 2026-07-21 (library; M5.4 wires it per run) |
| **SUT container logs** (per node, per block) | consensus nodes, docker | ❌ **GAP found by this review — no doc or task covered it.** HotStuff's client SUMMARY lines (its only metrics source) and fault forensics (why did recovery take 9 s?) live in these logs. New task **P4.5** |
| `docker events` audit (validity gate 4: no unexpected restarts) | consensus nodes | ❌ same gap — folded into P4.5 |
| Harness log (the campaign's own narrative) | loadgen stdout | ⬜ campaign runner redirects to file (M3.3 requirement, noted) |
| Prometheus TSDB snapshot (raw explanation layer, beyond the per-run CSVs) | obs VM | ⬜ runbook §4 has the mechanism (admin API enabled); executed once at phase end |
| Grafana dashboards | repo (as code) | ✅ nothing to collect |

End-of-phase collection protocol (runbook §4, now with logs):

```bash
# 1. from the laptop — pull the whole results tree + logs + snapshot
rsync -a root@$PUBLIC_LOADGEN:results/ ./campaign/results/
rsync -a root@$PUBLIC_LOADGEN:logs/    ./campaign/logs/        # P4.5 output
# TSDB snapshot: POST /api/v1/admin/tsdb/snapshot on obs, then rsync it
# 2. VERIFY before destroying — count manifests vs expected cells:
find ./campaign/results -name manifest.json | wc -l    # == cells run
grep -L '"status": "complete"' $(find ./campaign/results -name manifest.json)
# 3. only then: terraform destroy && hcloud server list   (must be empty)
```

Analysis then runs on the laptop against `./campaign/` — no VM needed ever
again; any figure regenerates from the archive alone (plan M6.4 acceptance).

## 7. Best-practice cross-check (do we hinder results for cost anywhere?)

Checked against the methodology's own anchors:

- **Fix the environment within a comparison** (Hoefler & Belli SC'15):
  model (a) does exactly this; model (b) is the documented deviation. ✅
- **Never trade measurement window for money**: 300 s windows and 180 s
  warmups stay — the stability and convergence gates need the length, and
  the entire saving from halving them would be ~€10. ✅ not traded
- **Repetition-level variance** (Kalibera & Jones): n=5 within one
  environment instance measures within-setup variance; across-setup
  generalization is *already* declared out of scope (methodology §7
  external validity: "one datacenter, one VM class"). Session mode (b), if
  used, accidentally adds a tiny across-setup sample — worth a sentence,
  not a design change. ✅
- **Dedicated vCPU non-negotiable** (steal = invisible corruption): kept
  everywhere a measurement runs, including the loadgen (D11). The only
  shared-vCPU box is obs, which is never measured. ✅
- **Coordinated omission, pooling, failover events**: harness-level, cost
  independent — implemented (P1). ✅

One place where cost *did* shape design, honestly labeled: D9's BFT nodes
are ccx23 (≈€0.14/h) rather than ccx33/48 (≥€0.19/h) — approaching, not
matching, HotStuff's published 16-vCPU class. That is a documented
threat-to-validity decision (MASTER_PLAN D9), not a hidden one, and BFT
numbers are within-family comparisons only.

## 8. Runway before any euro is spent (implemented vs needed)

Implemented and verified today: engine + validity-grade instrumentation
(P1 complete), etcd production driver (P2.1), local one-command loop, TF
phase shapes (validated, never applied).
Still required before the FIRST apply (all gated): remaining drivers P2.2–
P2.5 → **G1**; RemoteSshProvider + FaultInjector + golden tests P3.3 →
**G2 human review**; canary P3.4 (<€0.10); ValidityChecker/PrometheusExporter
P4.1–P4.3; campaign runner M3.3 (with single-block session mode + log
collection). The cost model above only starts ticking after G2.

## 9. Decision record + tangible actions

**Decisions (2026-07-09):**
1. Execution model = **(a) one apply per phase, system blocks serial**;
   session mode (b) supported as fallback with its confound documented;
   parallel (c) rejected. No per-algorithm Terraform.
2. Analysis is local-only after collection; **nothing is a sole copy on a
   VM at destroy time**; collection is verified (manifest count) *before*
   destroy.

**New tangible tasks (added to PENDING_TASKS):**
- **P4.5 — SUT log + docker-events capture per block** (closes the gap in
  §6; HotStuff hard-depends on it, gate 4 needs the events audit).
- **M3.3 amendment** — campaign runner must support a single-system block
  ("session mode") and redirect its own log to a file under `logs/`.
- **P3.5 (existing)** — price verification pre-apply now also confirms the
  billed-while-stopped behavior on the current tariff.

## Honest review of this document

Confidence levels: the *execution model* conclusions (§1–3) are grounded in
code and methodology text quoted above — high confidence. The *time budget*
(§5) inherits the runbook's per-run arithmetic, which is budget-grade until
the M6.2 pilot fixes real recycle times and saturation-search length; the
per-phase totals could move ±20%, which at these prices moves the campaign
by ±€10 — the conclusions survive. The *price basis* has one unverified
cell (cpx21) and a general P3.5 re-check before apply. The log-capture gap
(§6) is the one finding that changes the backlog; everything else confirms
the existing plan is already the cost-efficient shape, so no plan rewrite
is needed — only the two task additions above.
