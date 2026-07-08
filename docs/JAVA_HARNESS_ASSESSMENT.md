# From Shell Scripts to a Benchmark Harness — Assessment & Design

## 1. Honest scrutiny of what we do now

The v6 post-mortem already established that shell orchestration failed for
*process* reasons (shipped unverified). But preparing this assessment forced a
harder look at the **measurement probes themselves**, and I found two flaws I
had previously signed off on as correct:

**Flaw A — CometBFT throughput is client-capped at ~6 tx/s.**
`cmt_bench.py` runs 6 blocking threads on `broadcast_tx_commit`, which returns
only after block inclusion (~1 s block interval). Ceiling: clients ÷ block
time ≈ 6 tx/s — while the PaxiBFT paper in your bundle measured Tendermint at
~1,750 tx/s in LAN using **90 clients**, and the simulation paper states it
"carefully select[s] both the client count and concurrent request rate to
fully saturate" the system. Our probe measures the client's thread count, not
the protocol. I earlier reviewed this probe as "Correct: broadcast_tx_commit
waits for consensus" — that is true for *latency semantics* and badly wrong
for *throughput methodology*.

**Flaw B — every HTTP probe pays a TCP handshake per operation.**
`urllib.request.urlopen` does not reuse connections. Every Paxi and CometBFT
latency sample includes connection setup; every throughput number is throttled
by it. jetcd/Kafka-based systems don't pay this, so the cross-system
comparison is biased by client stack, not consensus design.

**The structural problem beneath both:** each system is measured by a
*different client with different concurrency semantics* — kafka-producer-perf-test
pipelines thousands of in-flight records; the Python probes block on 6
threads; asonnino's HotStuff client is rate-limited open-loop. The load model
varies more between systems than the protocols do. That is the deepest reason
"library instead of scripts" is the right question: the fix is **one load
generator, five drivers**.

Secondary, real but smaller: bash-specific bug classes from v6 (string-derived
container names, `local` outside functions, quoting across SSH, `|| true`
swallowing failures) are exactly what a compiler eliminates. And
`python-etcd3`, which etcd_bench.py depends on, is effectively unmaintained;
jetcd is the actively maintained official client.

## 2. What academia and industry actually do

**Paxi (SIGMOD '19, in your bundle)** is the strongest precedent — it exists
*because* of this problem: "compare many different consensus and replication
protocols against each other under the same framework with the same
implementation conditions." Its benchmarker component (explicitly modeled on
YCSB) has a config-driven workload (duration, write ratio, concurrency,
uniform/normal/zipfian key distributions), stores **every individual request
latency** for later analysis, and finds saturation by raising concurrency
until throughput stops increasing. It even bakes in fault primitives
(Crash(t), Drop(i,j,t)) as client-library commands.

**HotStuff (PODC '19, in your bundle)** measures by "varying the operation
request rate until the system saturated" — rate-controlled open-loop clients,
throughput/latency curves per batch size, end-to-end from clients.

**Industry:** YCSB (Java) is the canonical KV-store benchmark and the pattern
Paxi copies; OpenMessaging Benchmark (Java, Linux Foundation) is the Kafka
world's equivalent; Jepsen is the fault-injection gold standard (correctness,
not performance — citable for nemesis methodology); **HdrHistogram** (Java) is
the standard latency recorder, built to avoid **coordinated omission** — the
measurement error where a stalled system stops being sampled by blocked
closed-loop clients, hiding exactly the stalls a failover study exists to
measure. Our current probes have this problem; an open-loop engine recording
against intended-start-time fixes it, which matters directly for the
leader_kill scenarios.

Nobody serious benchmarks five systems with five different client stacks.

## 3. Language: Java is the right call here — for specific reasons

1. **It is your strongest language** (11 years, Kafka ecosystem). The v6
   failure mode was unreviewable-by-you code shipped unverified. Code you can
   read, debug, and defend in the September examination is itself a
   reliability and defensibility strategy.
2. **The ecosystem is native:** kafka-clients (real producer with acks=all and
   callback timing — replaces the perf-test black box), jetcd (official,
   returns CompletableFuture — async for free), java.net.http (pooled async
   HTTP for CometBFT/Paxi), HdrHistogram, Testcontainers (typed Docker
   orchestration with wait strategies and guaranteed teardown — replaces
   compose + bash glue on single host), YCSB patterns to borrow and cite.
3. **Your visualizer backend is already Java** — the harness can share the
   domain model (BenchmarkRun) and feed it directly.
4. **The bug classes that killed v6 die at compile time**: typed
   SystemUnderTest enum carries real container names (no `${sys%%_*}1`);
   Scenario carries `mutatesCluster()` so the campaign runner *cannot* reuse a
   corrupted cluster; run identity includes clusterSize in the path so the
   scalability-collision bug is inexpressible.
5. **Thesis value:** "a unified Java benchmarking harness driving five
   consensus implementations through one async load model" is a citable
   methodological contribution mirroring Paxi's own argument — materially
   stronger than "shell scripts invoked five different tools."

Counterpoints, honestly: Python/Fabric (asonnino's choice) would be ~40% less
code; JVM client warmup means the measuring client itself needs the warmup
discard we already do; and Java does nothing for the *semantic* orchestration
errors of multi-VM SSH — the hardest part of v6 stays hard in any language.

## 4. Proposed architecture (skeleton compiled and attached)

~500 lines, 8 files, pure JDK 21, `javac` verified in this session:

- `core.SystemUnderTest`, `core.Scenario` — typed identities with container
  naming, cluster shape, and restart semantics built in.
- `driver.ConsensusDriver` — the one abstraction that matters: async
  `write(key,value) -> CompletionStage`, completing **only on consensus
  commitment**, plus first-class `currentLeaderIndex()` (porting the leader
  detection v6's shell layer dropped). One `PaxiDriver` implemented to prove
  the SPI on pure JDK.
- `core.WorkloadEngine` — open-loop rate scheduler with bounded in-flight
  window (coordinated-omission-corrected, intended-start-time latency) plus a
  saturation mode; real per-second commit buckets for every system (no more
  synthetic flat lines); warmup flagged, not discarded, so the results layer
  decides.
- `core.LatencyRecorder` — HdrHistogram contract (pure-JDK log-bucket stand-in
  here; swap internals when Maven is available, API unchanged).
- `topology.ClusterProvider` — SPI with `LocalDockerProvider`
  (Testcontainers) and `RemoteSshProvider` (sshj) planned; faults operate on
  typed `NodeHandle`s, never derived strings.
- `results.CsvResultsWriter` — writes the **identical CSV/manifest contract**
  so analyse.py and the visualizer run unchanged. The harness replaces how
  numbers are produced, never the reviewed analysis layer.

Remaining drivers are mechanical: Kafka (~120 lines, producer callback =
commit ack), etcd/jetcd (~80, natively async), CometBFT (~100, sendAsync on
broadcast_tx_commit with a 200-op window — this alone un-caps Flaw A).
HotStuff stays a documented boundary: docker-exec asonnino's harness and parse
SUMMARY; its client is already open-loop rate-limited, which the engine now
matches for everyone else — resolving the load-model inconsistency I flagged
in the first review.

## 5. Effort, phased, with a timeline gate

| Phase | Scope | Est. |
|---|---|---|
| 1 | Measurement core: engine + Kafka/etcd/CometBFT/Paxi drivers + results writer, driven against a manually-started local compose | 2 days |
| 2 | LocalDockerProvider (Testcontainers) + fault injection + campaign runner → full single-host campaign in Java | 1.5–2 days |
| 3 | RemoteSshProvider for Hetzner (the v6 danger zone) + dry-run harness that stubs SSH and asserts every remote command | 2–3 days |

Phase 3 is optional and gated: Phases 1–2 alone yield a methodologically
*superior* campaign to anything so far (uniform load model, real percentiles
everywhere, coordinated-omission-corrected failover latency) on hardware you
already have. Do not start Phase 3 unless the writing schedule provably
absorbs it — the exam is in September and August must belong to the document.

## 6. Honest review of this assessment itself

What I verified: both probe flaws (grep + arithmetic against the papers'
numbers), the papers' methodology quotes (project-knowledge searches this
session), and the skeleton (javac, zero warnings-as-errors config, 8/8 files).
What I did **not** verify: that jetcd/Testcontainers versions resolve cleanly
in *your* Maven environment (no Maven access in this sandbox — the skeleton is
deliberately dependency-free); the real CometBFT async window behavior under
load (the 200-in-flight claim is designed, not measured); and Phase 3 effort,
which history says I underestimate. The pure-JDK LatencyRecorder is a contract
stand-in with ~3% bucket error — do not ship results from it; swap in
HdrHistogram first. The `avg` field in CsvResultsWriter currently writes p50
as a placeholder and is marked as such. And the strongest argument against
this whole plan is one I cannot resolve for you: five focused days is a
meaningful fraction of the time remaining before September, and a v7 of the
shell scripts is one day. The harness is the better artifact and the better
methodology; the shell fix is the faster path to *any* results. If results
exist by early August either way, choose the harness; if not, don't.
