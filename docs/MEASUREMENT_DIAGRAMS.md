# Measurement Architecture — Engine Core and Per-System Diagrams

One diagram set for the engine core and one per consensus system: topology,
commit path, what a completed `write()` *means*, how it is tested, how it is
measured, where the numbers land, and why they are reliable. Written from the
live code (2026-07-15, suite 66 tests green) and the corpus papers the design
mirrors (Paxi SIGMOD'19 workload model, HotStuff PODC'19 rate method, Tene on
coordinated omission, Hoefler & Belli SC'15 reporting rules). Authority order
unchanged: **live code > plan + methodology > this file**.

Status marks: ✅ built and test-verified · 🔶 designed, not built (task id).

---

## 1. The engine core — one load model for every system ✅

The deepest methodological fix of this thesis: **every system is driven by
the same open-loop generator through one SPI**; only the transport differs
per driver. The retired probes measured each system with a different client
stack (6 blocking Python threads ≠ pipelined perf-test ≠ rate-limited fab
client) — the load model varied more than the protocols did.

### 1.1 Inputs and outputs

```
INPUT  Config(durationSecs, warmupSecs, targetRatePerSec, maxInFlight,
              valueSizeBytes, conflictRatio)     one ConsensusDriver
                                                 one LatencyRecorder
                                                 EventLog (fault runs only)
OUTPUT Result(committedPerSecond[], latencies, errors, firstError, events)
       → CsvResultsWriter → results tree (§2)
```

### 1.2 The main loop (WorkloadEngine.run)

```
            OPEN-LOOP MODE (rate R > 0)                . SATURATION (R <= 0)
                                                       .
  intended send times fixed BEFORE the run:            .  no schedule: issue
  t0, t0+1/R, t0+2/R, ...  (the "schedule")            .  as fast as the
            │                                          .  window allows
            ▼                                          .  (closed loop, deep
 ┌─────────────────────────┐   window full?            .  concurrency — Paxi's
 │ wait until next intended│   (Semaphore              .  saturation-search
 │ time (parkNanos)        │    maxInFlight)           .  method)
 └───────────┬─────────────┘        │
             ▼                      ▼
      ┌─────────────────────────────────────┐
      │ inFlight.acquire()  ← BOUNDED WINDOW │  backpressure, never unbounded
      └───────────┬─────────────────────────┘
                  ▼
      ┌───────────────────────────────┐    keyFor(c):
      │ driver.write(keyId, value)    │◄── P(c)   → key 0 (conflict key,
      │ async, returns CompletionStage│    P(1−c) → uniform [1,1000)
      └───────────┬───────────────────┘    K=1000 REUSED keys (Paxi Table 3)
                  │ completes ONLY on consensus commitment,
                  │ exceptionally on failure/timeout (≤5 s, F18 contract)
                  ▼ (driver's IO thread)
      ┌────────────────────────────────────────────────────┐
      │ whenComplete(v, err):                              │
      │   events.append(now, ok)          ← fault runs     │
      │   err? → errors++, firstError CAS, release, return │
      │   latency = completed − INTENDED   ← CO correction │
      │   recorder.record(latency, warm?)  ← HdrHistogram  │
      │   perSecondCommits[second]++       ← real series   │
      │   finally: inFlight.release()      ← release LAST: │
      │            the drain barrier must not see a permit │
      │            before the sample is recorded           │
      └────────────────────────────────────────────────────┘
  ...loop until durationSecs, then:
      inFlight.acquire(maxInFlight)   ← DRAIN BARRIER: run() returns only
                                        when EVERY issued op is completed
                                        AND recorded (no lost samples)
```

### 1.3 Case: a stall (leader election, GC pause) — coordinated omission

The classic error: a blocked closed-loop client stops *sampling* exactly
while the system stalls — the tail a failover study exists to measure never
enters the data. The engine charges latency against the **intended** time:

```
 schedule   │ op₁   op₂   op₃   op₄   op₅   op₆   op₇   op₈    (fixed, 1/R apart)
 (intended) │  ▼     ▼     ▼     ▼     ▼     ▼     ▼     ▼
 time ──────┼──┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬──────────────►
            │  │     │     │   ╔═════ STALL (election) ═════╗
 completes  │  ▼     ▼     ▼   ║                            ║ ▼▼▼▼  (burst)
            │ ok    ok    ok   ╚════════════════════════════╝
 charged    │ 2ms  2ms   2ms   op₄: stallEnd−t₄  ← each op scheduled DURING
 latency    │                  op₅: stallEnd−t₅    the stall is charged its
            │                  ...  (queueing delay: the tail spreads up to
            │                       the full stall length — nothing hidden)
```

Pinned by `stallLatencyIsChargedAgainstIntendedTime` (a scripted 1 s stall
must charge ~100 scheduled ops their queueing delay: p50 fast, p90 ≥ 300 ms,
max ≈ full stall — a CO-blind measurement would show ONE slow op).

### 1.4 Case: fault run — failover measurement (EventLog) ✅

```
                 one clock domain: System.nanoTime in the harness JVM
 workload   commits ██████████████░░░░░░░░ ERRORS ░░░░░░██████████████
 (EventLog)                        ▲                    ▲
                                   │                    │
 injector thread ──── kill ────────┤                    │
                 faultInjectedNow()│                    │first successful
                                   │◄── failoverMillis ►│commit AT/AFTER mark
                                   │                    │
 manifest.json      fault_injected_at_ms          failover_ms
```

Events ride the main workload (no separate probe lane — a second traffic
class would perturb what it measures). Preallocated `long[]`, lock-free slot
claim; overflow **counts** dropped events (validity evidence), never crashes.
No recovery ⇒ empty Optional — an absent number, never a fabricated one.
Resolution: per completion (bounded by op rate), ≥30 trials → ECDF (F4).

### 1.5 Case: dead / quorum-less cluster — fail closed

```
 write() ──► completes exceptionally (driver bound ≤5 s: jetcd orTimeout,
             HTTP request timeout, Kafka delivery.timeout — F18 contract)
        ──► errors++, firstError kept, ZERO latency samples, ZERO commits
        ──► manifest: status=failed when ops==0 or error_rate>0.5
```

The drain barrier always resolves because completion is bounded — one
unbounded op would hang the whole fault run (measured before the fix: a
quorum-lost jetcd put was bounded only by etcd's ~7 s server-side grace).

### 1.6 Why the engine's numbers are reliable (invariant → test)

| Invariant | Pinned by |
|---|---|
| Open-loop rate adherence (±few %) | `openLoopAchievesTargetRate` |
| No sample lost at drain | `drainAccountsForEveryIssuedOp` |
| Buckets == histogram count | `perSecondBucketsSumToRecordedCommits` |
| CO correction (stall → tail) | `stallLatencyIsChargedAgainstIntendedTime` |
| K=1000 reused keyspace | `keySpaceIsBoundedAndReused` (the old unique-key bug measured 778k distinct keys / 778k ops) |
| Conflict fraction == configured c | `conflictFractionMatchesConfigured` (±0.015 at n≥10⁴ ≈ 5σ) |
| Key 0 exclusive to conflict traffic | `zeroConflictNeverTouchesTheConflictKey` |
| Whole-instrument self-consistency | `littlesLawSelfConsistency` (saturation: throughput × mean ≈ window — three independently measured quantities) |
| Dead cluster ⇒ errors, not data | `deadClusterYieldsErrorsNotFabricatedData` |
| Failover gap exact on one clock | `failoverGapIsRecoveredFromAScriptedStall` (±150 ms vs scripted truth) |
| Error causes never swallowed | `firstErrorCauseIsSurfacedNotSwallowed` |
| Warmup outside every statistic | `warmupSamplesStayOutOfEveryStatistic` |

---

## 2. Where the numbers land — and how they become analysis ✅ (writer) / 🔶 (exporter, validity)

```
                    loadgen (or laptop for dev runs)
 WorkloadEngine.Result ──► CsvResultsWriter ──► <root>/<system>/<scenario>/size<N>[/c<pct>]/<runId>/
                                                 ├─ throughput.csv   t,ops — EVERY second, zeros kept
                                                 │                   (a zero second is stall evidence)
                                                 ├─ latency.csv      avg(true mean)/p50/p95/p99/p99.9/max
                                                 ├─ latency.hlog     FULL HdrHistogram (HistogramLogWriter
                                                 │                   v1.3) — the POOLING input: per-run
                                                 │                   histograms are MERGED, percentiles
                                                 │                   are never averaged (methodology §3)
                                                 ├─ manifest.json    v2: params, environment(local|hetzner),
                                                 │                   image digest, harness_version,
                                                 │                   config_hash (12-hex SHA-256 over every
                                                 │                   cell-defining input), error_rate,
                                                 │                   fault_injected_at_ms, failover_ms,
                                                 │                   honest status
                                                 ├─ metrics/*.csv 🔶 PrometheusExporter (P4.2): query_range
                                                 │                   over export_queries.txt, ±15 s pad
                                                 └─ validity.json 🔶 ValidityChecker (P4.1): six gates,
                                                                     empty-series-fails meta-rule
 rsync → laptop (count-verified BEFORE destroy) → analyse.py v2 🔶 (pooled
 histograms, bootstrap CIs, Holm correction, ECDFs, validity filtering) → 8 figures
```

Reliability chain: the manifest makes each cell individually reproducible
(same config_hash ⇒ same experiment); `environment=local` runs are never
thesis data; the hlog makes distribution pooling possible instead of the
percentile-averaging most reports get wrong; validity gates reject rather
than average-in broken runs (no silent outlier removal, ever).

---

## 3. Topologies — what the harness drives

```
 LOCAL (dev substrate, ✅):  one Docker network per cluster, digest-pinned
   images, /health-gated parallel start, Ryuk teardown, thesis-* names
   ┌─────────────────────────── laptop ───────────────────────────┐
   │  harness JVM ──► mapped ports ──► [etcd1 etcd2 etcd3] or [k1] │
   └───────────────────────────────────────────────────────────────┘
   Functional evidence ONLY (environment=local) — never thesis data.

 CAMPAIGN (Hetzner, 🔶 P3, gated by G2 goldens + canary): one consensus
   node per VM, private net 10.0.0.0/24, spread placement group,
   loadgen ccx13 (D11) + obs cpx21; phases A/B/C per CAMPAIGN_RUNBOOK §2.
   One system under test at a time, serial blocks (EXECUTION_AND_COST_MODEL).
```

---

## 4. Per-system: commit path, driver, tests, caveats

Every system gets the same schedule, the same K=1000 keyId stream, the same
window semantics, the same results contract. What differs — and what each
diagram shows — is **what must happen inside the system before the driver's
stage completes**.

### 4.1 etcd (Raft) — ✅ production driver (P2.1 + F18)

```
 TOPOLOGY (size 3)          COMMIT PATH (one write)
 ┌──────┐ peers ┌──────┐    client ──put──► LEADER
 │etcd1 │◄─────►│etcd2 │                      │ append to own log + WAL fsync
 └──┬───┘ :2380 └──┬───┘                      ├─ AppendEntries ─► follower A ─ fsync ─┐
    │   ┌──────┐   │                          └─ AppendEntries ─► follower B ─ fsync ─┤
    └──►│etcd3 │◄──┘                          majority (2/3) persisted ──► commit     │
        └──────┘                              apply to KV store ──► RESPONSE ◄────────┘
 clients :2379              write() completes = Raft-committed AND applied
```

- **Driver**: `EtcdDriver` — jetcd native async gRPC `put`, 5 s per-op
  deadline (`orTimeout`); `EtcdHttpDriver` (v3 JSON gateway, pure JDK) is
  the fallback and the same-cluster cross-check — both write the SAME keys
  (`bench/k<id>`, pinned identical by test).
- **Leader detection**: every endpoint's maintenance status; the member
  whose own id equals the leader id it reports; endpoint order = node order.
  Cross-validated against the independent HTTP stack (two client stacks
  agreeing — not circular).
- **Tested**: committed gRPC write; leader kill → NEW leader ≤30 s and
  writes keep committing on 2/3; kill 2/3 → writes FAIL within the deadline
  (never fabricate, never hang); zero leftover containers.
- **Reliable because**: response ⇒ majority-fsynced Raft commit (the PUT
  carries raft_term); one keyspace across both drivers enables G3
  cross-checking; election visible in `etcd_server_leader_changes_seen_total`
  (validity gate 3 corroboration).

### 4.2 KRaft (Kafka, combined controller+broker) — ✅ driver (P2.2), single-node substrate

```
 CONTROL PLANE (KRaft quorum)          DATA PLANE (what we measure)
 ┌────────────────────────────┐        producer ──record(key,value)──► partition LEADER
 │ controller quorum: Raft    │                                          │ append to local log
 │ (metadata, leader election)│        follower brokers FETCH (pull) ◄───┤
 │ replaces ZooKeeper —       │        ISR replica A appended ───────────┤
 │ same nodes, combined mode  │        ISR replica B appended ───────────┤
 └────────────────────────────┘        high-watermark advances past offset
                                       (acks=all + min.insync.replicas=2:
                                        ≥2 of 3 ISR have the record)
                                       ──► send CALLBACK fires = commit
```

- **Driver**: `KafkaDriver` — real kafka-clients producer, `acks=all`,
  commit timed in the send callback; `delivery.timeout.ms=5000` (F18);
  metadata warmed at `connect()` so `send()` never blocks the issue loop.
  Key = UTF-8 integer string → murmur2 → same keyId, same partition —
  contention deterministic. Topic `bench`, 6 partitions (the retired
  probe's shape, kept for comparability), RF=cluster size, min.insync=2
  when RF=3.
- **Leader**: partition-0 leader mapped to node index by advertised
  host:port; an unmappable leader THROWS (never kill the wrong node).
  P3.3 must preregister which leader "leader_kill" means on a 3-broker
  cluster (partition leaders spread).
- **Tested**: committed write; leader detection; dead broker → write fails
  <8 s asynchronously (send() does not block — cached metadata). G1 flaw-B
  regression: saturation within the order-of-magnitude band of
  `kafka-producer-perf-test` on the same broker (measured 0.95x final run;
  the symmetric 15% comparison is M6.1's, on the cluster).
- **Caveats**: durability is replication-first (OS-level flush policy, not
  per-record fsync) — part of D6's documented asymmetry discussion; local
  substrate is single-node (multi-broker KRaft lands with the campaign
  provider).

### 4.3 Kafka + ZooKeeper (ZAB) — 🔶 substrate (D10), driver ✅ (same KafkaDriver)

```
 THE F6 COMPARISON: same 3 VMs, same data plane — only the coordination
 machinery differs.                     ┌──────────── VM i ────────────┐
 KRaft:    controller quorum in-process │  broker container            │
 Kafka+ZK: ZK ensemble colocated (D10)  │  zk container (ZAB, :7000)   │
                                        └──────────────────────────────┘
 DATA PLANE: identical to 4.2 (ISR replication, acks=all)
 CONTROL PLANE: controller elected via ZK ephemeral node (session timeout
 ~18 s dominates failover); metadata in ZK, ZAB-replicated:
   leader proposes ─► quorum ACK ─► COMMIT broadcast (primary-backup ZAB)
```

- **Why colocated**: ZK on separate VMs would give Kafka+ZK more hardware
  than KRaft and un-mirror the comparison; the symmetric contention is
  stated in the figure caption (D10).
- **Measured difference vs KRaft (expected, preregistered)**: failover
  dominated by ZK session timeout vs KRaft's Raft election; ZAB commit path
  visible via ZK's :7000 Prometheus metrics (names verified at P4.3).
- **Driver**: the SAME `KafkaDriver` (constructor takes KAFKA_ZK) — the
  data path is identical by design; that identity IS the point of F6.

### 4.4 CometBFT / Tendermint — ✅ driver + single-node substrate (P2.3)

```
 TOPOLOGY: 4 validators (n=3f+1, f=1)   COMMIT PATH (one height, happy path)
                                        proposer(round-robin, known in advance)
 client ──broadcast_tx_commit──► node    │ PROPOSE block (batches mempool txs)
        (returns only on block           ├─► PREVOTE   — wait >2/3 validators
         inclusion + DeliverTx)          ├─► PRECOMMIT — wait >2/3 validators
                                         └─► COMMIT: block finalized, txs applied
 latency = mempool wait + BLOCK INTERVAL (~1 s) + 3 voting phases
                          ^^^^^^^^^^^^^^ structurally inside every sample —
                          compared in absolute terms ONLY with that caveat
```

- **Driver**: pooled async `broadcast_tx_commit` (java.net.http), 5 s
  bounded completion. Probed facts encoded (v0.38.17): HTTP 200 ≠ commit —
  success = no JSON-RPC error AND check_tx.code==0 AND tx_result.code==0;
  txs carry an ASCII-hex nonce (mempool rejects duplicate bytes; kvstore's
  CheckTx demands exactly-two-'='-parts); the provider raises
  `rpc.max_subscription_clients` 100→2000 (each concurrent caller holds a
  subscription — measured 99/250 at the default). **Flaw-A acceptance
  green: 602 tx/s sustained (100× the retired 6-thread probe's ~6 tx/s
  ceiling), p50 = 1.09 s ≈ the block interval.**
- **Reliable because**: a completed stage ⇒ ≥2/3 precommits ⇒ full BFT
  cycle for that tx (codes checked, never just HTTP status);
  height/rounds/block-interval scraped at :26660 corroborate faults
  (round jumps on proposer kill).
- **Caveats**: O(n²) message complexity (vote gossip) — the preregistered
  expectation vs HotStuff's O(n); block-interval wait dominates latency.

### 4.5 Paxos (Paxi) — ✅ driver + leader detection (P2.4b), local 3-node substrate (P2.4a)

```
 TOPOLOGY: 3 replicas (Paxi framework)  COMMIT PATH (stable leader)
 client ──HTTP PUT /<intKey>──► paxi1    │ (Phase 1 ran ONCE at election)
                                         ├─ Phase 2a ACCEPT ─► replica B ─┐
   if paxi1 not leader: forwards         └─ Phase 2a ACCEPT ─► replica C ─┤
   internally (hop inside the sample,    majority (incl. self) accepted ──┤
   documented)                           ──► commit ──► HTTP 200 ◄────────┘
 write() completes = majority-quorum committed (IN-MEMORY store — D6)
```

- **Driver**: `PaxiDriver` — pooled async java.net.http, integer key in the
  URL path (Paxi Atoi-parses it; the typed int contract exists because a
  byte-blob key would have failed every op). F24 pinned by test: PAXOS
  writes pin to ONE endpoint (single client entry; forwarding internal —
  in practice the provider's probe-write gate elects node 0, so the entry
  IS the leader); the endpoint list stays node-ordered for index identity.
- **Leader detection**: the `Ballot` response header of a committed write
  (F22: paxi has no `/state`; format `"<n>.<zone>.<node>"`, ID part = the
  leader — authoritative from ANY entry because a forwarding follower
  relays the leader's reply). Fail-loud on multi-zone/malformed ballots.
- **Tested**: 4 unit contracts (ballot parsing incl. fail-loud shapes,
  endpoint strategy) + acceptance vs real Docker (paxi:6823d0b built from
  pinned source): committed write; leader corroborated through an
  independent stack AND entry; follower-kill → commits continue on 2/3.
  Paxi's own benchmarker returns at G3 as the cross-validation oracle (D4).
- **Fault caveat (F26, source-verified)**: stock paxi has NO failure
  detector — followers forward to the last-known leader forever, and a
  hard leader kill wedges writes (silent drop at the transport; our F18
  5 s bound turns that into honest errors). The paxi leader_kill design is
  preregistered at P3.3 (adaptive-wedge vs ephemeral_leader vs Crash(t)).
- **Caveats**: in-memory commit — no disk in the path (the Paxi authors
  compared against etcd by DISABLING its persistence; we refuse and
  document instead). Absolute numbers are compared with that asymmetry
  stated every time.

### 4.6 EPaxos (Paxi) — ✅ write path (round-robin), 🔶 exercised sweep P2.4

```
 LEADERLESS: every replica is a command leader for the keys it receives —
 the driver round-robins ALL endpoints (the retired probe's single-endpoint
 traffic could never exercise this).

 FAST PATH (no conflict):                SLOW PATH (conflict detected):
 client ─► replica X                     client ─► replica X
   │ PreAccept ─► fast quorum            │ PreAccept: dependency mismatch
   │ (N=3,f=1 → 2 incl. self)            │ ─► Phase 2 Accept ─► majority
   └─► commit in ONE round trip          └─► commit in TWO round trips
 deps recorded per command; interference = same key touched concurrently

 WHY THE D7 KNOB EXISTS: with c=0 (and the old unique-key bug) EPaxos
 measures identically to Paxos — the fast path never has to arbitrate.
 Routing fraction c to the shared key 0 forces interference ≈ c, so the
 fast→slow degradation (the paper's core trade-off) becomes measurable:
   c ∈ {0, 2%, 10%}  (Paxi paper sweeps c; Charapko '21 to 10%;
                      EPaxos-Revisited controls conflict directly)
```

- **Reliable because**: the realized conflict fraction equals c BY
  CONSTRUCTION (key 0 is excluded from the uniform range — no (1−c)/K
  bias), pinned at 5σ tolerance; same engine, same schedule as Paxos, so
  the EPaxos−Paxos delta isolates the protocol difference.
- **Caveats**: same in-memory store as Paxos (D6); result path gains the
  `c<pct>` segment so conflict cells can never collide with baseline.

### 4.7 HotStuff (asonnino build) — 🔶 boundary P2.5

```
 TOPOLOGY: 4 replicas + rate-limited    CHAINED PIPELINE (one view per block):
 client (the fab benchmark client        view v:   block Bₖ   ─ QC ─┐
 IS the load source; the harness         view v+1: block Bₖ₊₁ ─ QC ─┤ leader rotates
 parses its SUMMARY output)              view v+2: block Bₖ₊₂ ─ QC ─┤ each view;
                                         commit rule: consecutive QC  │ O(n) votes
 paper = 3-chain (PODC'19);              chain finalizes the block ◄──┘ per view
 asonnino build = 2-chain variant        (responsiveness: leader waits for
                                          n−f actual votes, not a timeout)
```

- **Why a boundary, honestly**: no Prometheus metrics, mean-heavy client
  output — the SUMMARY parser (P2.5, fixture-tested) is the only metrics
  source, so per-block SUT logs are collected per P4.5 ("its logs ARE its
  metrics"), and every figure it appears in carries the caveat.
- **Config requirement**: tx size MUST be set to 1024 B (the old 512 B
  default breaks the cross-system value-size contract).
- **Caveats**: client caps input rate (saturation = raise `HS_RATE` until
  TPS plateaus); D9 hardware seam — BFT nodes are upsized (ccx23), so
  BFT-vs-CFT comparisons are within-family/directional only.

---

## 5. The gates that make any of this trustworthy

```
 G1 (drivers): Kafka + CometBFT acceptance = the regression tests for the
     two probe flaws that motivated the harness (flaw B: within band of
     perf-test — local order-of-magnitude ✅, 15% at M6.1; flaw A: >300 tx/s).
 G2 (before billing): SSH golden tests HUMAN-reviewed + <€0.10 canary —
     the gate v6 never had.
 G3 (before campaign): harness vs native tools (perf-test, etcd benchmark,
     Paxi benchmarker) on the REAL cluster — ≤15% or explained, deltas
     documented in the thesis.
```
