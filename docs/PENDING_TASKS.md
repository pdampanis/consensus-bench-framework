# Pending Tasks — Prioritized Backlog + Status Ledger

**Last updated: 2026-07-15.** Companion to `IMPLEMENTATION_PLAN.md`
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

**GATE G1 STATUS (2026-07-16): evidence complete in-suite; formal
sign-off is the author's.** All five M2 driver acceptances exist as
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
  Notes: evaluate **Pumba** (runs on each VM, Docker-native kill/netem — no
  data-path hop) as the fault executor vs hand-rolled `tc`/`iptables`; golden
  tests bind either way. Fault semantics to preregister while writing goldens
  (review F13): PARTITION isolates the *leader* (not node 0), PACKET_LOSS %
  becomes a parameter, DOUBLE_KILL on BFT n=4 is an intentional
  liveness-loss demonstration (2 > f=1) and is documented as such.
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
| F20 | `NodeHandle.privateIp` carries the Docker network ALIAS ("etcd1"), not an IP (LocalDockerProvider.java:93) — semantic trap for RemoteSshProvider | P3.3 | open — rename or fill with a real IP when the remote provider lands |
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
| F26 | **Stock paxi cannot recover from a hard leader kill** (source-verified @ 6823d0b): a follower that knows a ballot always FORWARDS client requests to that leader (paxos/replica.go handleRequest) — there is no failure detector and no re-election trigger. Refined by deeper source read: on an ESTABLISHED-but-broken connection the transport writer just logs and drops (transport.go Dial goroutine), so a hard leader kill produces a silent WEDGE — forwarded writes get no reply and fail only at our F18 5 s bound, indefinitely; the **panic** path (socket.go:98-100, dial retry 100×50 ms) fires only when no prior connection existed. Consequently the LEADER survives follower death (its P2a to the dead peer is logged-and-dropped; 2/3 quorum commits — empirically confirmed by PaxiDriverTest's follower-kill). Paxi's designed fault primitive is `/crash?t=` (socket pause, process alive). `-ephemeral_leader=true` enables follower self-election but changes baseline semantics. | P3.3 | open — **preregister the paxi leader_kill design** before the goldens: adaptive-mode + documented wedge as the honest "no failure detector" result, vs ephemeral_leader mode, vs paxi's own Crash(t); verify empirically whichever is chosen. Expected-vs-observed material for the thesis (implementation property, not protocol property). |

---

## Immediate next increment (proposed)

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
