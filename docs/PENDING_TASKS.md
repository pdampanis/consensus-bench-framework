# Pending Tasks — Prioritized Backlog + Status Ledger

**Last updated: 2026-08-14** (seventh review F50–F69, then the eighth
read-through F70–F74 — both sections near the bottom). Companion to `IMPLEMENTATION_PLAN.md`
(milestone-ordered M0→M6). This file is **priority-ordered for the current
push** and doubles as the handoff ledger: a fresh session should be able to
read this file plus `PROJECT_STATE.md` and know exactly what is done (with
evidence), what is next, and why. When this file and `IMPLEMENTATION_PLAN.md`
disagree on *order*, this file wins; on *acceptance criteria*, the plan wins.
Authority order unchanged: **live code > plan + methodology > this
file/overview > docs/archive**.

---

## Status snapshot (2026-07-08)

**DONE and verified by execution** (see PROJECT_STATE §3 for detail):
- M0 vertical slice vs real etcd; M1.1 `mvn21 clean verify` green (shade fix).
- **P0.0 Terraform layer**: `infra/cloud-init.yaml` created;
  `infra/main.tf` reworked (count/type variables for D8/D9, loadgen→ccx13 per
  D11, spread placement group, computed cost output, `count<=7` guardrail).
  Verified locally with terraform 1.9.8: `fmt`, `init`, `validate` green;
  dummy-token `plan` green for all three phase shapes (A: 11 resources
  €0.292/h; B count=7: node7=10.0.0.17, €0.567/h; C 4×ccx23: €0.636/h);
  count=8 correctly rejected. **NOT yet applied — G2 gate intact.**
- **Prometheus retrieval fix (review F3)**: per-target `role` labels in
  `observability/prometheus.yml` (instance-name matching could never fire);
  `loadgen_cpu` rewritten on `role="loadgen"`, `loadgen_cpu_steal` added
  (D11), ZK scrape job added (D10). Verified: `promtool check config` green;
  all 22 export queries parse via `promtool check rules`.
- Docs: `CAMPAIGN_RUNBOOK.md` created (topology, phases, cost, durations,
  storage, retrieval protocol, no-Ansible decision); MASTER_PLAN gained
  D10/D11; methodology §1/§4 updated; IMPLEMENTATION_PLAN M3 substrate note.
- **P0.1, P1.5, P0.4 code increments (TDD, red→green)** — see their sections
  below. Suite: **18 tests green**; three real bugs found and fixed along the
  way (shaded-jar NOP logging, engine drain race, zero-second dropping).
  Manual verification guide: `docs/LOCAL_TESTING.md`.

**Decisions locked 2026-07-08**: D10 (ZK colocated on broker nodes),
D11 (loadgen on dedicated vCPU), no-Ansible (runbook §6).

**The 2026-07-08 hard review** produced findings F1–F17 (ledger at the bottom
of this file maps each to a task). The pattern it caught: methodology claims
without implementation coverage — every such claim now has a task below.

**P0 AND P1 ARE FULLY CLOSED (2026-07-09); P2.1 (jetcd) IS DONE.** The
measurement instrument is complete and pinned, and the first production
driver (etcd/jetcd, leader detection cross-validated) rides the local loop
from the shaded jar. Manual verification: **`docs/LOCAL_TESTING.md`**.

**P2.2 (KafkaDriver) AND P2.3 (CometBftDriver) ARE DONE (2026-07-15, TDD
throughout). Suite: 71 tests green. Next: P2.4 Paxi leader detection —
NOTE: Paxi has no published Docker image; P2.4's substrate needs a
built-from-source image first.** P2.2 delivered:
- **P2.2a** — LocalDockerProvider KRaft size-1 substrate (apache/kafka
  3.9.1 digest-pinned; testcontainers `kafka` module owns the advertised-
  listener dance; multi-node fails closed until the campaign provider).
- **P2.2b** — `KafkaDriver` (KRAFT/KAFKA_ZK): acks=all, commit timed in the
  send callback, delivery.timeout=5 s (F18 contract), metadata warmed at
  connect() so send() never blocks the issue loop; leader = partition-0
  leader by endpoint match, unmappable ⇒ throw. Dead-broker write fails
  <8 s asynchronously. Key encoding (UTF-8 int) pinned.
- **P2.2c** — G1 flaw-B regression vs `kafka-producer-perf-test` on the
  same broker (oracle exec'd in-container on the BROKER listener). **Local
  gate = 3x order-of-magnitude band; the symmetric 15% moved to G3/M6.1
  on the cluster** — measured evidence across 4 laptop configurations
  (ramp fraction, sticky-vs-keyed partitioning ≤2x, window/latency
  Little's-Law cap, 5 s vs 120 s delivery-timeout asymmetry under
  writeback storms; final run: 0.95x). Full rationale in the test javadoc.
- **Engine observability (fallout, TDD)**: `Result.firstError` — a
  551-error run with no cause cost a diagnosis cycle; the engine now keeps
  and WARNs the first failure cause. Diagrams: `MEASUREMENT_DIAGRAMS.md`.

**2026-07-15 second hard review** (full code+docs pass; 51/51 suite
re-verified by execution first): produced findings F18–F21 (ledger below)
and a doc-drift cleanup. Fixed same day, TDD red→green: **F18 — jetcd
per-op deadline** (red test measured a write on a quorum-lost cluster
completing only via etcd's ~7 s *server-side* grace, i.e. no client-owned
bound at all; fix = `orTimeout(5 s)` in `EtcdDriver.write`, matching the
HTTP drivers, + bounded-completion contract documented in the
`ConsensusDriver.write` javadoc). Suite: **52 tests green.** Consequence
for P2.2+: every future driver must bound completion at 5 s (Kafka:
`delivery.timeout.ms`). Docs refreshed: README status/caveats/handoff,
METRICS_AND_SOURCES §0 + retired-probe labeling, CONTINUATION_PROMPT
"Where to start" → P2.2.

---

## How to read a task

Each task carries: **Why** (the problem it solves) · **Deliverable** (files) ·
**TDD acceptance** (the test written *first*, red→green) · **Deps** · **Notes**.
A task is *done* only when its acceptance test is green **and** run-verified
(execute, don't assert — M0 is the template).

## Engineering standards (apply to every task — do not restate per task)

- **Karpathy / TDD.** One component per increment; write the failing test first,
  watch it fail for the right reason, make it pass, refactor. Stop at each
  checkpoint and surface *done / next / uncertain*. Confirm before large batches.
- **Simplicity first.** Minimum code that passes the test; no speculative
  abstraction, config, or error handling beyond what the task needs. If 200
  lines could be 50, write 50.
- **Modular & readable.** Small classes behind the existing SPIs
  (`ConsensusDriver`, `ClusterProvider`); one responsibility each; names that a
  reviewer reads without the comment.
- **Comments explain *what happens and why*, not the syntax.** Match the density
  already in the skeleton (see `WorkloadEngine`/`ConsensusDriver` headers).
- **Correct, explicit error handling — fail closed.** No swallowed failures
  (v6's `|| true` disease). An unresolved input (dead container, missing leader,
  bad status, **empty metric series**) throws or fails the gate with a message
  that says *what* and *which node/endpoint*; nothing silently defaults.
- **Performance where it's measured.** The per-op path stays allocation-light
  (no logging, no boxing, no string building inside `write()`/`record()`); setup
  and teardown can be liberal.
- **Verbose logging behind a flag.** slf4j everywhere. `-v/--verbose` raises the
  level to DEBUG and logs every **phase boundary** (deploy, wait-healthy,
  connect, warmup start/end, fault inject, teardown) and per-second throughput —
  never inside the hot per-op path when off. Default INFO stays quiet and useful.

---

## P0 — Local automated deploy → test → teardown — **GOAL ACHIEVED 2026-07-08**

Goal was: **one command** brings up a fresh etcd cluster in Docker, runs the
harness against it, writes the standard results, tears everything down —
fast, repeatable, no leaked containers. Delivered:
`java -jar consensus-bench.jar local-run --size {1|3} --rate R --duration D [-v]`
— measured **7.6 s** (size 1) / **8.2 s** (size 3) wall-clock for the whole
clean→deploy→run→teardown loop from the shaded jar (target was <30 s).
Substrate: Docker via Testcontainers implementing `topology.ClusterProvider`.

### P0.0 — Terraform + cloud-init, locally verified — **DONE 2026-07-08**
Evidence in the status snapshot above. Residual (moved to P3.5): confirm
plan names/prices with `hcloud server-type list` before first apply.

### P0.1 — Logging + `--verbose` foundation — **DONE 2026-07-08 (TDD, run-verified)**
- **Red→green.** `ArgParserTest` (5 tests, the repo's first) written first; 4
  failed for the right behavioral reasons (bare `-v` ignored / eats next key /
  no fail-closed throw); minimal parser fix → 5/5 green. Parser now: `--key
  value` pairs, bare `-v/--verbose` anywhere, `IllegalArgumentException` on a
  trailing key (fail closed).
- **Logging.** slf4j in `Main`/`WorkloadEngine`/`EtcdHttpDriver`; printf →
  INFO; DEBUG phase boundaries only *outside* the hot loop; warmup-end +
  per-second throughput come from a verbose-only daemon reporter thread that
  reads the engine's atomics — the per-op path is byte-identical with `-v`
  on or off. `Main` holds no static logger (slf4j-simple freezes config on
  first `LoggerFactory` call; `-v` must decide first).
- **Run-verified** vs real etcd 3.4.30 in Docker: without `-v` → 4 INFO lines;
  with `-v` → same INFO + connect/warmup-start/warmup-end/drain/close phases
  + `t=Ns committed=100 ops` per second at the 100 ops/s target; manifest
  contract unchanged; zero leftover containers.
- **Bonus find (real bug).** The `-v` smoke exposed that the shaded jar's
  logging was silently NOP since M1.1: kafka-clients' slf4j-api **1.7.36**
  won Maven mediation over slf4j-simple's 2.0.16 (1.x API can't see a 2.x
  provider), and shade wasn't merging `META-INF/services`. Fixed: direct
  `slf4j-api:2.0.16` dep + shade `ServicesResourceTransformer` (which M2.1's
  gRPC needed anyway). Dependency tree now shows only 2.0.16.

### P0.2 — `LocalDockerProvider` (etcd, size 1 and 3) — **DONE 2026-07-08 (TDD)**
- **Delivered.** `topology.LocalDockerProvider` (main code, ships in the
  uber-jar for P0.3): etcd v3.4.30 pinned **by registry digest** (D2),
  one Docker network per cluster with etcd1..N aliases, per-member
  `/health` readiness gate, **parallel start** via `Startables.deepStart`
  (an etcd member only answers /health once quorum exists — a sequential
  start would deadlock on its own gate), `thesis-*` container names,
  Ryuk-backed teardown, fail-closed on unsupported systems/reuse.
- **Acceptance (red→green, real Docker):** size 1 — healthy, committed PUT,
  stop() leaves zero `thesis-*` containers. size 3 — quorum write; **kill 1
  of 3 → writes keep committing after re-election (≤20 s); kill 2 of 3 →
  writes FAIL** (no fabricated commits); cleanup clean even with killed
  members. Unsupported system → typed error. 3 tests, ~15 s.
- **Environment quirk found (real):** Docker Engine 29.6 enforces min API
  1.40; Testcontainers ≤1.21.3 pins client API 1.32 and fails the daemon
  probe with "client version 1.32 is too old". **Pin ≥1.21.4** (done). Suite
  now REQUIRES a local Docker daemon; uber-jar grew 52→77 MB (docker-java).

### P0.3 — `local-run` one-command orchestration — **DONE 2026-07-08 (TDD)**
- **Delivered.** `Main` now dispatches two commands: default endpoint-run (M0
  behavior) and `local-run` (P0.3) — `LocalDockerProvider.removeLeftovers()`
  pre-clean (idempotent, WARN per removed container), fresh cluster, engine
  run, CSV/manifest, teardown via try-with-resources (guaranteed on any
  exit path). Container-library INFO chatter silenced at default level so
  quiet mode stays 5 summary lines; `-v` shows everything.
- **Acceptance (red→green + CLI):** integration test plants a leftover
  `thesis-*` container, runs `local-run` twice in a row → complete manifests
  (`status=complete`, `errors=0`), zero survivors both times. From the shaded
  jar: size 1 = **7.6 s**, size 3 = **8.2 s** wall-clock (target <30 s);
  size-3 results land under `.../size3/` (the v6 collision-proofing visible).
- Suite: **22 tests green.**

### P0.4 — Quick correctness fixes in writer/SPI — **DONE 2026-07-08 (TDD, red→green each)**
1. **Zero-second dropping fixed.** `write()` now takes `durationSecs` and
   writes EVERY run-window second zeros included (a zero-commit second is
   stall evidence); only drain-buffer seconds (t ≥ duration) are filtered to
   nonzero. Red test: synthetic 400 s Result with a zero at t=310 → row was
   missing → now present.
2. **Manifest honesty.** Manifest gains `duration_secs` and `error_rate`
   (whole-run failure fraction, `%.4f`); status is `failed` when ops==0 OR
   error_rate > 0.5 — a majority-failed run may not claim `complete` (finer
   judgment stays with ValidityChecker M5.5). Red tests: 90%-error run
   claimed `complete`, `error_rate` didn't exist.
3. **`close()` never swallows.** Failure during `stop()` is logged at WARN
   (teardown continues). Test captures stderr and asserts the failure text is
   visible; second test pins no-throw + stop-attempted.
4. **duration>warmup guard.** `Main.requireDurationExceedsWarmup` fails closed
   before any work; CLI-verified (`--duration 5 --warmup 5` → IAE, nothing
   written).
- Suite: **18/18 green** (`mvn21 clean verify`); jar re-smoked vs etcd 3.4.30:
  clean manifest (`error_rate: 0.0000`, `status: complete`) and dead-cluster
  run (`error_rate: 1.0000`, `status: failed`, zero fabricated samples).

---

## P1 — Workload-model correctness  *(unblocks trustworthy numbers)*

Recommended order: **P1.5 first** (pin current behavior), then P1.1 → P1.2 →
P1.3 → P1.4 → P1.6.

### P1.5 — WorkloadEngine characterization tests (FakeDriver) — **DONE 2026-07-08**
- **Delivered.** `FakeDriver` (test scope: fixed service delay on its own
  scheduler thread, programmable stall window, fail-all mode) + 6
  characterization tests, all green (`WorkloadEngineTest`): open-loop rate
  adherence; **drain accounts for every issued op**; buckets sum == histogram
  count; warmup flagging; **CO correction** (1 s stall → ~100 scheduled ops
  charged their queueing delay: p50 fast, p90 ≥ 300 ms, max ≈ full stall — a
  CO-blind measurement would show one slow op); dead cluster → errors only,
  zero fabricated samples. Tolerances discriminate by order of magnitude —
  not flaky.
- **Engine fix that fell out (traced, surgical).** Inspection while pinning
  the drain invariant found `inFlight.release()` ran *before*
  `recorder.record()` in the completion callback — the drain barrier could
  complete while final callbacks were mid-record, silently losing up to a
  window of samples from the Result (µs-wide race; test passed by luck).
  Fixed: release moved into a `finally` AFTER recording, making the pinned
  invariant deterministic. `mvn21 clean verify`: 11/11 green.

### P1.1 — Key contract: int keyId, K=1000 reuse, per-driver encoding — **DONE 2026-07-08 (TDD)**
- **RED was real:** the keyspace test against the old engine measured
  **778,324 distinct keys over 778,324 ops** — every key unique, zero
  contention possible, D7 unfireable. Executable proof, not just diagnosis.
- **Delivered.** `ConsensusDriver.write(int keyId, byte[] value)` with
  `KEY_SPACE = 1000` as the SPI contract constant (Paxi Table 3); the typed
  int makes the append-the-op-index bug *inexpressible*. Engine `keyFor()`
  draws uniform ints, no per-op allocation (P1.2 adds conflict routing
  there). Each driver owns its encoding, precomputed at `connect()` so the
  per-op path builds no key strings: etcd `bench/k<id>` (base64 cache),
  Paxi `/<id>` (full URI table) — numeric-only, as Paxi's Atoi demands
  (re-verify vs paxi/http.go at P2.4).
- **Green:** keyspace bounded ≤1000 AND coverage ≥950 over 10⁴+ ops;
  encoding pins for both drivers; full suite **25 tests green** including
  all Docker integration tests re-passing on the new contract.

### P1.2 — Conflict-ratio knob (D7) + result-path dimension — **DONE 2026-07-08 (TDD)**
- **Delivered.** `Config.conflictRatio` (validated in the record compact
  constructor: [0,1], NaN rejected — fail closed at construction, not at
  runtime); `keyFor(c)` routes fraction `c` to **key 0, exclusive to
  conflict traffic** (uniform traffic draws [1,1000)) so the realized
  fraction equals `c` *by construction* — no (1−c)/K bias, which would
  matter most at the smallest sweep point c=2%. `RunIdentity.conflictRatio`
  → path segment `c<pct>` **only when c>0** (non-Paxi tree + committed M0
  results keep their layout; c>0 vs c=0 can't collide — extra segment) +
  `conflict_ratio` manifest field. **Whole percents only** in the identity:
  0.025 would render as its rounding neighbor and silently merge two cells
  (v6 collision class) → IAE. `--conflict` on both CLI commands.
- **Acceptance (green, non-flaky by design):** realized fraction within
  ±0.015 of c ∈ {2%,10%} at n≥10⁴ (≥5σ band); **c=0 ⇒ exactly zero writes
  to key 0**; construction rejects −0.01/1.01/NaN; path pins for c10 and
  the c=0 legacy shape; manifest field golden-checked. CLI run-verified:
  `--conflict 0.10` → `.../size1/c10/c10a/` + `"conflict_ratio": 0.10`;
  `--conflict 1.5` fails fast. Suite: **31 tests green.**

### P1.3 — HdrHistogram + true mean + `.hlog` persistence + Little's-Law self-test — **DONE 2026-07-09 (TDD)**
- **Delivered.** `LatencyRecorder` internals → two `ConcurrentHistogram`s
  (all/warm; 3 significant digits = 0.1% precision vs the stand-in's ~3%;
  wait-free recording on the hot path; **auto-resizing**, because
  CO-corrected latencies during a long stall reach tens of seconds and a
  fixed ceiling would throw away exactly the samples a failover study
  needs). Same API + `meanMicros()` (true mean) + `warmSnapshot()` (copy —
  callers can't mutate live state). Writer: `avg` = true mean (placeholder
  gone) and **`latency.hlog`** in standard HistogramLogWriter v1.3 format
  (readable by HdrHistogram tooling + analyse.py v2), interval stamped
  [measurement start, run end], unit documented in the header comment.
- **Acceptance (red→green):** percentiles within 0.1% on a known 10⁴ set;
  skewed set → mean ≈ 1099.9 ≫ p50 = 100; warmup samples excluded from
  every statistic incl. max; **merge-two-snapshots == histogram of all raw
  samples** (the §3 pooling path proven); **.hlog round-trip** re-reads to
  the same count/percentiles; **Little's-Law self-test** (saturation:
  throughput × mean ≈ window, three independently measured quantities —
  M0's lucky corroboration is now a permanent regression test). Jar
  run-verified: real run dir contains latency.hlog; avg=2837 vs p50=2263.
  Suite: **38 tests green.** The M0 caveat "no result trusted until real
  HdrHistogram" is closed.

### P1.4 — Failover instrumentation: timestamped events — **DONE 2026-07-09 (TDD)**
- **Delivered.** `core.EventLog`: preallocated `long[]`, lock-free
  claimed-slot append (one long/event: rel-nanos, errors as −(rel+1)),
  **opt-in** via a 4-arg engine constructor (3-arg keeps the hot path
  event-free — pinned by test); **one clock domain** (events + fault mark
  both System.nanoTime in the harness JVM — chrony is irrelevant to this
  number); `faultInjectedNow()` for the injector thread;
  `failoverMillis()` = first commit at-or-after the mark (linear scan at
  results time). **Overflow drops loudly** (dropped counter for the
  validity layer), never crashes, never truncates silently. `Result.events`
  (null for baseline runs; secondary constructor keeps existing callers).
- **Acceptance (red→green):** unit — 70 ms gap recovered from synthetic
  nanos; no-recovery ⇒ empty (never fabricated); overflow ⇒ dropped
  counted. Engine — off-by-default; every completion an event
  (drain-guaranteed); **scripted-stall end-to-end**: fault marked mid-stall
  from another thread, recovered failover ≈ (2.0 s − mark) within 150 ms;
  dead cluster ⇒ error events only, no failover number. Suite: **45 green.**
- **Residual (owned by P1.6/M3.3):** `fault_injected_at`/`failover_ms` into
  the manifest (writer side), campaign runner sizes the log and calls
  `faultInjectedNow()` at injection. Design choice recorded: events ride
  the main workload, NOT a separate probe lane (a second traffic class
  would perturb the workload it measures).

### P1.6 — Manifest v2: pin what the methodology says is pinned — **DONE 2026-07-09 (TDD)**
- **Delivered.** Manifest now pins: load params (`rate_ops_s`, `window`,
  `value_size_bytes` — writer takes the whole `Config`), **`environment`**
  (`local|hetzner`; Main always writes "local" — the "hetzner" tag belongs
  exclusively to the campaign runner on the loadgen VM), **`image`** (the
  digest-pinned ref from the provider; **honest `null`** for endpoint-run —
  we didn't start that cluster, we don't claim to know it),
  **`harness_version`** (jar Implementation-Version via a shade
  manifestEntry; "dev" from classes), **`config_hash`** (12 hex of SHA-256
  over every cell-defining input — same hash ⇒ same experiment), and
  P1.4's **`fault_injected_at_ms` / `failover_ms`** (explicit nulls for
  baseline; absent ≠ zero). EventLog gained `faultMarkMillis()` so gate 3
  can align its Prometheus corroboration window.
- **Acceptance (red→green):** params/environment/image/version pins;
  **hash deterministic across identical writes AND sensitive to a changed
  param**; fault fields null → real (25/70 ms fixture) → mark-without-
  recovery keeps failover null. Jar run-verified: live manifest carries the
  real registry digest, version 0.1.0-SNAPSHOT, hash, all params. Suite:
  **49 tests green.** All of methodology §1's manifest claims are now true.

---

## P2 — Real drivers  → **Gate G1**  *(develop against P0's local clusters)*

- **P2.1 — jetcd `EtcdDriver` + leader detection — DONE 2026-07-09 (TDD).**
  Native async gRPC put (commit semantics identical to the HTTP 200);
  `connect()` precomputes all 1000 `ByteSequence` keys AND fail-closes on a
  status probe before any latency sample; `currentLeaderIndex()` scans
  every endpoint's maintenance status for the member whose own id equals
  the leader id (endpoint order = node order); dead members logged and
  skipped, no-leader ⇒ `Optional.empty()` (election in progress — honest
  absence). **Acceptance green vs real Docker:** committed writes; detected
  leader **cross-validated against the HTTP-gateway stack** (two
  independent client stacks agreeing — not circular); kill the DETECTED
  leader → new different leader within 30 s → writes keep committing on
  2/3; key encoding pinned identical to EtcdHttpDriver (G3 needs one
  keyspace). `local-run` now uses EtcdDriver (production path);
  EtcdHttpDriver stays as endpoint-run fallback + G3 cross-check.
  **Shaded-jar proof:** jetcd/gRPC from the uber-jar drove a 3-node quorum
  (451 commits, 0 errors) — the ServicesResourceTransformer is exercised,
  not assumed. jetcd 0.8.5 note: `statusMember(String)`. Suite: **51 green.**
- **P2.2 — `KafkaDriver` — DONE 2026-07-15 (TDD)** — see the status
  snapshot above. Local acceptance = order-of-magnitude band vs perf-test
  (measured 0.95x); the symmetric 15% comparison runs at G3/M6.1 on the
  campaign cluster.
- **P2.3 — `CometBftDriver` — DONE 2026-07-15 (TDD, probe-first).**
  Substrate: single-validator cometbft v0.38.17 (digest-pinned) with the
  in-process kvstore app; the provider raises `rpc.max_subscription_clients`
  100→2000 (measured: 99/250 committed at the default — each concurrent
  `broadcast_tx_commit` holds a subscription; 250/250 after). Probed facts
  encoded: HTTP 200 ≠ commit (success = no JSON-RPC error AND
  check_tx.code==0 AND tx_result.code==0 — v0.38 renames deliver_tx to
  tx_result); mempool cache rejects duplicate tx bytes → per-tx nonce,
  carried as ASCII hex because kvstore's CheckTx splits on '=' requiring
  EXACTLY two parts (raw nonce bytes hit 0x3d → 12% code-2 failures,
  caught red by the new firstError instrumentation). **G1 flaw-A
  acceptance: 602 tx/s sustained (100x the retired 6-thread ceiling),
  p50 = 1.09 s ≈ the block interval, error rate <1%.** Window 600 (the
  ≥200 floor was window-bound at 220 tx/s = window/latency — Little's Law,
  same lesson as P2.2c). Suite: **71 tests green.**
- **P2.4 — Paxi substrate + leader detection + exercise P1.2 knob.**
  **P2.4a (substrate) DONE 2026-07-16, TDD red→green:** `infra/paxi/
  Dockerfile` (source-commit pin 6823d0b; first local build: image id
  bc12c64d3391, 16.1 MB — record per D2-adapted) + `LocalDockerProvider`
  3-node PAXOS/EPAXOS (`-algorithm` from the enum; generated minimal
  config.json — Config.Load() decodes into a defaults-prefilled singleton;
  **committed-probe-write quorum gate**, because election is lazy and no
  /health exists; fail-closed `requireLocalImage` with the build command in
  the message — the image exists in no registry). Acceptance green vs real
  Docker in 2.8 s: 3 nodes, quorum probe committed, size≠3 fails closed,
  zero leftover containers. Defaults kept deliberately: `-adaptive=true`
  (stable leader, internal forwarding) and reply-on-EXECUTE (commit+apply,
  = etcd semantics). EPAXOS wired but first exercised at P2.4c.
  **P2.4b (production PaxiDriver) DONE 2026-07-16, TDD:** Ballot-header
  leader detection (`leaderNodeIndexFromBallot`, fail-loud on multi-zone/
  malformed — never kill the wrong node) + F24 resolved by code
  (`endpointIndexFor` pinned by test: PAXOS = one client entry, EPAXOS =
  round-robin; node-ordered list kept for index identity) + fail-closed
  connect probe that also warms the pooled connection (no handshake in any
  latency sample — the flaw-B lesson). Unit: 4 contract tests. Acceptance
  green vs real Docker (3.0 s): committed write; leader detected via
  Ballot and **corroborated through an independent stack AND entry** (raw
  JDK HTTP via a follower names the same leader — forwarded replies carry
  the leader's ballot); follower-kill → writes keep committing on 2/3.
  Leader-kill deliberately NOT tested (F26; P3.3 preregisters it).
  **P2.4c DONE 2026-07-16 — P2.4 IS FULLY CLOSED.** EPaxos exercised for
  real (first time ever): the engine drove round-robin writes over all
  three replicas at 150 ops/s — **0 errors** (a dead or non-committing
  entry would have surfaced as ~1/3 errors), ~450 post-warmup commits.
  The D7 knob end-to-end: a c=10% run against the REAL cluster completed
  with 0 errors and carried its identity through the whole chain — engine
  `keyFor(c)` → real EPaxos commits → `epaxos/baseline/size3/c10/<runId>`
  path + `"conflict_ratio": 0.10` manifest + `status: complete`. Laptop
  numbers stay functional evidence only; the fast-vs-slow-path performance
  question is the campaign cluster's (G3 cross-validates vs Paxi's own
  benchmarker per D4).
  **Mechanism corrected 2026-07-16 (F22): paxi has NO `/state` endpoint** —
  verified against ailidani/paxi @ 6823d0b (= master HEAD; the Dockerfile
  pin): http.go registers only `/`, `/history`, `/crash`, `/drop`. Leader
  detection instead reads the **`Ballot` response header** every committed
  paxos write carries (paxos/replica.go HTTPHeaderBallot; format
  `"<n>.<zone>.<node>"`, and the ID part IS the leader — paxi's own client
  parses exactly this). Also verified: the Atoi int-key contract
  (`strconv.Atoi(r.URL.Path[1:])` — the P1.1 re-verify note is CLOSED),
  `server/server.go` flags (`-id Z.N -algorithm paxos|epaxos -config`),
  config.json shape (`address` tcp:// + `http_address` http:// maps, IDs
  "1.1".."1.3"), lazy leader election (first request triggers P1a — the
  provider must gate start() on a committed probe write), and go.mod with
  zero external requires (no go.sum issue building at the pin).
- **P2.5 — HotStuff SUMMARY parser — DONE 2026-07-16 (TDD red→green).**
  `driver.HotStuffSummary` (record + strict `parse`): the SUMMARY block is
  HotStuff's ONLY metrics source, so parsing fails CLOSED — a missing
  field throws NAMING the field (never fabricate a thesis number), zero or
  duplicate SUMMARY blocks are refused (an appended rerun log is
  ambiguous), thousands separators stripped, upstream's `?` committee size
  fails rather than defaults. End-to-end TPS/latency = client-observed
  primaries; Consensus TPS/latency = protocol-internal. 4 unit tests.
  **Honest caveat:** the fixture is reconstructed VERBATIM from the
  emitting code (asonnino/hotstuff benchmark/logs.py result(), fetched
  2026-07-16) — no real HotStuff run has flowed through the parser yet;
  that first happens with the Phase-C substrate. Re-pin against a real
  fab.log then.

**GATE G1: SIGNED OFF by the author, 2026-07-16** (in-suite evidence
accepted; no separate calibration/local/ archive requested). All five M2 driver acceptances exist as
permanent regression tests: M2.1 etcd (leader x-validated vs HTTP stack),
M2.2 Kafka (flaw-B: order-of-magnitude vs perf-test locally, 15% at
G3/M6.1 — last settled-machine ratio 0.50x, historical 0.95x), M2.3
CometBFT (flaw-A: 602 tx/s, 100x the retired ceiling), M2.4 Paxi
(Ballot-header leader detection corroborated independently + EPaxos
exercised + D7 e2e), M2.5 HotStuff parser (fixture-level — see caveat).
The plan's "archived under calibration/local/" formality is satisfied by
the suite + this ledger + LOCAL_TESTING's captured expectations; confirm
or request a separate archive tree before advancing to P3.
- **P2.6 — Safety oracle scope decision + implementation** *(2026-07-07 review)*.
  Methodology §4.2's durability probe covers Kafka/etcd/CometBFT only. Extend:
  Paxi GET read-back sample audit; HotStuff documented as no-oracle (loud
  caveat in every figure). Evaluate **Porcupine** (Go linearizability checker)
  on etcd histories as a stretch goal; decide before M5.5 lands.
- **P2.0 (conditional) — Scheduler scaling** *(review F8)*. The single-threaded
  open-loop scheduler (parkNanos jitter + inline submit cost) may cap
  achievable rate below Kafka's saturation. Trigger: G1 shows achieved <99%
  of target at required rates. Response: shard the schedule across N threads
  with per-thread phase offsets (mechanical; design note only until triggered).

*Extend `LocalDockerProvider` per system as its driver lands (KRaft trio,
Kafka+ZK per D10 — zk1-3 + broker1-3 colocated, CometBFT testnet, Paxi trio).*

---

## P3 — Infra & remote  *(apply/billing gated behind G2; authoring is not)*

- **P3.1 — `cloud-init.yaml` — DONE 2026-07-08** (see snapshot).
- **P3.2 — `main.tf` D8/D9/D11 parameters — DONE 2026-07-08** (see snapshot).
- **P3.3 — `RemoteSshProvider` + FaultInjector + golden tests** (G2 — human
  read-through of recorded remote command sequences before any VM is billed).
  **P3.3a (M4.1) DONE 2026-07-16, TDD:** `SshExecutor` SPI (ExecResult
  carries exit+stdout+stderr — no stream ever discarded; `execOrThrow`
  fails closed naming the command AND the remote stderr) +
  `RecordingSshExecutor` (test scope — the golden-file substrate: every
  command verbatim, in order, as `host:port$ command`) + `SshjExecutor`
  (pooled client per host:port, 30 s command bound, PromiscuousVerifier
  documented for the private net). Acceptance vs a REAL key-authenticated
  sshd in Docker (digest-pinned linuxserver/openssh-server): stdout
  verbatim, non-zero exit + stderr surfaced, fail-closed throw, pooled
  reuse. 4 tests green.
  **P3.3b (M4.2, etcd) DONE 2026-07-16, golden-file TDD:**
  `RemoteSshProvider` (etcd size 1..provisioned; every other system fails
  closed until its block lands with its own golden) driven ENTIRELY
  through SshExecutor. The golden was written FIRST as the spec —
  `src/test/resources/goldens/etcd-size3-start-stop.txt` carries a G2
  read-through checklist in its header (real private IPs everywhere,
  --network host, digest-pinned image, advertise URLs per node,
  deterministic thesis-etcd<i> names, curl-on-node health, full
  teardown) and the test compares the recorded sequence VERBATIM.
  Pre-clean and teardown accept only "No such container" as a benign
  non-zero (never `|| true`); the health gate polls until a deadline and
  fails closed NAMING the node. F20 resolved (real IP in NodeHandle,
  pinned by test; javadoc defines per-substrate semantics). 4 tests.
  NOT yet verified: real-VM behavior (docker stderr wording, cloud-init,
  private-net routing) — exactly what the P3.4 canary exists for.
  **P3.3c (M4.3, remote FaultInjector) DONE 2026-07-16, golden-file TDD:**
  `SshFaultInjector` on the same seam — kill (docker kill, abrupt SIGKILL),
  packet_loss (netem on the iface RESOLVED at runtime from `ip -o route
  get <peer>` output — never assumed eth0), partition (PAIRWISE node-IP
  DROP rules preserving the loadgen→leader path, never a subnet block),
  slow_node (HOST stress-ng, cloud-init-installed, hard --timeout),
  double_kill (deterministic 0+1). Every applied fault registers its undo;
  `heal()` replays LIFO, best-effort but LOUD (WARN on non-zero, never
  `|| true` — undo commands legitimately non-zero when there's nothing to
  undo, and tc/iptables/pkill can't distinguish that at the CLI). Golden
  `src/test/resources/goldens/etcd-size3-faults.txt` (per-scenario blocks,
  network-invariant checklist in the header) matched verbatim; 5 tests
  incl. iface-resolved-not-assumed, subnet-never-blocked, and
  heal-emitted-even-when-inject-throws. NOT verified on a real VM (sudo
  availability, exact tc/iptables/`ip route` output, stress-ng effect) —
  the P3.4 canary's job. **F13/F19 targeting inherited from apply()
  (pinned by FaultInjectorApplyTest); F26 (paxi leader_kill) LOCKED as the
  adaptive-mode wedge — see F26-DECISION.**
  **P3.3d-paxi (RemoteSshProvider PAXOS/EPAXOS) DONE 2026-07-16,
  golden-file TDD:** the provider now dispatches etcd vs paxi; the paxi
  branch generates config.json with REAL private IPs (address tcp:1735 +
  http_address http:8080, single-zone IDs), writes it with a
  single-quoted printf (human-reviewable, no base64), bind-mounts it to
  /config.json, runs `paxi:6823d0b -id 1.<i> -algorithm {paxos|epaxos}`
  (D4, one binary), gates readiness on a COMMITTED PROBE WRITE (no
  /health; election is lazy). `-adaptive` stays default per F26. Golden
  `paxos-size3-start-stop.txt` matched verbatim; epaxos swaps only the
  algorithm token; **the F26 wedge is pinned** — paxi leader_kill records
  exactly one `docker kill thesis-paxi<leader>` and nothing to heal.
  7 provider tests.
  **P3.3d-kraft (RemoteSshProvider KRAFT) DONE 2026-07-17, golden-file
  TDD:** the highest-risk remote recipe, de-risked in two verified steps —
  first `KraftMultiBrokerFormationTest` proved the env-var contract BY
  EXECUTION (3 combined-mode brokers on a user-defined network, quorum,
  acks=all under min.insync.replicas=2, Isr=3), THEN the golden
  `kraft-size3-start-stop.txt` was written as the spec encoding the
  remote deltas: voters + advertised listeners on REAL private IPs (F20),
  `--network host` binding :9092/:9093 natively (D2), fixed
  KAFKA_CLUSTER_ID (auto-formats storage; NO volume — state dies with
  the container, clusters byte-fresh), internal topics RF=3/txn-min-ISR-2
  (bench topic stays KafkaDriver.connect()'s), readiness = per-node
  "Kafka Server started" log gate THEN the api-versions quorum oracle on
  node1 counting "(id:" headers == cluster size (2-of-3 would serve
  acks=all silently degraded — refused, fail-loud with observed vs
  required). clientEndpoints are BARE host:port (Kafka bootstrap
  contract). 3 new tests; golden matched verbatim. Remaining P3.3d:
  KAFKA_ZK (D10 colocated ZK+broker — needs its own verified local shape
  first, same pattern), CometBFT (4-validator testnet genesis), HotStuff;
  then the G2 human read-through of ALL goldens.
  **Remote-deltas preregistration (2026-07-16 — the topology/network/
  traffic changes between the local Docker substrate and the servers;
  every golden must reflect these):**
  1. **F20 resolves here**: on the remote provider, `NodeHandle.privateIp`
     carries the REAL private IP (10.0.0.11+ from the generated
     inventory); the P2.4a test's alias pin (F25) is updated in the same
     increment.
  2. **Host networking, no mapped ports** (D2): clientEndpoints() become
     `http://10.0.0.1x:<native port>`; cluster-formation flags advertise
     private IPs, not Docker aliases. Provider health gates run ON each
     node (curl 127.0.0.1 via SSH — the SSH hop itself proves loadgen→node
     reachability); the loadgen→SERVICE path is proven separately by every
     driver's fail-closed connect() probe (F36 wording fix, 2026-07-18 —
     the code was right, this sentence was not).
  3. **netem must shape the PRIVATE interface**: resolved per node at
     runtime via `ip -o route get <peer_private_ip>` and parsed from the
     recorded output — never an assumed `eth0` (on Hetzner the public and
     private NICs differ; shaping the public iface would fault the admin
     path and leave consensus traffic untouched: a silent no-op fault).
  4. **Partition preserves the measurement path**: iptables rules block
     the leader↔other-NODE private IPs pairwise, never the subnet — the
     loadgen→leader path must survive, because observing the partitioned
     leader IS the measurement. Heal in `finally`, provably always emitted.
  5. **Timing**: inter-node RTT moves from loopback-µs to real-NIC
     0.2–0.3 ms (D1) — the 5 s driver bounds and 30 s command bound are
     unaffected; health/probe RETRY loops must tolerate the extra
     round-trips, and saturation numbers will differ from laptop numbers
     by design (only cluster numbers are thesis data).
  Notes: evaluate **Pumba** (runs on each VM, Docker-native kill/netem — no
  data-path hop) as the fault executor vs hand-rolled `tc`/`iptables`; golden
  tests bind either way. Fault semantics to preregister while writing goldens
  (review F13): PARTITION isolates the *leader* (not node 0), PACKET_LOSS %
  becomes a parameter, DOUBLE_KILL on BFT n=4 is an intentional
  liveness-loss demonstration (2 > f=1) and is documented as such; plus
  F26 (paxi leader_kill: adaptive-wedge vs ephemeral_leader vs /crash?t=
  — decide before the paxi goldens).
- **P3.4 — Canary** (one etcd cell on 2 temporary VMs, < €0.10).
- **P3.5 — Pre-apply price/plan verification.** `hcloud server-type list` →
  confirm ccx13/ccx23/ccx33/cpx21 names + prices; sync `local.hourly_eur` in
  main.tf and the runbook §2 table. (cpx21 price is unconfirmed post-2026-06.)

---

## P4 — Observability & validity (M5, after canary)

- **P4.5 — SUT log + docker-events capture per block** *(gap found by the
  2026-07-09 execution/cost review — see EXECUTION_AND_COST_MODEL §6)*.
  After each system block (and after every fault run), pull `docker logs`
  of every SUT container and a `docker events` audit into
  `logs/<system>/<runId>/` on the loadgen, collected to the laptop with the
  results tree. **HotStuff hard-depends on this** (its client SUMMARY lines
  are its only metrics source, P2.5 parses them) and validity gate 4 needs
  the events audit. TDD: local-run variant captures a container's log;
  fixture test that a restart appears in the events audit.

- Role-label retrieval fix — **DONE 2026-07-08** (see snapshot).
- **P4.1 — ValidityChecker** (M5.5) implements the six gates + the
  **empty-series-fails rule** (methodology §4 meta-rule) + loadgen-steal (D11).
- **P4.2 — PrometheusExporter** (M5.4) per runbook §5: ±15 s padding,
  query_range step=5s, `metrics/*.csv` per run.
- **P4.3 — ZK + JMX metric-name verification** (M5.2): the ZK :7000 metric
  names and Kafka JMX names in `export_queries.txt` are unverified until the
  exporters run — pin the jmx rules file in the repo and fixture-test both.
- **P4.4 — Grafana dashboards as code** (M5.6, unchanged).

Then M6 (calibration → pilot → campaign, Gate G3) per `IMPLEMENTATION_PLAN.md`
and `CAMPAIGN_RUNBOOK.md`.

---

## Review-findings ledger (2026-07-08 hard review — nothing gets lost)

| # | Finding (file:line) | Task | Status |
|---|--------------------|------|--------|
| F1 | No per-run histogram persistence → pooling impossible | P1.3 | **DONE** |
| F2 | Failover uninstrumented (1s buckets only) | P1.4 | **DONE** (manifest field → P1.6) |
| F3 | loadgen PromQL regex could never match | obs fix | **DONE** |
| F4 | Key contract incompatible with Paxi int keys | P1.1 | **DONE** |
| F5 | Manifest pins nothing methodology claims | P1.6 | **DONE** |
| F6 | Zero tests; CO correction unpinned | P1.5, P0.4 | P1.5 **DONE** (11 tests green, drain race fixed); P0.4 pending |
| F7 | `close()` swallows exceptions (ClusterProvider.java:31) | P0.4 | **DONE** |
| F8 | Single-thread scheduler ceiling | P2.0 | conditional |
| F9 | Loadgen shared vCPU + no steal gate | D11 + P4.1 | D11 **DONE**, gate pending |
| F10 | Kafka+ZK topology undecided | D10 | **DONE (locked)** |
| F11 | Magic `warmup+300` drops zero rows (CsvResultsWriter.java:47) | P0.4 | **DONE** |
| F12 | status=complete under error mass (CsvResultsWriter.java:62) | P0.4 | **DONE** |
| F13 | Fault semantics unregistered (ClusterProvider.java:44-52) | P3.3 | pending |
| F14 | No placement group; stale cost; missing cloud-init | P0.0 | **DONE** |
| F15 | analyse.py/visualizer absent though contract targets them | see note | open |
| F16 | Plan M3 substrate conflict | plan amended | **DONE** |
| F17 | Nits: charset, div-zero, HttpClient close, max≈bucketMid, tag-pins, JMX names | P0.4/P1.1/P1.3/P4.3 | div-zero, charset, max (HDR exact) **DONE**; HttpClient-close + tag-pins + JMX names pending |

**2026-07-15 second hard review — findings F18–F21:**

| # | Finding (file:line) | Task | Status |
|---|--------------------|------|--------|
| F18 | jetcd put has no client deadline → quorum-lost write bounded only by etcd's ~7 s server grace (or nothing, if the picked endpoint is dead); drain barrier can outwait/hang the run (EtcdDriver.java:74, WorkloadEngine.java:141) | fixed in P2.1 | **DONE 2026-07-15** (TDD; orTimeout 5 s; SPI contract documented; 52 green) |
| F19 | `FaultInjector.apply()` default contradicts F13 preregistration: NETWORK_PARTITION isolates node 0 not the leader; PACKET_LOSS hardcodes 5% (ClusterProvider.java:54-63) | fixed pre-P3.3 | **DONE 2026-07-15** (TDD, recording-injector test per scenario; red showed partition:0 + loss:5) |
| F20 | `NodeHandle.privateIp` carries the Docker network ALIAS ("etcd1"), not an IP (LocalDockerProvider.java:93) — semantic trap for RemoteSshProvider | P3.3 | **DONE 2026-07-16** (P3.3b): RemoteSshProvider fills a REAL private IP (pinned by test); NodeHandle's javadoc now defines the field's semantics per substrate (real IP remotely, alias locally where the alias IS the address). F25's local alias pin stays valid. |
| F21 | Manifest JSON string-concat leaves runId/imageRef unescaped (CsvResultsWriter.java:137+); and nothing pins the campaign's 1024 B value size (Main defaults 256) | M3.3 | open — campaign runner must set valsize=1024 and runIds stay `[a-z0-9]+` until escaped |

Doc-drift items fixed 2026-07-15: README (status/caveats/handoff/layout),
METRICS_AND_SOURCES (banner + §0 + retired-probe labels + HS 1024 B
requirement), CONTINUATION_PROMPT (next increment = P2.2). Still stale,
low-priority (superseded text is overridden by authority order): MASTER_PLAN
D3/§2 diagram (loadgen CPX21, €0.15/h — void per D11 + runbook §2),
IMPLEMENTATION_PLAN M4.5 (hcloud script wording — superseded by P0.0
Terraform). F15 (analyse.py/visualizer not in repo) remains open.

F15 note: locate `analyse.py` + the React visualizer in the pre-rebuild
archive and vendor them (or a golden contract test) into this repo before
M6.4 — the "EXACT output contract" is otherwise unverifiable. Not yet a
numbered task because the source location is outside this repo; resolve with
the author.

**2026-07-16 third hard review — findings F22–F25** (full code+docs pass;
suite re-verified by execution first: 71 committed tests green, plus the
in-flight P2.4a red test failing for the right reason):

| # | Finding | Task | Status |
|---|---------|------|--------|
| F22 | P2.4's planned leader-detection endpoint `/state` DOES NOT EXIST in paxi @ 6823d0b — replacement: the `Ballot` response header (see P2.4 above); Atoi key contract verified, closing the P1.1 note | P2.4 docs | **DONE 2026-07-16** (plan corrected in PENDING_TASKS/IMPLEMENTATION_PLAN/MASTER_PLAN/MEASUREMENT_DIAGRAMS) |
| F23 | Engine bucket array sized duration+5 == the 5 s driver bound: a commit in second duration+5 (timeout slop) was dropped from buckets while the histogram kept it (WorkloadEngine ctor) | engine | **DONE 2026-07-16** (TDD red→green: `lateCompletionsUpToTheDriverBoundStayInTheBuckets`; capacity now duration+6) |
| F24 | PaxiDriver round-robins endpoints for PAXOS too — the "Paxos gets ONE endpoint" rule exists only as calling convention; also currentLeaderIndex() needs the full node-ordered list to return an index | P2.4b | **DONE 2026-07-16** (TDD: `endpointIndexFor` — PAXOS pins endpoint 0, EPAXOS round-robins; node-ordered list kept for identity) |
| F25 | The new P2.4a test pins `privateIp()=="paxi2"` — deepens the F20 alias-in-privateIp trap; both must move together at P3.3 | P3.3 | open (noted on F20) |

F17 residual "HttpClient close" — **DONE 2026-07-16**: EtcdHttpDriver and
PaxiDriver `close()` now call JDK 21 `HttpClient.close()` (bounded: every
request carries a 5 s timeout). Remaining F17 residuals: tag-pins, JMX names
(P4.3). Also fixed: LOCAL_TESTING build-time drift (~1.5 → ~3 min measured
2026-07-16 as integration tests accumulated).

| # | Finding | Task | Status |
|---|---------|------|--------|
| F27 | The two G1 flaw-regression thresholds sat inside laptop environmental noise: parity measured 0.16x under a writeback storm (band is 1/3x–3x) and CometBFT flaw-A measured 297.2 tx/s vs its >300 line after 2 h of suite load (602 settled). Two consecutive gates tripped on margins, not regressions. | suite | **DONE 2026-07-16** — CometBFT tripwire moved 300→100 tx/s (~17x the ~6 tx/s broken-class ceiling; order-of-magnitude per the engine-test tolerance philosophy; the 602 tx/s G1 ACCEPTANCE stays ledgered). Parity band already order-of-magnitude; its javadoc gained the pressure-diagnosis rule. |
| F26-DECISION (2026-07-16, author) | **Paxi leader_kill = ADAPTIVE-MODE WEDGE (honest).** Keep paxi's default `-adaptive=true`; a hard `docker kill` of the detected leader produces a measured LIVENESS WEDGE (forwarded writes fail at the 5 s driver bound, no re-election) — reported as the honest "paxi ships no failure detector" result, an IMPLEMENTATION property, contrasted with etcd/Raft's sub-second failover. No ephemeral_leader, no /crash?t=. Consequence: paxi leader_kill has NO failover-ECDF (F4) — a documented absence, never a fabricated number; the recovery-profile figure (F5) shows the wedge. leader_kill needs NO special injector code — `FaultInjector.apply` already emits `kill(leader)`, and the wedge is emergent. | preregistered expectation | **LOCKED** — bakes into the paxi remote golden (P3.3d) and methodology §7. |
| F26 | **Stock paxi cannot recover from a hard leader kill** (source-verified @ 6823d0b): a follower that knows a ballot always FORWARDS client requests to that leader (paxos/replica.go handleRequest) — there is no failure detector and no re-election trigger. Refined by deeper source read: on an ESTABLISHED-but-broken connection the transport writer just logs and drops (transport.go Dial goroutine), so a hard leader kill produces a silent WEDGE — forwarded writes get no reply and fail only at our F18 5 s bound, indefinitely; the **panic** path (socket.go:98-100, dial retry 100×50 ms) fires only when no prior connection existed. Consequently the LEADER survives follower death (its P2a to the dead peer is logged-and-dropped; 2/3 quorum commits — empirically confirmed by PaxiDriverTest's follower-kill). Paxi's designed fault primitive is `/crash?t=` (socket pause, process alive). `-ephemeral_leader=true` enables follower self-election but changes baseline semantics. | P3.3 | open — **preregister the paxi leader_kill design** before the goldens: adaptive-mode + documented wedge as the honest "no failure detector" result, vs ephemeral_leader mode, vs paxi's own Crash(t); verify empirically whichever is chosen. Expected-vs-observed material for the thesis (implementation property, not protocol property). |

**2026-07-17 fourth hard review — findings F28–F30** (full code+docs pass;
HEAD re-verified by execution FIRST in a throwaway worktree: 101/101 green;
all three findings fixed same day, TDD red→green; plus the P3.3d-kafka
prerequisite — the 3-broker KRaft formation test the 2026-07-16 classifier
outage had blocked — compile-fixed, executed green in 14.6 s, committed):

| # | Finding | Task | Status |
|---|---------|------|--------|
| F28 | `SshFaultInjector.slowNode` backgrounded stress-ng WITHOUT redirecting streams (`cmd & echo started`): the process inherits the exec channel's stdout/stderr, sshd holds the channel open until IT exits, and the 30 s command bound trips — the injection itself stalls 30 s and aborts on a real VM. The RecordingSshExecutor cannot see channel semantics, so the golden was green while the behavior was broken (the documented "goldens can't catch semantic SSH errors" class — caught pre-canary). Measured red vs a real sshd: ConnectionException after 36.6 s. | injector | **DONE 2026-07-17** — `nohup <cmd> >/dev/null 2>&1 & echo started`; golden SLOW_NODE updated with the load-bearing rationale; shape pinned by a real-sshd assertion (<5 s); heal's pkill still matches (nohup execs, cmdline unchanged) |
| F29 | Remote pre-clean removed only the NEW cluster's own container names on member nodes: a D8 size-down run (7→5/3) left stale members running on non-member nodes (spraying peer traffic at the new cluster), and a crashed cross-system block left a different system's containers eating CPU on measurement boxes — both silent stationarity violations | provider | **DONE 2026-07-17** — golden-first: pre-clean = `docker ps -aq --filter name=thesis- \| xargs -r docker rm -f` on ALL provisioned nodes (removeLeftovers semantics); new test pins the sweep at clusterSize < provisioned |
| F30 | `EtcdHttpDriver.currentLeaderIndex()` hardcoded `Optional.of(0)` (M0 stub) — the v6 "kill node1 and hope" trap, armed for whoever wires fault targeting through the fallback path; the single-endpoint driver structurally cannot know the leader | driver | **DONE 2026-07-17** — honest absence (`Optional.empty()`), red→green; no caller depended on the stub (EtcdDriverTest's cross-check uses its own raw HTTP-gateway ground truth) |

Suite after the four increments: **104 tests green** (`mvn21 clean verify`).
Doc drift fixed same session: README quick-start pointer (was "next: P2.4"),
CONTINUATION_PROMPT next increment (was P3.3c), stale "Last updated"
headers, LOCAL_TESTING count; `harness/dependency-reduced-pom.xml`
(shade-plugin artifact) untracked + gitignored; repo-local CLAUDE.md added
so sessions stop inheriting the unrelated `~/Downloads/CLAUDE.md`.

---

## 2026-07-18 fifth hard review + the "everything remote-ready" session

**Verification protocol of this session (user-authorized deviation):** the
author asked for all increments batched with ONE `mvn21 clean verify` at
session end instead of per-increment runs; every commit from this session
says so. Before any change, the committed HEAD was re-verified by
execution: **115/115 green in 3:58** — 114 committed tests plus the
until-then-UNCOMMITTED `HotStuffMultiNodeFormationTest` found in the
working tree (the P3.3d-hotstuff step-2 in-flight work), which passed in
21.5 s: the 4-node HotStuff formation is EXECUTION-VERIFIED (client
traffic committed through BFT consensus, every replica logging logs.py's
own commit regex).

**Findings F31–F38** (review first, fixes in the same session):

| # | Finding | Status |
|---|---------|--------|
| F31 | `SshFaultInjector.heal`'s `pkill -f 'stress-ng --cpu 2'` matches the remote `sh -c` shell running the pkill itself (its cmdline contains the pattern) — every slow_node heal would SIGTERM its own shell and turn the loud WARN channel into permanent noise | **FIXED** — `stress-n[g]` character-class trick; golden updated; canary checklist notes the one EXPECTED WARN (nothing to undo after --timeout) |
| F32 | `Main.parse` silently ignored unknown argument keys: `--ratee 300` ran at the default rate — fail-open in a fail-closed CLI | **FIXED** — `requireKnownKeys` per command, TDD-pinned (`unknownArgumentKeyFailsClosed`) |
| F33 | Local-built images (paxi:6823d0b, hotstuff:dc01ac8) exist in NO registry: the committed paxi golden would fail at first contact on a real VM (docker pull of an unpullable image); registry images' first `docker run` pull could trip the 30 s SSH bound | **FIXED** — provider `requireImageOnNodes` gate (fail-closed naming node + `docker save \| ssh docker load` fix; goldens updated, test-pinned) + cloud-init pre-pulls the four registry digests at boot |
| F34 | PROJECT_STATE §3 diagram/§4 still said RemoteSshProvider, FaultInjector, and the Kafka/CometBFT drivers were "designed, not built" — the source-of-truth doc contradicted its own header | **FIXED** (docs) |
| F35 | A mid-start provider failure leaves just-started containers RUNNING on VMs (nodes register only after gates pass; KAFKA_ZK's ensemble is the exception via auxContainers) | **DECIDED + DOCUMENTED** — deliberate evidence preservation; F29 pre-clean sweeps them at the next start; stated in the provider javadoc |
| F36 | Remote-deltas preregistration item 2 said health gates "traverse the private net from the loadgen"; the implementation (correctly) curls ON the node — doc/code drift in the spec the goldens claim to encode | **FIXED** (wording, above) |
| F37 | IMPLEMENTATION_PLAN M2.2 still carried the raw "within 15%" acceptance that F27/G1 moved to G3/M6.1 — and the authority rule says the plan wins on acceptance criteria | **FIXED** (plan amended) |
| F38 | Nits: `awaitKafkaQuorum` message said "KRaft cluster" when gating KAFKA_ZK (fixed); dead `root` var in the new formation test (fixed); noted-not-fixed: SshjExecutor's lost putIfAbsent race returns an unpooled client (harmless), EtcdHttpDriver re-encodes the constant value per op (fallback path only), KRAFT and KAFKA_ZK share `thesis-k<i>` names (one system at a time + pre-clean make it safe; forensics ambiguity noted) | **FIXED / noted** |

**Shipped this session (all seven systems now remote-ready):**

- **P3.3d-hotstuff COMPLETE**: the formation test committed (with evidence),
  the remote golden `hotstuff-size4-start-stop.txt` written FIRST (keygen
  one-shots on node1, committee = fab config.py's exact shape on real
  private IPs with three ports per node, boot-level readiness stated
  honestly — commits need traffic and the client is the traffic source),
  and the provider HOTSTUFF branch matching it verbatim + endpoint/shape/
  image-gate tests. **RemoteSshProvider serves 7/7 systems.**
- **HotStuffLogAnalyzer**: logs.py ported to Java VERBATIM at the pinned
  commit (fetched dc01ac8 2026-07-18 — same regexes, same merge-earliest,
  same formulas, byte-identical SUMMARY template that the strict P2.5
  parser round-trip-validates). One deliberate deviation, our house rule:
  zero commits THROWS instead of emitting an all-zero SUMMARY. Live-log
  shape check (do real -vv lines match at dc01ac8?) is the first VM run's
  job — listed in the guide.
- **M3.3-core `remote-run`**: one campaign cell end-to-end from the loadgen
  — `Inventory` (typed, fail-closed parse of the Terraform-generated file),
  driver dispatch per system, fault thread at `--fault-at` targeting the
  DETECTED leader (F13/F19; replica 0 documented for EPaxos/CometBFT only),
  heal in finally, `environment=hetzner` results, fault-run SUT-log
  collection (P4.5 partial), and the HotStuff path (upstream client on the
  loadgen, whole-log collection, analyzer → summary.txt + logs + manifest).
  A fault run whose injection failed THROWS after writing forensics — it
  never masquerades as data. `RemoteLogs`: chunked `dd | base64` transfer
  (1 MiB per command) because sshj's read-after-join caps single-command
  output at the ~2 MB channel window — full -vv logs must survive intact
  (byte-accumulated, decoded once; truncation fails loud).
- **Docs**: `HOW_TO_CONTINUE.md` (the one-page map) and
  `PER_ALGORITHM_TEST_GUIDE.md` (per-algorithm test/debug/benchmark
  checklists + canary first-contact list).

**Result of the batched verification: 137/137 tests green, BUILD SUCCESS
(~4 min)** — 22 new tests (hotstuff provider ×3, analyzer ×5, campaign
×13, F32 CLI ×1) plus the committed formation test; every new golden
matched verbatim on the first run. **Batches 2–3 (MatrixRunner +
`campaign-run` CLI + observability/analysis) re-verified in a second
batched run: 143/143 green** — MatrixRunner ×5 + the `--dry-run` parser
pin; the observability/analysis work is non-Java (dashboards, analyse.py
`--selftest` green separately, shell/compose syntax-checked).

---

## 2026-07-21 sixth hard review + fixes (F39–F49, all closed same session)

**Protocol:** full code+docs read-through first (committed HEAD re-verified
by execution: 158/158 green incl. the then-uncommitted M5.5 package found
in the working tree); then one increment per commit, TDD red→green with
the red shown, a green `mvn21 clean verify` before EVERY push (six gates
run; the one batched-verify exception covers exactly the F45+M5.2-etcd
pair, stated in both messages). Suite grew 158 → **170 tests green**.
(Doc-drift note closed by this section: the "143" headline count was one
commit stale — HEAD had 146 committed tests before this session; NEXT-4b's
3 analyzer tests landed after the 07-18 close-out was written.)

| # | Finding | Status |
|---|---------|--------|
| F39 | The M5.5 ValidityChecker package sat UNCOMMITTED and unledgered in the working tree while §4/NEXT-5/README still said "not built" — the exact working-agreement violation (update state docs at session end) this file exists to prevent | **CLOSED** — landed HARDENED (F40–F44 fixed first), ledger updated |
| F40 | Gate 6 read metrics/clock_offset.csv but NO export query produced it — post-M5.4 every run would FAIL clock_discipline with a false "broken retrieval" diagnosis | **FIXED** — `clock_offset \| node_timex_offset_seconds` added; a new contract test pins EVERY consulted metric name against export_queries.txt (the drift class now fails in-suite) |
| F41 | Gate 3 corroboration was etcd/cometbft-only: every Kafka fault run would FAIL with a wrong diagnosis, every paxi/hotstuff fault run would FAIL for metrics they NEVER had (documented §2 limitation; the F26 wedge changes no leader BY DESIGN); node_up can never witness a container kill (host node_exporter survives) | **FIXED** — per-system witness map (etcd_leader_chg / cmt_rounds / kafka_urp>0), honest SKIP naming P4.5 for paxi/hotstuff, node_up kept with its limit stated |
| F42 | HotStuff run dirs (summary.txt, no throughput.csv BY DESIGN) failed rate_adherence + convergence as if broken | **FIXED** — explicit SKIPs; a missing throughput.csv for driver systems stays FAIL |
| F43 | Methodology §4.1's window-ceiling half and §4.4's restart-audit half had NO gate row — silent absence in a "SKIP is loud" design | **FIXED** — window_headroom (awaits M5.3) + container_restarts (awaits P4.5) as loud SKIPs |
| F44 | checkTree aborted the whole walk on one corrupt manifest | **FIXED** — record-and-continue, uncheckable = not-valid loudly |
| F45 | HotStuffMultiNodeFormationTest asserted every-replica-commits at a FIXED instant: 3 observed fails 2026-07-21 with the lagging node VARYING while proposals held ~100 ms cadence — laptop pressure (F27 class), not regression | **FIXED** — deadline-polled gate (30 s), same rule as provider health gates; never-commits still fails; re-verified green ×2 |
| F46 | M5.2 SEQUENCING TRAP: prometheus.yml/dashboards/gate 3 assumed metric endpoints (etcd :2381, cometbft :26660, kafka :7071) that no provider start command opened — and the plan had M5.2 land AFTER the canary, i.e. the author would sign off G2 goldens that M5.2 must rewrite | **FIXED** — all three exporters wired NOW, probe-first (etcd flag live-verified on the digest; cometbft sed execution-verified in the formation test; kafka agent+rules execution-verified on a real broker in KafkaJmxAgentTest, closing P4.3's name question; agent 1.0.1 pinned across pom/cloud-init/goldens by test — 1.1.0 was a wrong guess, Central's metadata is the truth). **The seven goldens are FINAL text for the G2 read-through** |
| F47 | Fault thread timing: delay counted from thread start (pre-connect), and a mark before EventLog.start() would produce a garbage failover that reads real | **FIXED** — isStarted() wait + fail-loud guard; mark semantics documented (mark = injection COMPLETE ⇒ failover_ms is a lower bound for multi-command faults; methodology §4.3) |
| F48 | Spec rejected short BASELINE runs over the DEFAULTED fault-at they never read | **FIXED** — validation applies to fault scenarios only |
| F49 | RemoteLogs snapshotted to /root, assuming a root --ssh-user | **FIXED** — /tmp |

**Noted, not fixed (deliberate):**
- **N1 (watch at M6.2):** for preregistered-failure scenarios (F26 paxi
  wedge, DOUBLE_KILL liveness demo) the writer's honesty rule
  (error_rate>0.5 ⇒ status=failed) could exclude exactly the preregistered
  evidence from analyse.py AND make resume re-run the cell. At campaign
  rates the wedge's error mass stays well under 0.5 (the in-flight window
  throttles wedged issue to ~window/5 s ops/s), so it should not trip —
  verify at the pilot; if it does, the decision is scenario-aware status
  vs analysis-side handling, the author's call.
- **N2 (author's call):** `corpus/` tracks the SIGNED thesis-assignment
  form (personal document). The GitHub repo is PRIVATE today, so no
  exposure — but if it is ever made public the file (and its git history)
  goes with it; removal then needs a history rewrite, not just `git rm`.

---

## 2026-08-14 seventh hard review (F50–F68)

**Protocol:** full code+docs read-through against committed HEAD 9e9fbdd
(re-verified by execution FIRST: `mvn21 clean verify` green, 170/170 — the
documented count confirmed). Findings F50–F67 and their evidence are in the
review write-up; F68 was found while gating this session's own work. Then
one increment at a time, TDD red→green with the red shown, a green
`mvn21 clean verify` before each checkpoint. Suite 170 → **176 green**.

| # | Finding | Status |
|---|---------|--------|
| F50 | A fault run whose injection FAILED was written as a complete, valid, permanently-skipped cell: `RemoteRunner` writes CSVs+manifest BEFORE rethrowing `injectionFailure`, so `status: complete` + `fault_injected_at_ms: null` → `MatrixRunner.alreadyComplete` skips it forever on resume, gate 3 calls it a "baseline run" and SKIPs (`valid: true`), and analyse.py emits a `leader_kill` cell carrying UNDISTURBED BASELINE numbers — the v6 reclassification class, reachable from the campaign path. Proven end-to-end through the real writer/runner/checker classes | **F50a CLOSED** (TDD) — `CsvResultsWriter` writes `status: failed` when `scenario != BASELINE` and the EventLog carries no mark. The mark is stamped only after `apply()` RETURNS, so mark-present ⇔ fault-fired; putting the rule in the WRITER (not the caller) makes resume, validity and analysis read one truth and disarms the trap for future callers. Measured after: `alreadyComplete` false, analyse.py excludes with reason `status=failed`. **F50b/c ALSO CLOSED** (increment 2, TDD): gate 3 now reads `scenario` and FAILs (`'leader_kill' run carries NO fault mark`) instead of SKIPping as "baseline run"; `RemoteRunner` checks `faultThread.isAlive()` after its join instead of discarding the timeout; the fault thread's engine-start wait is BOUNDED (`awaitEngineStart`, 2 min) — the unbounded loop leaked one spinning daemon thread per failed fault cell whenever `connect()` died. Whole chain re-measured: `status failed` / `alreadyComplete false` / `valid=false` with the right diagnosis / analyse.py excluded |
| F51 | `SshFaultInjector.undo` is a plain `ArrayDeque` pushed by the fault thread and popped by the main thread in `heal()`, which `RemoteRunner` calls unconditionally after a `join(30_000)` that may time out — a lost undo entry leaves a live netem qdisc / iptables DROP poisoning every later run on that VM (the stationarity violation F29 exists to prevent) | **CLOSED** (increment 3, TDD) — every mutating operation now holds the injector's monitor, so `heal()` WAITS for an in-flight injection instead of interleaving with it. The red was deterministic (a latch holding `tc qdisc add` in flight, commands recorded in COMPLETION order): heal issued `tc qdisc del` BEFORE the add — recorded order `[ip -o route get, tc qdisc del, tc qdisc add]` — so the delete failed as "nothing to undo" (a WARN by design) and the netem rule SURVIVED. Serializing dominates a concurrent deque, which would have stopped collection corruption but still allowed the interleave; the `ArrayDeque` stays, guarded |
| **F69** | **No sweep undoes HOST-level fault state.** Host fault undo exists ONLY inside `heal()` (verified: `tc qdisc del` / `iptables -D` / `pkill` appear nowhere else in main), and `PRECLEAN_CMD` removes `thesis-*` CONTAINERS only. So if the campaign JVM dies between inject and heal — OOM, Ctrl-C, SSH transport loss, power — the netem qdisc / iptables DROP / stress-ng SURVIVES, and the next `start()` sweeps containers while the fault silently shapes every later run on that VM. F29 closed exactly this class for containers; the host half was never covered. F51's fix removes the *in-process* race but cannot help a dead process | **OPEN** — NEXT-9 |
| F52 | `error_rate` is computed and written but consulted by NO validity gate; `status` tolerates 50% failures, and `rate_adherence` SKIPs for saturation runs — so a baseline where 49% of ops failed reports `valid: true`. §4's gate 2 (durability) is the intended home and is a documented SKIP pending P2.6, but a manifest-only error-rate gate needs no Prometheus | **OPEN** — needs a threshold + scope decision (fault runs SHOULD error) |
| F53 | packet-loss percent is hardcoded 30 in `MatrixRunner` (not a `Block` field, so a block cannot vary it), absent from the manifest AND from `config_hash` — two `packet_loss` cells at different percentages hash identically, so §1's "any cell individually reproducible" fails for that scenario. `METRICS_AND_SOURCES.md:229` preregisters **5%** and predicts "modest degradation" | **DECIDED 2026-08-14 (author): SWEEP BOTH 5% AND 30%** — severity becomes a workload factor like D7's conflict ratio (**D14**; methodology §1 now lists six factors, both expectations preregistered in METRICS_AND_SOURCES before any run). Plumbing OPEN as S1.1. **Implement the identity half in the SAME increment or the decision makes things worse:** `RunIdentity.dir()` is `<system>/<scenario>/size<N>[/c<pct>]/<runId>`, so a 5% and a 30% `packet_loss` cell resolve to the SAME directory and the same `config_hash` — sweeping severity without adding it to run identity converts a hidden ambiguity into an active overwrite, i.e. the exact v6 path-collision class `RunIdentity`'s own javadoc claims is inexpressible. Cost of the sweep: +5 runs/system ≈ +50 min ≈ +€0.25 |
| F54 | `analyse.py`'s honesty rules fail OPEN on fields a v1 manifest lacks: missing `environment` ≠ "local" so laptop runs are INCLUDED (verified: the committed M0 tree analyses as 2 included / 0 excluded), and missing `duration_secs` keeps the drain tail, reporting 229.9 ops/s for a run that achieved 306.5 | **OPEN** — increment 4 |
| F55–F57 | Methodology/semantics needing an author call: `METRICS_AND_SOURCES.md` describes partition as "full isolation" (impl preserves the loadgen→leader path BY DESIGN) and slow_node as Pumba/`yes` (impl is host `stress-ng`); fault DURATIONS differ across scenarios (slow_node self-ends at 120 s, others persist to `heal()` — 120/300 vs 240/300 measured seconds faulted); `packet_loss` netem shapes the measurement path too, while partition explicitly does not | **OPEN — decisions** |
| F58–F67 | Hygiene: HotStuff manifest hardcodes `status: complete` and omits `harness_version`/`config_hash`/`error_rate` (safe only while `runHotStuff` refuses non-BASELINE — becomes load-bearing at NEXT-4); F21 runId validation guards only `remote-run`; ValidityChecker javadoc claims an `instance` column it discards; `SshFaultInjector` SSHes to `privateIp()` where `host()` is the documented management address; `SshjExecutor` leaks a client on a lost putIfAbsent race; **export_queries.txt has 23 queries, README/PENDING_TASKS/LOCAL_TESTING still say 22** (F40 added `clock_offset`; LOCAL_TESTING states it as EXPECTED OUTPUT, so following that doc now shows a false failure); MEASUREMENT_DIAGRAMS header says 66 tests, line 194 says 170; README CLI list omits `campaign-run`; `collectSutLogs` skips KAFKA_ZK's colocated ZK containers; stray un-expanded brace dir under `bench/` | **OPEN — cheap** |
| **F68** | **`KafkaPerfTestParityTest` is the suite's only unstable gate and it red-lined `mvn21 clean verify` TWICE this session in two DIFFERENT ways — ratio 0.22x (band is 0.33–3.0), then 11% errors (`TimeoutException: Expiring 1 record(s) … 5000 ms`). Mechanism: it benchmarks a REAL broker, so it asserts on the laptop's spare capacity; both failures occurred at load average ~15 (Chrome on three cores), and the same test passed at 1.86x in isolation on the SAME tree minutes later. Its band (0.33x) is TIGHTER than the variance its own javadoc records observing (0.2x–2.8x across four laptop configurations).** Why it matters beyond annoyance: IMPLEMENTATION_PLAN's honest review names "gates holding under impatience" as the plan's deepest assumption — a gate that reds on unrelated background load is the one test training the author to wave a red build through | **OPEN — AUTHOR'S CALL** (it is a G1 regression test; narrowing/widening a gate is not a mechanical fix). Options: widen the laptop band to the documented evidence (~0.1x–10x — its stated job is catching the 100–1000× probe class, not a 3× one); raise/drop the 2% error assertion locally (the test's OWN comment calls these "honest timeouts, not a broken load model"); or tag it out of the default `verify` and run it deliberately, since the symmetric ±15% comparison already lives at G3/M6.1 on the cluster where the environment is controlled |

---

## 2026-08-14 (continuation) eighth read-through — findings F70–F74

**Protocol:** a second full code+docs pass over the SAME day's tree,
including the then-uncommitted F51 increment. Re-verified by execution
FIRST: `mvn21 clean verify` → **`Tests run: 177, Failures: 0, Errors: 0`,
BUILD SUCCESS, 5:06 min** (176 committed + the new F51 test), on Docker
29.6.2 with all seven images present. `KafkaPerfTestParityTest` (F68)
PASSED this run — consistent with "load-dependent gate", not "broken".
Two ledger items were reproduced rather than trusted: F54 (`analyse.py
harness/results` → 2 included / 0 excluded, reporting 229.9 ops/s for the
run PROJECT_STATE records at 306.6) and the F58–F67 query count
(`export_queries.txt` has **23** non-comment lines). No code changed in
this pass — findings only.

| # | Finding | Status |
|---|---------|--------|
| **F70** | **The failover measurement can void itself silently, and nothing catches it — the F50 class, one layer down.** `EventLog.dropped()` has NO production caller (grep: two tests only); it is absent from the manifest and consulted by no validity gate, although its own javadoc says "dropped > 0 must fail validity". `RemoteRunner.eventCapacity` caps the buffer at **4,000,000** (the fixed roof for saturation runs). PROVEN BY EXECUTION (standalone `EventLog`, campaign shape — duration 480, fault at 240 s, ~17k ops/s): `dropped = 81000`, `fault_injected_at_ms = 240000`, **`failover_ms = empty`** where ground truth was 900 ms. Downstream that reads as *"the fault fired and the system never recovered"* — the F26 paxi-wedge signature, manufactured by instrument overflow; `status` stays `complete` because the F50 rule only requires the MARK, which is present, and `analyse.py` drops `None` failovers with no exclusion line. Thresholds at campaign defaults: tail-drop above ~8.3k ops/s, the buffer passes the MARK ITSELF above ~16.7k ops/s. Net effect on F4: the ECDF loses exactly the highest-throughput trials, silently | **OPEN** — NEXT-10. Cheapest honest fix: `events_dropped` into the manifest + a validity gate (drops>0 ⇒ FAIL, or ⇒ failover unreportable); better: size the buffer from the prior sat block's measured throughput instead of a constant |
| **F71** | **`campaign-run` cannot express the runbook's failover-trial run shape.** `MatrixRunner.block()` hardcodes `durationSecs=480, warmupSecs=180, window=200, valueSizeBytes=1024`, and `Main.campaignRun`'s `requireKnownKeys` accepts no `duration`/`warmup`/`window`/`valsize`/`fault-at`/`loss` key. `CAMPAIGN_RUNBOOK.md` §3 specifies failover trials as **180 warmup + 180 measurement, fault at +60 (≈8 min)**; the code runs them at 180 + 300 with the fault at +240 (≈10 min). So either the runbook's budget table is wrong or the `Block` needs the knob — and today the operator has no way to choose. Same root as F53 (`loss` hardcoded 30) but wider: every per-scenario run-shape input is welded shut | **OPEN** — decide which shape is correct, then plumb (one `Block` field per differing input, or a per-scenario shape record) |
| **F72** | **`Scenario.mutatesCluster()` enforces nothing.** No production caller anywhere (grep: only its own declaration and two comments). The invariant IS honored — `RemoteRunner.run` constructs a fresh provider and calls `start()` for EVERY cell — but by unconditional recycling, not by the type system, contrary to `DATA_ANALYSIS_METHODOLOGY.md` §1 ("enforced in the harness type system, not by discipline") and `Scenario`'s own javadoc ("the campaign runner MUST recycle"). Nothing compile-time or runtime would stop a later "skip the recycle for baseline reps to save 2 min/run" from reintroducing v6's C4 | **OPEN — cheap.** Either make the claim true (assert/branch on it where the cluster is reused) or correct the two doc sentences to say "by unconditional per-cell recycling" |
| **F73** | **A fourth fault-semantics divergence, belonging with F55–F57.** `METRICS_AND_SOURCES.md` §leader_kill says EPaxos "kills a **random** replica instead"; `RemoteRunner.faultTargetIndex` uses **deterministic replica 0** (documented in-code as equivalent-by-symmetry). For a leaderless protocol, random-target vs fixed-target is a real methodological choice — a fixed target cannot surface position-dependent effects, a random target costs reproducibility — not a wording slip | **OPEN — decision**, batch it with F55–F57 |
| **F76** | **A `mvn21 clean verify` reported BUILD SUCCESS having run only 90 tests across 15 classes — skipping the very test that covered the change being gated.** Observed 2026-08-14 by the concurrent review session while gating increment 3 (F51); it held the commit rather than claim the green, re-ran, and got a complete 177/34. **It did not reproduce and the cause is unknown — deliberately not invented.** Recorded here because it existed only in that session's scrollback, and because of what it implies: the project's entire verification discipline rests on "a green `verify` means the suite ran", and this is one observed counter-example. A build that exits 0 having run half the suite would retroactively weaken every green-gate claim in the history, including the G1 sign-off. Related in spirit to F68 (a gate that goes red on unrelated load) — both are gate-integrity problems, in opposite directions | **OPEN — watch.** Mitigation already adopted by that session and now written into `GIT_WORKFLOW.md` gate 1: **read the count from Maven's own `Tests run:` summary line, never from arithmetic over report files**, and treat a suspiciously fast `verify` (baseline ≈5 min) as a failed gate until the count is confirmed. If it recurs, capture `target/surefire-reports/` and the full log BEFORE re-running — a re-run is what destroyed the evidence the first time |
| **F75** | **Two declared dependencies are shaded into the 77 MB uber-jar and used by nothing.** `picocli 4.7.6` appears in `src/` only inside a comment (`Main.java:23`, "M1.3 replaces this with picocli"); `micrometer-registry-prometheus 1.14.5` appears in `src/` **not at all** — that is M5.3's :9400 registry, still listed as open work. So the two milestones nearest to "declarative CLI" and "in-flight self-metrics" are each already paid for in build weight and supply-chain surface, with zero benefit taken | **OPEN — cheap.** Either spend them (M1.3 / M5.3) or drop them from the pom until they are spent. Called out because the plan in `SIMULATION_AND_RULES_ANALYSIS.md` §6 depends on both |
| **F74** | Hygiene, extending F58–F67: `SystemUnderTest.allContainerNames` and `isByzantine` have **zero callers** (dead API); `SystemUnderTest.containerName` is used only by `LocalDockerProvider` (yields `etcd1`, while `RemoteSshProvider` builds `thesis-etcd1` on its own path). Doc staleness beyond the items already listed: `LOCAL_TESTING.md:35` pins 170 tests (HEAD 176, tree 177) and `:430` states 22 queries as EXPECTED OUTPUT; `MEASUREMENT_DIAGRAMS.md` says 66 in its header and 170 at line 194; `HOW_TO_CONTINUE.md` still dates itself 2026-07-21 / 170 green; this file's own line 3 says "Last updated: 2026-07-21"; README's status block and caveats predate F50/F51 entirely. CORRECTION to F58–F67: the "stray un-expanded brace dir" is real but **untracked and empty** (`git ls-files` clean) — a local `mkdir -p` artifact, not a repo defect | **OPEN — cheap** |

---

## Simulation control, rules, and result confidence

The author's request of 2026-08-14 — full control of each simulation's
rules, per-simulation results with what-happened evidence, false-positive
attribution, a confidence level, in-flight collection, and offline analysis
tooling — is analysed and planned in **`docs/SIMULATION_AND_RULES_ANALYSIS.md`**
(analysis + plan, NO code, per instruction). Summary of its conclusions:
no framework is adopted (Gatling, Drools, Jepsen, Chaos Toolkit all
surveyed and refused, with reasons); four *artifacts* are copied instead;
two author decisions are open — **D12** (simulation specs as typed Java
records + serialized `simulation.json`, recommended, vs a parsed YAML file)
and **D13** (confidence as an ordinal grade mechanizing methodology §6,
recommended, vs a numeric score, refused). Its increments S0–S5 fold
M1.3/M5.3/M5.4/P4.5/M6.4 into the same spine; S0 (F69 + F70) is recommended
BEFORE the G2 read-through because both touch goldens or the manifest.
Load-bearing constraint recorded there: the confidence grade must not ship
before the validity gates actually evaluate — measured today at **1 of 10**.

## Immediate next increments (LLM-ready specs)

**NEXT-10 — F70: make an overflowed EventLog unable to pose as a
measurement (increment 6).** Red first: a run whose buffer fills before
the fault mark currently writes `fault_injected_at_ms` set,
`failover_ms: null`, `status: complete` — pin that as the failing
expectation. Then the writer/gate decision (author's call, because it
sets what a missing failover MEANS): `events_dropped` becomes a manifest
field, and either `CsvResultsWriter` refuses `complete` when drops > 0 on
a fault run, or `ValidityChecker` gains a `event_log_integrity` gate that
FAILs on drops > 0. Both keep the F50 discipline — one truth, read by
resume, validity and analysis. Sizing the buffer from a measured
saturation input is the follow-on, not the fix.

**NEXT-6 — F50b/c. DONE (increment 2, TDD red→green, 176 green.)**

**NEXT-7 — F51. DONE (increment 3, TDD red→green.)**

**NEXT-9 — F69: sweep HOST fault state at `start()` (increment 5).** Add
the host-level undo to the remote pre-clean so a fault cannot outlive the
process that injected it. Shape (author's call on the exact commands,
because these are destructive on a shared host and must be scoped to what
the harness itself creates): per provisioned node, alongside
`PRECLEAN_CMD` — `tc qdisc del dev <resolved-iface> root` (ignore
"nothing to delete"), remove the harness's own DROP rules rather than
`iptables -F` (a flush would take out anything else on the box), and
`pkill -f 'stress-n[g] --cpu 2'`. Goldens must be updated FIRST as the
spec (they are FINAL text for G2, so this needs the author's sign-off
either way). Preconditions to state in the golden header: the sweep runs
on EVERY provisioned node like F29's container sweep, and each command's
"nothing to undo" exit is benign while a real failure still fails closed.

**NEXT-8 — F54 (increment 4).** `analyse.py` must exclude-and-list on a
missing `environment` or `duration_secs` instead of treating absence as
permission. Verify against the committed M0 tree: today 2 included /
0 excluded, must become 0 included / 2 excluded.

**NEXT-1 — G2 golden read-through (HUMAN, the author).** Read all seven
goldens in `harness/src/test/resources/goldens/` against their header
checklists. Deliverable: a signed-off line in this file. Blocks everything
billed. No code.

**NEXT-2 — P3.4 canary (after G2 + P3.5 price check).** Follow
PER_ALGORITHM_TEST_GUIDE §8 verbatim. Expected code fallout: none, but
budget for wording drift in tc/iptables/docker stderr on ubuntu-24.04 —
each surfaces as a named fail-closed error; fix = adjust the one probe
string + its golden, TDD.

**NEXT-3 — M3.3-full matrix runner. DONE same session (batch 2, TDD).**
Design refinement over the original plan, per EXECUTION_AND_COST_MODEL:
the execution unit is ONE SYSTEM BLOCK, not a whole phase — a phase is
the operator running blocks in sequence, and sweep RATES are operator
INPUTS (25/50/75% of a prior sat block's measured saturation; a static
generator cannot know them). `campaign.MatrixRunner`: cells = scenarios
× rates × conflicts × reps with the M0-tree runId convention
(`rate<R>r<NN>` / `satr<NN>` — rate is not a path segment, so it lives
in the runId, collision-free); seeded shuffle within the block (order
itself reproducible); resume = manifest `"status": "complete"` skip;
failure → `campaign-log.jsonl` line + CONTINUE; `--dry-run` preflight.
CLI: `campaign-run --system … --scenarios … --rates … [--conflicts …]
[--reps 5] [--seed …] [--dry-run]`; failover distributions = their own
block (`--scenarios leader_kill --reps 30`). 5 tests + a `--dry-run`
parser pin.

**NEXT-4 — HotStuff fault scenarios (preregister BEFORE implementing).**
Decisions an implementing LLM must put to the author first: (a) target =
replica 0 (rotating leader — same doc rule as CometBFT)? (b) SUMMARY
`faults` field semantics for kill scenarios (logs.py's Faults line feeds
committee_size = nodes + faults — a killed node still wrote logs up to the
kill; decide whether its log stays in the analyzer input); (c) is a
mid-run kill even meaningful for the upstream client (it may exit on lost
connections — probe first, locally, with the formation test's harness).
Then: golden block for the fault sequence + analyzer handling + runner
unlock, TDD.

**NEXT-4b — HotStuff warmup asymmetry. DONE 2026-07-18 (TDD).** Resolved
the honest gap without losing the paper anchor: `HotStuffLogAnalyzer`
gained an optional `warmupSecs` window applying logs.py's OWN formulas to
only the batches committed after (client start + warmup) — the same
warmup discard every other system gets (methodology §1). `warmupSecs=0`
(the 3-arg overload) reproduces logs.py EXACTLY, so the full-run,
paper-comparable number stays recomputable from the always-saved raw
logs; `RemoteRunner` passes `spec.warmupSecs()` so `summary.txt` is the
post-warmup, cross-system-comparable number. Not a new deviation — it is
the standard warmup discard, applied to logs.py's formulas. TDD:
hand-computed windowed fixture (warmup=4 drops the first batch → 50 tx/s,
its sample excluded from e2e latency), warmup=0 byte-identical to
whole-run, over-long warmup fails closed. The remaining HotStuff caveats
(D9 hardware seam, log-derived/no-server-metrics) stand and are in §7.

**NEXT-5 — P4 remainder.** PARTLY DONE (batch 3, 2026-07-18): dashboards
(P4.4) shipped — campaign-overview + per-algorithm (etcd, kafka [both
modes], cometbft, paxi+hotstuff), each carrying an embedded reading guide
(baseline expectation, fault signature, false-positive catalogue).
Collection + offline replay shipped
(`scripts/collect_block.sh` → one dated dir with results + Prometheus
snapshot, count-verified pre-destroy; `observability/offline/` compose
replays the snapshot against the SAME provisioned dashboards). analyse.py
foundation shipped (`analysis/`, F15 successor: per-cell median/IQR + mean
bootstrap CI, per-run percentile SPREADS not averages, environment/status
exclusion listed not silent, `--selftest` green).
**2026-07-21 additions (sixth review): ValidityChecker (M5.5-core) DONE
and hardened** — six gates + empty-series-fails + loadgen-steal into
validity.json, per-system gate-3 witnesses, hotstuff-aware, the
consulted-metrics↔export_queries contract test; it is a LIBRARY — the
M5.4 integration (and any CLI) wires it into the run flow. **P4.3 DONE
for Kafka JMX** (KafkaJmxAgentTest: real broker + pinned agent 1.0.1 +
in-repo rules file serve the two export-query names; ZK znode_count was
probed 2026-07-17). **M5.2 exporters wired in the providers/goldens**
(etcd :2381, cometbft :26660, kafka :7071 — F46; goldens FINAL for G2).
STILL OPEN: **PrometheusExporter** (M5.4 — runbook §5 query_range →
per-run metrics/*.csv, then call ValidityChecker.check per run), the
harness self-metrics on :9400 (micrometer, M5.3 — unlocks the
window_headroom gate), **docker-events audit** (the open half of P4.5 —
unlocks container_restarts and the paxi/hotstuff gate-3 witness), and
analyse.py's growth to pooled histograms + Holm + the 8 figures (M6.4).
No new decisions needed on any.

**NEXT-6 — observability/analysis polish (batch 3, DONE).** See
`OBSERVABILITY_AND_EXPECTATIONS.md` (per-algorithm preregistered baselines
with corpus anchors, dashboard reading guide, false-positive catalogue,
cleanup checklist) and `docs/examples/` (one real tiny laptop run + two
labelled synthetic shapes, field-by-field). These are the novice-facing
"what am I looking at / is this right" layer the author asked for.

(The section below is the previous session's text, kept for history.)

**P3.3d-kafka_zk STEP 2 — the remote golden + provider KAFKA_ZK branch.**
STEP 1 IS DONE (2026-07-17, 18.7 s green): `KafkaZkColocatedFormationTest`
proved the D10 colocated shape BY EXECUTION, built on two PROBED facts —
(a) **the apache/kafka image's entrypoint REFUSES ZK mode** ("Formatting
is only supported for clusters in KRaft mode"), but the SAME digest-
pinned image carries full ZK-mode binaries, so the recipe bypasses the
entrypoint: printf a server.properties, run `kafka-server-start.sh` —
which is exactly what keeps F6 an identical-binaries comparison; ZK mode
logs "[KafkaServer id=N] started (kafka.server.KafkaServer)" (NOT
KafkaRaftServer — the wait/gate line differs from KRaft); (b) the
zookeeper:3.9 image (digest-pinned, new `ZOOKEEPER_IMAGE` constant)
forms the ensemble from ZOO_MY_ID + ZOO_SERVERS and enables the
PrometheusMetricsProvider on :7000 via ZOO_CFG_EXTRA — verified serving
`znode_count` (P4.3's metric-name source). Verified end-to-end: 3
brokers registered via the ensemble, acks=all committed under
min.insync.replicas=2, Isr=3. Step 2: the golden — per VM TWO
containers (thesis-zk<i> + thesis-k<i>), private-IP ZK connect strings
and advertised listeners, the printf'd server.properties
golden-reviewable, gates = ZK up, broker "started (kafka.server.
KafkaServer)" log grep, api-versions count == N — then the provider
branch to match verbatim. **STEP 2 ALSO DONE same day (golden-file
TDD): `kafka_zk-size3-start-stop.txt` written FIRST — per VM TWO
containers (thesis-zk<i> ensemble + thesis-k<i> broker), private-IP
ZOO_SERVERS/zookeeper.connect/advertised listeners, ZK env values
single-quoted (spaces/semicolons), the broker's server.properties
printf'd INSIDE its start script (byte-for-byte reviewable), gates =
:7000/metrics curl per ZK (doubles as the scrape-target proof), the
ZK-MODE started line per broker (a wrong-mode broker cannot pass),
api-versions count == 3; teardown brokers FIRST then the ensemble (an
aux-container list in stop(), order pinned by test); size≠3 refused
(D10). Matched verbatim; 2 new tests; the provider now serves SIX of
seven systems — only HOTSTUFF fails closed.**
**P3.3d-hotstuff INCREMENT 1 (image + real CLI contract) DONE
2026-07-17:** `infra/hotstuff/Dockerfile` — asonnino/hotstuff pinned at
source commit dc01ac8626a64342f6a76ae6f8914535dd090bdd; **upstream ships
NO Cargo.lock**, so deps float within semver — the recorded image id
(`hotstuff:dc01ac8` = 8501e107d4bf, 93.5 MB) is the real reproducibility
anchor. Two build facts measured red first and baked into the
Dockerfile: rust ≥ 1.85 (the floating tree resolves zeroize_derive 1.5.0
→ edition2024) and clang/libclang+g++/make (the store crate compiles
RocksDB via bindgen). CLI contract SOURCE-VERIFIED + probed live:
`node keys --filename F` → single-line-able {name, secret} base64 JSON
(printf-distributable — the CometBFT file pattern); `node run --keys F
--committee F [--parameters F] --store PATH` with `-vv` (the log level
whose benchmark-feature lines feed logs.py's SUMMARY → HotStuffSummary);
`client <ADDR> --timeout INT --size INT --rate INT --nodes [ADDR…]`
(waits for every --nodes address before starting; the SUT's OWN load
generator — the documented boundary). Committee JSON =
{consensus:{authorities:{<pubkey>:{stake,address}},epoch},
mempool:{authorities:{<pubkey>:{stake,transactions_address,
mempool_address}},epoch}} — THREE ports per node (consensus,
transactions=client target, mempool; fab's LocalCommittee uses
port+i/+size/+2*size). Parameters JSON fields: consensus.timeout_delay/
sync_retry_delay; mempool.gc_depth/sync_retry_delay/sync_retry_nodes/
batch_size/max_batch_delay (all ints). Next hotstuff increment: 4-node
formation verified by execution (committee from generated keys, one
container per node, a client burst visible in the SUMMARY-feeding
logs), THEN the remote golden + provider branch. After that: the G2
read-through of ALL goldens, P3.4 canary.

(The section below is the step-1 text, kept for history.)

**P3.3d-cometbft STEP 2 — the remote golden + provider branch.** Step 1
is DONE (2026-07-17): `CometBftMultiValidatorFormationTest` proved the
DISTRIBUTION-shaped recipe BY EXECUTION in 7.9 s — `cometbft testnet` as
the one-shot keygen; each node assembled from FOUR small JSONs (genesis,
priv_validator_key, node_key, data/priv_validator_state — init's FilePV
loader REQUIRES the state file once the key is pre-placed); `cometbft
init` keeps pre-placed files and fills default config; peers via the CLI
flag `--p2p.persistent_peers=<id@host:26656>` excluding self (ids from
`CMTHOME=<dir> cometbft show-node-id` — **the --home flag is IGNORED,
CMTHOME wins, P2.3's fact reconfirmed**); sed for
max_subscription_clients 100→2000 AND addr_book_strict=false (RFC1918 on
both the local net and the campaign's 10.0.0.0/24). Verified: n_peers=3,
a tx committed through 3-of-4 BFT precommits (both codes 0), every
replica reached the committed height. **STEP 2 ALSO DONE same day
(golden-file TDD): `tendermint-size4-start-stop.txt` written FIRST —
keygen + show-node-id as `docker run --rm` one-shots on node1 (with
`-e CMTHOME`, the probed fact), each cat'ed artifact COMPACTED to
single-line JSON before its printf (the single-quote-safety proof AND
the one-golden-line guarantee; fixture placeholders stand in for the
per-run random material — the golden pins COMMAND STRUCTURE), fresh-
state `rm -rf` first (a stale data/ dir would resurrect a previous
chain), peers exclude self on private IPs, both seds in the start
script, readiness = /health per node then /status on node1 until
latest_block_height ≥ 1 (a height only >2/3 of validators can produce —
the quorum gate; pinned by a stuck-at-0 fail-closed test). size≠4
refused (D9: n=3f+1). Matched verbatim; 3 new tests.** After that:
KAFKA_ZK (D10 colocated ZK+broker, same pattern), HotStuff, the G2 human
read-through of ALL goldens, then the P3.4 canary.

(The section below is the pre-P3.3d text, kept for history.)

**P2.5 — HotStuff SUMMARY parser (fixture-tested).** The exact format is
captured from asonnino/hotstuff `benchmark/benchmark/logs.py` (result()
method, fetched 2026-07-16): a `SUMMARY:` block with `+ CONFIG:` (Faults,
Committee size, Input rate, Transaction size, Execution time, consensus/
mempool tunables) and `+ RESULTS:` (Consensus TPS/BPS/latency, End-to-end
TPS/BPS/latency) — **integers carry thousands separators** ("7,812 tx/s")
and latencies are in ms. End-to-end TPS/latency are the client-observed
primaries (our metric class); Consensus TPS/latency are internal. Build
the fixture verbatim from that format; the parser is a small class the
campaign runner feeds from the client log (P4.5 collects it — HotStuff's
logs ARE its metrics). Tx size MUST be 1024 B on the campaign (the
cross-system value-size contract).

(The section below is the pre-P2.4 text, kept for history.)

**P2.4 — Paxi (Paxos/EPaxos) substrate + leader detection.** Prerequisite
discovered: Paxi publishes NO Docker image — build one from source
(github.com/ailidani/paxi @ 6823d0b, Go; `infra/paxi/Dockerfile` exists,
source-commit-pinned per D2-adapted). Then: extend `LocalDockerProvider`
(3-node Paxi, generated config.json, probe-write quorum gate — paxi elects
its first leader lazily), verify the PaxiDriver write path against the
real server (Atoi contract already source-verified 2026-07-16, F22), TDD
**`Ballot` response-header parsing** for `currentLeaderIndex()` (fixture +
live smoke — `/state` does not exist, F22) and resolve F24 (PAXOS write
path pins one endpoint; node-ordered list kept for identity), and exercise
the D7 conflict sweep end-to-end (functional on the laptop: c>0 run
completes clean with the c-path/manifest identity). Then P2.5 (HotStuff
SUMMARY parser, fixture-tested). Implement one increment at a time (test →
code → run → checkpoint).
