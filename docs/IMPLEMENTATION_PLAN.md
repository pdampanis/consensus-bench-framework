# Implementation Plan — Execution Grade

Every task here has a deliverable (files), an acceptance criterion (a command
or observable fact), and dependencies. Milestones end in gates; nothing past
a gate starts until the gate is green. M0 was **executed and verified in the
authoring session**, so this plan starts from demonstrated-working code, not
promises.

---

## M0 — Vertical slice (DONE, evidence in bundle)

The compiled skeleton was extended with `EtcdHttpDriver` (pure JDK, etcd v3
JSON gateway) and a `Main` CLI, then run against a **real etcd 3.4.30**
(Raft commit path incl. WAL fsync — the PUT response carries `raft_term`).

Executed and verified:
- **Open-loop 300 ops/s, 20 s, warmup 5 s**: 4,599 committed, 0 errors,
  achieved 306.6 ops/s (≈2% of target); real per-second series with genuine
  variance (298–396), not a synthetic flat line.
- **Saturation, window 64**: 1,020.8 ops/s, p50 = 58.9 ms — and Little's Law
  predicts window/throughput = 62.7 ms, so the engine's window accounting
  and its latency measurement **corroborate each other independently**.
- Output contract checked programmatically: throughput.csv schema, latency
  percentile ordering, size-aware result path, honest manifest.
- A negative test happened by accident and passed: when etcd died between
  sandbox invocations, the harness reported 6,001 errors and zero committed
  — it does not fabricate data from a dead system.

What M0 did **not** prove: quorum behavior (single node), the jetcd path
(gateway JSON instead), absolute numbers (shared sandbox CPU; 1 kops/s and
the 323 ms p99 tail reflect the environment and the 5 s warmup transient),
and Maven dependency resolution (no Central access in the sandbox).

---

## M1 — Project bootstrap (~0.5 day)

| ID | Task | Deliverable | Acceptance | Deps |
|----|------|-------------|------------|------|
| M1.1 | Maven-ize | `pom.xml` (provided, pinned), `src/main/java` layout | `mvn -q verify` green on your machine | — |
| M1.2 | HdrHistogram swap | `LatencyRecorder` internals → HdrHistogram, same API; add true mean/count | Unit test: exact percentiles on a known sample set | M1.1 |
| M1.3 | Real CLI | picocli commands: `run`, `campaign`, `validate` | `--help` renders; M0 smoke reproducible via new CLI | M1.1 |
| M1.4 | Results writer fix | True mean replaces the documented p50 placeholder | Golden-file unit test on a fixed Result | M1.2 |

## M2 — Drivers (~1.5–2 days) → **Gate G1**

| ID | Task | Deliverable | Acceptance | Deps |
|----|------|-------------|------------|------|
| M2.1 | EtcdDriver (jetcd) | Driver + leader detection via maintenance status | 60 s smoke vs local etcd; on a 3-process local cluster, detected leader index matches `etcdctl endpoint status` | M1 |
| M2.2 | KafkaDriver | kafka-clients producer, acks=all, callback-timed commit | 60 s smoke vs local single-broker KRaft; LOCAL gate = order-of-magnitude band vs `kafka-producer-perf-test` on the same broker (amended 2026-07-18 per F27/P2.2c: laptop environments swung the ratio 0.2x–2.8x for identified reasons — evidence in the parity test's javadoc); the symmetric 15% comparison runs at G3/M6.1 on the cluster | M1 |
| M2.3 | CometBftDriver | Async `broadcast_tx_commit`, window ≥200 | Local kvstore node: sustained > 300 tx/s (≥50× the old 6-client ceiling); p50 ≈ block interval | M1 |
| M2.4 | Paxi leader detection | `Ballot` response-header parsing in PaxiDriver (F22: paxi has no `/state`; the header's ID part is the leader) | Unit test on Ballot-header fixtures + 3-node local Paxos smoke | M1 |
| M2.5 | HotStuff boundary | SUMMARY parser as a class | Unit test against a captured `fab.log` fixture | M1 |

**Gate G1**: all five acceptance runs archived under `calibration/local/`.
The M2.2 and M2.3 checks are the direct regression tests for the two probe
flaws that motivated the harness.

## M3 — Local development substrate (~1 day)

**SUPERSEDED on substrate choice (2026-07-07): the local substrate is Docker
via Testcontainers (`LocalDockerProvider`), not local processes** — Docker is
up on the dev machine, it matches the pinned-image packaging (D2), and
Testcontainers gives typed teardown guarantees. See `PENDING_TASKS.md` P0,
which also pulls this milestone *ahead of* M2 (local loop first). The
acceptance criteria below still bind; only the mechanism and order changed.

| ID | Task | Deliverable | Acceptance | Deps |
|----|------|-------------|------------|------|
| M3.1 | LocalDockerProvider (was: LocalProcessProvider) | ClusterProvider impl launching n-node local clusters | 3-node etcd up/healthy/down cleanly, repeated 5× | G1 |
| M3.2 | Process FaultInjector | kill-by-NodeHandle using detected leader | leader_kill on local etcd trio: client series shows gap→recovery; post-kill detected leader differs | M3.1 |
| M3.3 | CampaignRunner | Matrix executor: per-run recycle for `mutatesCluster()`, size-aware paths, resume; **single-system "session mode" + own log to `logs/` (2026-07-09, EXECUTION_AND_COST_MODEL §9)** | Local mini-campaign 2×2×n2: all manifests complete; immediate re-run skips everything; one-system block runnable standalone | M3.2 |

## M4 — Remote layer (~2–3 days) → **Gate G2** (the v6 danger zone)

| ID | Task | Deliverable | Acceptance | Deps |
|----|------|-------------|------------|------|
| M4.1 | SshExecutor + Recorder | sshj wrapper + a recording stub implementation | Unit tests exercise both against the same interface | M3 |
| M4.2 | RemoteSshProvider | Per-system container start/health/stop on nodes | Dry-run: recorded command sequences reviewed | M4.1 |
| M4.3 | Remote FaultInjector | docker-kill by handle; netem with iface from `ip -o route get <peer_ip>`; iptables partition with heal in `finally`; stress-ng | Dry-run recorded; heal provably always emitted | M4.1 |
| M4.4 | **Golden tests** | For every (system, scenario, size) cell: exact expected remote command sequence as reviewable golden files | Suite green + one human read-through of the goldens — the verification v6 never had | M4.2–3 |
| M4.5 | Provisioning | hcloud script: 4×CCX13 + 2×CPX21, private net, dual-IP inventory, cluster-keypair distribution; cloud-init rev2 (stress-ng, chrony, node_exporter, xxd) | Script dry-run prints plan; inventory schema test | M4.4 |
| M4.6 | **Canary** | One etcd cell end-to-end on 2 temporary VMs (<€0.10, <1 h) | Valid CSVs downloaded; teardown leaves zero servers | M4.5 |

**Gate G2**: no full-cluster provisioning before M4.4 goldens are reviewed
and the M4.6 canary is green.

## M5 — Observability & validity (~1–1.5 days)

| ID | Task | Deliverable | Acceptance | Deps |
|----|------|-------------|------------|------|
| M5.1 | obs stack | Starter zip deployed to obs VM; Prometheus templated from inventory | All targets `up == 1` | M4.6 |
| M5.2 | Exporters | Kafka JMX-agent image; etcd `--listen-metrics-urls`; CometBFT instrumentation flag | Each system's job scraped during a smoke | M5.1 |
| M5.3 | Harness metrics | micrometer registry on :9400 (inflight gauge, submitted/failed counters) | Series visible in Prometheus during a run | M1.3 |
| M5.4 | PrometheusExporter | query_range over `export_queries.txt` → `metrics/*.csv` per run | Canary rerun: run dir self-contained | M5.1 |
| M5.5 | ValidityChecker | The six methodology gates → `validity.json` | Unit tests on synthetic metric CSVs (pass + each failure mode) | M5.4 |
| M5.6 | Dashboards | Grafana JSONs in repo: campaign overview + per-system | Import clean on fresh Grafana | M5.1 |

## M6 — Calibration → pilot → campaign (runtime + ~1 day) → **Gate G3**

| ID | Task | Deliverable | Acceptance | Deps |
|----|------|-------------|------------|------|
| M6.1 | Cross-validation | Harness vs native tools (Kafka perf-test, etcd benchmark, Paxi benchmarker) on the real cluster; deltas documented | ≤15% or explained — **Gate G3** | M5 |
| M6.2 | Pilot | Baseline ×2 per system → variance, warmup convergence, validity thresholds fixed numerically | Pilot report; n confirmed or adjusted | G3 |
| M6.3 | Campaign | systemd unit, randomized order, full matrix; download preserves server data; destroy after local verification | ≥95% cells valid; results tree + metrics + validity archived | M6.2 |
| M6.4 | Analysis v2 | analyse.py v2 per methodology (pooled histograms, Holm, ECDFs, validity filtering) + the 8 figures | Figures regenerate from the archive alone | M6.3 |

**Total: ~6.5–9 focused days + cluster runtime.** This exceeds the earlier
"4–5 day" WS1 estimate — deliberately: that number was flagged as a floor,
and M4.4/M4.6 exist precisely because the cheap path (skipping verification)
was already tried once and billed as v6.

---

## Risk register

| Risk | Likelihood | Response |
|------|-----------|----------|
| Maven coordinates fail to resolve | Low | M1.1 acceptance catches it in minutes; versions are mainstream |
| CometBFT RPC misbehaves at 200 in-flight | Medium | M2.3 tests exactly this before anything remote exists; fallback: broadcast_tx_sync + commit polling |
| sshj quirks (host keys, keepalive on long runs) | Medium | M4.1 isolates SSH behind one interface; canary runs a full-length cell |
| CCX13 unavailable in fsn1 | Low | nbg1/hel1 fallback in the provisioning script |
| JMX agent overhead on 2-vCPU brokers | Low | M6.1 runs one Kafka calibration pass agent-off vs agent-on |
| Sandbox-proven ≠ quorum-proven | Certain | M2/M3 local clusters, then canary — the ladder is the plan |

## Definition of done

The implementation is done when: `mvn verify` is green; G1–G3 gates are
archived with their evidence; the full campaign tree (CSVs + metrics/ +
validity.json per run, ≥95% valid) is downloaded and versioned; the
calibration deltas document exists; all eight figures regenerate from the
archive with one command; and the Hetzner project contains zero servers.

## Honest review of this plan

What it fixes about its predecessor: MASTER_PLAN's actions were
direction-level; these are deliverable-level with executable acceptance, and
the plan now *begins* with executed evidence instead of ending with promised
review. The Little's-Law cross-check in M0 was luck turned into method — it
should become a permanent engine self-test (add to M1.2's unit tests).
What remains genuinely uncertain: the M4 estimate (remote orchestration has
missed every estimate so far — treat 3 days as the floor, and the goldens
as non-negotiable regardless of schedule pressure); the pinned dependency
set (unverifiable here — first ten minutes of M1 settle it); and the
CometBFT window behavior, which is the one driver whose acceptance test
could force a design change (hence it sits in M2, before anything depends
on it). The plan's deepest assumption is procedural, not technical: that
gates hold under impatience. v6 happened because a gate that should have
existed didn't; G2's human read-through of the golden files is deliberately
manual so that skipping it has to be a conscious decision rather than an
oversight.
