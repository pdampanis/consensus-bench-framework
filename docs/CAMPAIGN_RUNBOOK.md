# Campaign Runbook — What Gets Spawned, For How Long, What It Costs, Where the Data Lives

Operational companion to `IMPLEMENTATION_PLAN.md` (M6) and
`DATA_ANALYSIS_METHODOLOGY.md`. This is the document a fresh session reads to
understand the *physical* campaign: topology, phases, durations, cost, storage,
and the Prometheus retrieval protocol. Authority order unchanged (live code >
plan+methodology > this file). Durations marked *pilot-refined* are finalized
by M6.2 before the campaign; treat them as budget-grade until then.

Created 2026-07-08, alongside the locally verified Terraform layer
(`infra/main.tf` + `infra/cloud-init.yaml`; `terraform validate` and
dummy-token `plan` green for all three phases — see PROJECT_STATE §3).

---

## 1. Topology — what `terraform apply` spawns

```
                        LAPTOP  (never in the measurement path)
     terraform apply/destroy · ssh admin (tunnels) · rsync results · git archive
          │ hcloud API                                     ▲ results tree
          ▼                                                │ (rsync per phase)
┌─ Hetzner project ── spread placement group ── firewall: public SSH only ──────┐
│                                                                               │
│              private network 10.0.0.0/24  ("thesis-net")                      │
│                                                                               │
│  consensus nodes (count & type are terraform variables)                       │
│  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌ ─ ─ ─ ─ ─┐ ┌ ─ ─ ─ ─ ─ ─ ─ ┐    │
│  │ node1 .11 │ │ node2 .12 │ │ node3 .13 │ │ node4 .14│ │ node5-7 .15-17│    │
│  │  docker:  │ │   (same)  │ │   (same)  │ │ BFT phase│ │ D8 phase only │    │
│  │  SUT ctnr │ │           │ │           │ └ ─ ─ ─ ─ ─┘ └ ─ ─ ─ ─ ─ ─ ─ ┘    │
│  │  node_exp │ │           │ │           │   Phase A: ccx13 (2 vCPU dedic.)   │
│  └─────▲─────┘ └─────▲─────┘ └─────▲─────┘   Phase C: ccx23/33 (D9, upsized)  │
│        │ consensus writes    │ scrapes :9100 :2381 :7071 :7000 :26660         │
│        │ (open-loop)         │                                                │
│  ┌─────┴──────────────┐   ┌──┴──────────────────┐                             │
│  │ loadgen .20  ccx13 │   │ obs .21  cpx21      │                             │
│  │ harness uber-jar   │──▶│ prometheus + grafana│                             │
│  │ results tree (CSV) │◀──│ (query_range API)   │                             │
│  └────────────────────┘   └─────────────────────┘                             │
│    D11: dedicated vCPU —     scrape 5s, retention 15d,                        │
│    the instrument itself     snapshot API for archiving                       │
└───────────────────────────────────────────────────────────────────────────────┘
```

Resources per apply (Phase A): 5 servers, 1 network + subnet, 1 firewall,
1 spread placement group, 1 SSH key, 1 generated inventory file — **11
resources, nothing else** (no volumes, no floating IPs), so `terraform
destroy` is provably complete and billing stops at zero.

## 2. Phases — one `main.tf`, three variable sets

> Detailed per-system time/cost tables, the serial-vs-parallel decision
> record, and the artifact-collection inventory live in
> `EXECUTION_AND_COST_MODEL.md` (2026-07-09) — this section is the summary.
> Core facts: **one system under test at a time** (serial blocks on shared
> infra); no per-algorithm Terraform (phases are the only real shapes);
> destroy discipline is the only cost lever that matters.

| Phase | Command (from `infra/`) | Shape | €/h | Runtime* | Phase cost* |
|-------|------------------------|-------|-----|----------|-------------|
| A — CFT baseline/faults/conflict (etcd, KRaft, Kafka+ZK, Paxos, EPaxos; size 3) | `terraform apply` (defaults) | 3×ccx13 + loadgen ccx13 + obs cpx21 | 0.292 | ~71 h | ~€21 |
| B — Raft scalability D8 (etcd, KRaft; sizes 5,7) | `-var consensus_node_count=7` | 7×ccx13 + support | 0.567 | ~15 h | ~€9 |
| C — BFT D9 (CometBFT, HotStuff; size 4) | `-var consensus_node_count=4 -var consensus_node_type=ccx23` | 4×ccx23 + support | 0.636 | ~22 h | ~€14 |
| G3 calibration + M6.2 pilot (on Phase A infra) | — | — | 0.292 | ~10 h | ~€3 |

**Total: ~118 h ≈ 5 calendar days unattended runtime, ≈ €47; with 30%
contingency (invalid-cell reruns, retries) ≈ €61 — inside the ~€70 D9
envelope.** Prices are the post-2026-06 Hetzner repricing gathered 2026-07-07
(ccx13 €0.0689/h, ccx23 €0.1378/h; cpx21 unconfirmed) — **run `hcloud
server-type list` before the first apply and sync `local.hourly_eur` in
main.tf.** The old "€0.15/h" estimate predates the repricing and is void.

Phase order A → B → C, destroy between phases (type/count changes replace
servers anyway; a fresh apply is the standardized lifecycle). Within a phase
the run order is randomized per methodology §1.

## 3. Anatomy of one run — why 8 minutes each *(pilot-refined)*

```
standard run (baseline / rate sweep / non-failover faults):
  0:00  fresh SUT cluster start + health gate        ~1-2 min  (mutating cells: always fresh)
  0:02  warmup           180 s   discarded; JVM+page-cache settle (methodology §1)
  0:05  measurement      300 s   the only window that produces figures
  0:10  PrometheusExporter + ValidityChecker + CSVs  ~30 s
        ≈ 8 min measured + ~2 min overhead  →  ~10 min per run

failover trial (leader_kill, ≥30 trials — distribution is the object):
  0:00  fresh cluster + health gate                  ~1-2 min
  0:02  warmup           180 s
  0:05  measurement      180 s   fault injected at +60 s; ±60 s window captured
        ≈ 6 min measured + ~2 min overhead  →  ~8 min per trial
```

Why 300 s and not less: the within-run stability gate (CoV over the window)
and the convergence gate (warmup-tail vs measurement-head) both need enough
one-second buckets to be meaningful; 300 points is the floor the methodology
fixed. Why 180 s warmup: JVM systems (Kafka) warm slowly; the pilot's
convergence check demotes this from assumption to measurement.

Run-count budget behind the phase table (n=5 per cell, ≥30 failover trials):

| Block | Runs × minutes | Hours |
|-------|----------------|-------|
| Per CFT system: sat-search ~30m + sat 5×10m + sweep 15×10m + faults 20×10m + failover 30×8m | ~670 min | 11.2 |
| Phase A: 5 systems × 11.2 h | | ~56 |
| D7 conflict sweep (Paxos+EPaxos × c∈{2,10}%: re-search + sat + sweep) | 2×2×~220 min | ~15 |
| Phase B: 4 cells (2 systems × sizes {5,7}) × ~230 min baseline-only | | ~15 |
| Phase C: 2 BFT systems × 11.2 h | | ~22 |

## 4. Where results live

```
loadgen:~/results/<system>/<scenario>/size<N>[/c<NN>]/<runId>/
    throughput.csv      per-second committed ops (harness)
    latency.csv         point percentiles + mean (harness)
    latency.hlog        FULL HdrHistogram, compressed — pooling input (P1.3)
    manifest.json       identity, window, params, digests, fault timestamp
    validity.json       the six §4 gates, pass/fail with reasons (M5.5)
    metrics/*.csv       Prometheus query_range exports (M5.4) — see §5
```

Flow: written on **loadgen** during the run → `rsync -a` to the laptop after
every phase (verified by file count + manifest count before destroy) → the
laptop copy is committed/archived (git + offline copy). **Also collected
before destroy (EXECUTION_AND_COST_MODEL §6): per-block SUT container logs
+ docker-events audit (P4.5 — HotStuff's metrics ARE its logs) and the
campaign runner's own log.** The Prometheus TSDB
on obs is *secondary* (explanation layer): 15-day retention outlives the
campaign, and a TSDB **snapshot** (`--web.enable-admin-api` is already set)
is taken once at campaign end and archived beside the results tree. Nothing
on any VM is the sole copy of anything by the time `terraform destroy` runs.
Local dev runs carry `environment=local` in the manifest (P1.6) and are
**never** mixed into thesis figures (`perf_valid=false`).

## 5. Prometheus retrieval protocol — how each run gets *its own* metrics

The harness's `PrometheusExporter` (M5.4) makes every run self-contained.
Mechanics, in order:

1. **Time bounds come from the run's own manifest** — `started_at`/`ended_at`
   (ISO-8601 UTC, written by the harness on loadgen; chrony keeps all VM
   clocks within 5 ms, validity gate 6, so one clock domain is enough at a
   5 s scrape step).
2. **Pad the window by ±15 s** (3 scrape intervals) so boundary samples are
   not lost to scrape phase alignment.
3. For each line `<name> | <promql>` in `observability/export_queries.txt`:
   `GET http://obs:9090/api/v1/query_range?query=<promql>&start=<start-15s>&end=<end+15s>&step=5s`
   and write `metrics/<name>.csv` (`t_unix,t_iso,labels…,value` — one row per
   series sample).
4. **Selection is by `role` label, never by instance name.** Prometheus's
   `instance` label is `IP:port`; rev1 of the queries matched
   `instance=~".*LOADGEN.*"` which can never fire — the fix (2026-07-08) adds
   per-target `role: consensus|loadgen|obs` labels in `prometheus.yml`.
   Instance→node mapping in analysis uses `deploy/inventory.env`
   (10.0.0.11 → node1, …), which Terraform regenerates on every apply.
5. **Fail closed:** an empty result for any validity-relevant series is a
   validity FAILURE for that run, never a pass — an empty series means the
   retrieval path is broken, precisely when a gate must not wave runs through.
6. **Fault alignment:** the harness stamps the fault-injection time into the
   manifest (P1.4); gate 3 (fault ground truth) then checks the PER-SYSTEM
   witness (`etcd_leader_chg` increment / `cmt_rounds` jump / `kafka_urp`
   above zero; `node_up` as a generic extra — it only moves for VM-level
   faults) inside the ±60 s window around that timestamp; paxi/hotstuff
   have no server metrics and SKIP loudly until the P4.5 docker-events
   audit (methodology §4.3, F41).

Worked example — leader-change corroboration for a leader_kill run:

```
GET /api/v1/query_range
    ?query=etcd_server_leader_changes_seen_total
    &start=2026-08-03T14:02:45Z   (manifest started_at − 15 s)
    &end=2026-08-03T14:11:15Z     (manifest ended_at + 15 s)
    &step=5s
→ metrics/etcd_leader_chg.csv ; gate 3 asserts the counter increments
  within ±60 s of manifest.fault_injected_at, else the run is reclassified.
```

## 6. Config management decision — Terraform + cloud-init, **no Ansible**

Recorded decision (2026-07-08): Ansible is not used. The three layers are
already owned: **Terraform** owns infrastructure state (create/destroy,
inventory generation); **cloud-init** owns first-boot node substrate (docker,
node_exporter, chrony, stress-ng — idempotent by construction, runs once);
the **harness's RemoteSshProvider** owns everything dynamic (per-system
container lifecycles, fault injection), in typed Java behind golden tests
(G2). Ansible would add a third automation language and a second idempotency
model to do ~40 lines of glue — and would put YAML-templated orchestration in
exactly the layer where v6 died. If a future need appears (e.g., rotating a
package on live VMs mid-campaign), the answer is destroy/re-apply, not
mutation — the whole design is built around disposable, reproducible VMs.

## 7. Standing safety rules

- `terraform destroy` after every phase; verify with `hcloud server list`
  (must be empty) — billing is only provably stopped by absence of servers.
- Never store the token in a file that can be committed; `TF_VAR_hcloud_token`
  env var only (.gitignore already blocks `*.tfvars`, state, inventory).
- No `terraform apply` before Gate G2 (golden tests human-reviewed + canary
  P3.4 green). Local `validate`/`plan` are always allowed — they bill nothing.
- Results are rsync'd and count-verified on the laptop **before** destroy.
