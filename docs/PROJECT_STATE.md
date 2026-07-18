# PROJECT STATE — Consensus Benchmark Harness (read this first)

This is the single source of truth for where the *implementation* stands.
It is written for a fresh Claude session with no memory of prior turns.
When something here conflicts with an older file, this document wins; update
it at the end of every working session.

Last updated: 2026-07-18 — **fifth hard review (F31–F38, all fixed or
decided same session; ledger in PENDING_TASKS) + the "everything
remote-ready" session.** Protocol note: at the author's request every
increment below was verified by ONE batched `mvn21 clean verify` at
session end (not per increment; stated in each commit). Highlights:
HEAD re-verified 115/115 green FIRST (the working tree held the
uncommitted-but-passing HotStuffMultiNodeFormationTest — 4-node HotStuff
formation EXECUTION-VERIFIED, 21.5 s). Then: **P3.3d-hotstuff COMPLETE**
(remote golden written first + provider HOTSTUFF branch matching verbatim
— **RemoteSshProvider now serves 7/7 systems**); **F33** image-presence
gate (paxi/hotstuff exist in no registry — fail-closed per node with the
docker save|load fix; cloud-init pre-pulls the four registry digests);
**HotStuffLogAnalyzer** (logs.py ported verbatim at dc01ac8, fetched
2026-07-18: same regexes/merge/formulas, byte-identical SUMMARY template,
round-trip-validated by the strict P2.5 parser; deviation: zero commits
THROWS — never an all-zero row); **M3.3-core `remote-run`** (one campaign
cell on real VMs from the loadgen: typed Inventory, per-system driver
dispatch, fault thread targeting the DETECTED leader — replica 0
documented for EPaxos/CometBFT only — heal in finally, env=hetzner
results, fault-run SUT-log collection, and the HotStuff path: upstream
client on the loadgen + chunked whole-log retrieval (`RemoteLogs`, the
sshj 2 MB window) + analyzer → summary.txt; HotStuff faults preregistered,
BASELINE only); new docs `HOW_TO_CONTINUE.md` (the map) and
`PER_ALGORITHM_TEST_GUIDE.md` (per-algorithm test/debug/benchmark
checklists). **Same session, batches 2–3: M3.3-full matrix runner**
(`campaign.MatrixRunner` + `campaign-run` — one system block, seeded
reproducible shuffle, manifest-resume, failure-continues, `--dry-run`);
**per-algorithm Grafana dashboards** (campaign overview + etcd/kafka/
cometbft/paxi+hotstuff, each with an embedded baseline-vs-observed +
false-positive reading guide); **collection + offline replay**
(`scripts/collect_block.sh` → one dated dir + count-verify pre-destroy;
`observability/offline/` replays the Prometheus snapshot on the SAME
dashboards); **`analysis/analyse.py`** (F15 successor: per-cell bootstrap
CIs, percentile SPREADS never averages, honest exclusion, `--selftest`);
and the novice layer `OBSERVABILITY_AND_EXPECTATIONS.md` (preregistered
per-algorithm baselines with corpus anchors + cleanup) + `docs/examples/`
(one real tiny run + two labelled synthetic shapes). Next: G2 golden
read-through (HUMAN) → P3.5 price check → P3.4 canary → per-system smoke.
**Suite after all three batches: 143 tests green** (one batched
`mvn21 clean verify`, BUILD SUCCESS; MatrixRunner + the `--dry-run`
parser pin added the last 6).
Prior update, 2026-07-17 — fourth hard review + fixes (F28–F30, ledger in
PENDING_TASKS; all fixed same day, TDD red→green) and the **P3.3d-kafka
prerequisite verified by execution**: 3-broker KRaft formation on a
user-defined Docker network (the increment the 2026-07-16 classifier
outage had blocked mid-verification) — quorum formed, acks=all committed
under min.insync.replicas=2, Isr=3; the remote Kafka golden can now encode
a VERIFIED wiring shape. F28: slowNode's SSH backgrounding held the exec
channel open (measured 30 s join timeout vs a real sshd — the injection
itself would stall and abort); fixed with nohup + stream redirect, golden
updated, shape pinned by a real-sshd assertion. F29: remote pre-clean now
sweeps thesis-* on EVERY provisioned node (D8 size-down and cross-system
leftovers silently violated stationarity); goldens updated FIRST as the
spec. F30: EtcdHttpDriver no longer claims leader index 0 (the M0 stub was
the v6 kill-node-1 trap); honest absence. **P3.3d-kraft ALSO DONE same
day (golden-file TDD): RemoteSshProvider serves KRAFT — golden written
from the EXECUTION-VERIFIED formation shape (private-IP voters/advertised
listeners, --network host, fixed KAFKA_CLUSTER_ID + no volume so state
dies with the container, internal topics RF=3/txn-min-ISR-2, readiness =
per-node "Kafka Server started" log gate then the api-versions quorum
oracle counting joined brokers == cluster size, BARE host:port
endpoints); matched verbatim, 3 new tests.** **P3.3d-cometbft STEP 1
ALSO DONE same day: the 4-validator formation VERIFIED BY EXECUTION in
7.9 s (CometBftMultiValidatorFormationTest) — testnet as one-shot
keygen, each node assembled from FOUR small JSONs (genesis, both keys,
priv_validator_state — required by init once the key is pre-placed),
peers via the --p2p.persistent_peers CLI flag excluding self (ids via
CMTHOME=<dir> show-node-id; --home is IGNORED, P2.3's fact
reconfirmed), sed for max_subscription_clients AND
addr_book_strict=false (RFC1918 everywhere); n_peers=3, tx committed
through 3-of-4 BFT precommits, every replica reached the committed
height. STEP 2 ALSO DONE (golden-file TDD): the golden
`tendermint-size4-start-stop.txt` written FIRST — keygen/show-node-id
as docker run --rm one-shots on node1 (-e CMTHOME, the probed fact),
artifacts COMPACTED to single-line JSON before printf, fresh-state
rm -rf first, private-IP peers excluding self, both seds, readiness =
/health per node then latest_block_height>=1 on node1 (the quorum
gate, stuck-at-0 pinned fail-closed), size≠4 refused (D9) — matched
verbatim by the RemoteSshProvider TENDERMINT branch, 3 new tests.
**P3.3d-kafka_zk STEP 1 ALSO DONE same day: the D10 colocated shape
VERIFIED BY EXECUTION in 18.7 s (KafkaZkColocatedFormationTest), built
on two probed facts — the apache/kafka image's entrypoint REFUSES ZK
mode ("Formatting is only supported for clusters in KRaft mode") but
the SAME digest-pinned image carries full ZK-mode binaries, so the
recipe bypasses the entrypoint (printf server.properties +
kafka-server-start.sh) — F6 stays an identical-binaries comparison; ZK
mode logs "started (kafka.server.KafkaServer)", NOT KafkaRaftServer.
The zookeeper:3.9 image (digest-pinned, new ZOOKEEPER_IMAGE constant)
forms the ensemble from ZOO_MY_ID+ZOO_SERVERS and serves Prometheus on
:7000 via ZOO_CFG_EXTRA (verified: znode_count — P4.3's source).
End-to-end: 3 brokers registered via the ensemble, acks=all committed
under min.insync.replicas=2, Isr=3. STEP 2 ALSO DONE (golden-file TDD):
`kafka_zk-size3-start-stop.txt` written FIRST — two containers per VM,
private-IP wiring, the server.properties printf'd inside the broker's
start script, gates = ZK :7000/metrics per node + the ZK-MODE started
line + api-versions==3, teardown brokers-then-ensemble (aux list in
stop(), pinned) — matched verbatim by the provider KAFKA_ZK branch.
The RemoteSshProvider now serves SIX of seven systems; only HOTSTUFF
fails closed. **P3.3d-hotstuff increment 1 ALSO DONE: the pinned-source
image (infra/hotstuff/Dockerfile, commit dc01ac8, image id 8501e107d4bf
— upstream has NO Cargo.lock, the image id IS the reproducibility
anchor; rust≥1.85 and libclang/g++ measured as build requirements) +
the node/client CLI contract source-verified and probed live (keys →
{name,secret} b64 JSON; committee JSON with three ports per node;
parameters fields; -vv feeds the SUMMARY). Remaining: the hotstuff
formation test, its golden + provider branch. Then the G2 human
read-through of ALL goldens, P3.4 canary.**
Suite: **114 tests green.**
Prior update, 2026-07-15 — second hard review (F18–F21 ledgered; F18 jetcd
5 s deadline fixed TDD; doc drift fixed) **and P2.2 (KafkaDriver) completed
TDD end-to-end**: KRaft single-node substrate (P2.2a), production
KafkaDriver (P2.2b), G1 flaw-B regression vs kafka-producer-perf-test
(P2.2c — local order-of-magnitude gate, measured 0.95x; the symmetric 15%
moved to G3/M6.1 with measured justification), engine `firstError`
observability, and F19 (FaultInjector leader-targeting per F13) fixed
before P3.3 can inherit it. **Suite: 66 tests green; all work committed and
pushed per increment.** New: `docs/MEASUREMENT_DIAGRAMS.md` (engine core +
per-system commit-path/measurement diagrams). Prior update (2026-07-08/09):
F1–F17 review, D10/D11 locked, Terraform layer locally verified (no apply,
G2 intact), Prometheus role-label fix, CAMPAIGN_RUNBOOK created, P0+P1
closed, P2.1 done. Backlog + ledger: `docs/PENDING_TASKS.md`.
**Same-day continuation: P2.3 (CometBftDriver) DONE — 602 tx/s measured
(100x the retired probe's ceiling), p50 ≈ block interval; suite 71 green;
see PENDING_TASKS for the probed facts (subscription cap, kvstore '='
split, nonce). Next: P2.4 (needs a built-from-source Paxi image first).**
**2026-07-16 third hard review + fixes (F22–F25, ledger in PENDING_TASKS):
paxi has NO `/state` endpoint — P2.4 leader detection corrected to the
`Ballot` response header (source-verified @ 6823d0b, which also closed the
P1.1 Atoi re-verify note); engine bucket array boundary fixed TDD (F23,
capacity duration+6); EtcdHttpDriver/PaxiDriver close() now release the
JDK 21 HttpClient (F17 residual). Same-day continuation, P2.4a DONE
(TDD): paxi image built from pinned source (infra/paxi/Dockerfile,
6823d0b, image id bc12c64d3391) + LocalDockerProvider 3-node PAXOS/EPAXOS
with a committed-probe-write quorum gate (election is lazy; no /health)
and fail-closed requireLocalImage. Suite: 74 tests green. P2.4b ALSO DONE
(TDD): production PaxiDriver — Ballot-header leader detection (fail-loud
on multi-zone/malformed), F24 endpoint strategy pinned by test (PAXOS one
entry, EPAXOS round-robin), fail-closed connect probe; acceptance vs real
Docker: committed write, leader corroborated through an independent stack
AND entry, follower-kill → commits continue on 2/3. F26 ledgered
(source-verified + follower-kill side empirically confirmed): stock paxi
has no failure detector — hard leader kill = silent wedge; leader_kill
design preregistered at P3.3. P2.4c ALSO DONE — **P2.4 FULLY CLOSED**:
EPaxos exercised for the first time (engine round-robin over all three
replicas, 0 errors) and the D7 knob end-to-end (c=10% run against the
real cluster → `c10` path + manifest identity, 0 errors). Suite: 81
tests green. P2.5 ALSO DONE (TDD): `HotStuffSummary` strict fail-closed
parser (missing fields named, duplicate blocks refused; fixture verbatim
from upstream logs.py — re-pin vs a real fab.log at Phase C). **THE P2
DRIVER PHASE IS COMPLETE; GATE G1 SIGNED OFF BY THE AUTHOR 2026-07-16.**
P3.3a (M4.1) DONE (TDD): `SshExecutor` SPI + `RecordingSshExecutor`
(the golden-file substrate) + `SshjExecutor` (pooled, 30 s bound),
acceptance vs a real key-authenticated sshd in Docker. The
**remote-deltas preregistration** (PENDING_TASKS P3.3) pins the
topology/network changes between local Docker and the servers: real
private IPs in NodeHandle (F20), host networking endpoints, netem iface
via `ip -o route get` (never assumed), pairwise-IP partitions that
preserve the loadgen→leader measurement path, LAN-RTT timing notes.
Suite: 89 tests green. P3.3b ALSO DONE (golden-file TDD): RemoteSshProvider
(etcd first, others fail closed) — the golden was written FIRST as the
spec, the recorded command sequence matches it verbatim, F20 resolved
(real private IPs in NodeHandle, pinned by test), health gate fails
closed naming the node. Suite: 93 tests green. P3.3c ALSO DONE (golden-file TDD): SshFaultInjector
— kill/packet_loss(netem on the RESOLVED iface)/partition(pairwise node
IPs, loadgen→leader path preserved)/slow_node(host stress-ng)/double_kill,
heal replays LIFO best-effort-but-loud; golden matched verbatim, 5 tests
incl. iface-resolved-not-assumed + heal-on-inject-throw. Suite: 98 tests
green. **F26 DECIDED by the author 2026-07-16: paxi leader_kill = adaptive-
mode WEDGE (honest — no failure detector; implementation property, no
failover-ECDF point; methodology §7 preregistration written).** P3.3d-paxi ALSO DONE (golden-file TDD): RemoteSshProvider now serves
PAXOS/EPAXOS (config.json with real private IPs via single-quoted printf,
bind-mounted; committed-probe-write gate; -adaptive default per F26; the
leader_kill WEDGE pinned — one docker kill, no heal). Suite: 101 tests
green. Next P3.3d: Kafka/KRaft remote (multi-broker private-listener
wiring — the highest-risk recipe), CometBFT, HotStuff; then the G2 human
read-through of all goldens. Then P3.4 canary (G2 billing gate — nothing
applied yet, G2 intact).**

---

## 1. What this project is

A master's thesis: *Evolution and Comparison of Consensus Protocols in
Distributed Systems*. Theoretical analysis plus an experimental benchmark of
seven systems — KRaft, Kafka+ZooKeeper (ZAB), etcd (Raft), CometBFT
(Tendermint), Paxos, EPaxos (both via Paxi), and HotStuff (asonnino). Metrics:
consensus latency, throughput, fault tolerance, scalability.

The experimental work is being rebuilt as a **unified Java benchmark harness**
(`consensus-bench`) that drives all systems through one async load model,
deployed on a **multi-VM Hetzner cluster** (one consensus node per VM, real
network), with **Prometheus/Grafana** as the explanation-and-validity layer.

## 2. Why the rebuild (the history that matters)

The prior approach was shell scripts orchestrating per-system tools. Two
classes of failure killed it:

- **Measurement flaws** (found by scrutiny, verified): the CometBFT probe used
  6 blocking clients on `broadcast_tx_commit` → a ~6 tx/s ceiling while the
  system does thousands; every HTTP probe used `urllib` with no connection
  reuse → a TCP handshake inside every latency sample. Root cause: each system
  measured by a *different client stack with different concurrency semantics*.
  The load model varied more than the protocols.
- **A "v6" multi-VM deploy layer shipped unverified** and would have failed at
  first contact (SSHing to private IPs from the laptop; no inter-node key
  distribution; fault runs reusing corrupted clusters; string-derived
  container names like `kraft1` that don't exist; a `local` outside a function
  that crashes a whole session; a scalability path collision that silently
  skips cells; Tendermint peer-ID parsed from a nonexistent JSON field). It was
  reviewed honestly and retired.

**The lesson encoded into everything now: execute and verify, never assert.**
Typed Java kills the string-surgery bug class at compile time; hard gates with
manual review kill the ship-unverified bug class.

## 3. What EXISTS and is VERIFIED (as of this document)

### Harness architecture (true to code, 2026-07-08; ✦ = designed, not built)

```
                 java -jar consensus-bench.jar
                 ┌───────────── Main ─────────────┐
                 │ (endpoint-run | local-run)     │  fail-closed arg parse,
                 │ -v → DEBUG phase boundaries    │  duration>warmup guard
                 └──────┬──────────────┬──────────┘
     lifecycle          │              │ measurement
┌───────────────────────▼──┐   ┌───────▼────────────────────────────────┐
│ ClusterProvider (SPI)    │   │ WorkloadEngine                         │
│ ├ LocalDockerProvider    │   │  open-loop schedule / saturation mode  │
│ │  digest-pinned etcd,   │   │  bounded in-flight window (Semaphore)  │
│ │  /health gate, Ryuk,   │   │  intended-time latency (CO correction) │
│ │  pre-clean leftovers   │   │  keyFor(c): K=1000 reused, D7 conflict │
│ └ RemoteSshProvider      │   │  key 0 exclusive ← knob (P1.2)         │
│   7/7 golden-served      │   └──────┬─────────────────────────────────┘
│   + SshFaultInjector     │          │ write(keyId∈[0,1000), value)
└──────────▲───────────────┘          ▼
           │ campaign.RemoteRunner (remote-run: one cell on real VMs,
           │ Inventory-typed, fault mark, env=hetzner; HotStuff = upstream
           │ client on loadgen + RemoteLogs + logs.py-port analyzer)
                     ┌─ ConsensusDriver (SPI) ────────────────┐
                     │ per-driver keyId encoding @connect():  │
                     │ ├ EtcdDriver (jetcd/gRPC, PRODUCTION;  │
                     │ │   leader detect x-validated vs HTTP) │
                     │ ├ EtcdHttpDriver  fallback + G3 check  │
                     │ ├ PaxiDriver      "/<id>" numeric-only │
                     │ ├ KafkaDriver (KRAFT+ZK) · CometBft    │
                     │ └ HotStuffLogAnalyzer (logs=metrics)   │
                     │ completes ONLY on consensus commit     │
                     └──────┬─────────────────────────────────┘
                            │ CompletionStage → whenComplete
                            ▼
      LatencyRecorder (real HdrHistogram: ConcurrentHistogram all/warm,
      3 sig digits, auto-resize; true mean; warmSnapshot() for pooling)
      per-second commit buckets · errors (never fabricated data)
      EventLog (opt-in, fault runs: lock-free timestamped events, one
      clock domain, failoverMillis = fault mark → first commit)
                            │
                            ▼
      CsvResultsWriter → <system>/<scenario>/size<N>[/c<pct>]/<runId>/
        throughput.csv (every second, zeros kept) · latency.csv (true mean)
        latency.hlog (FULL histogram — methodology §3 pooling input)
        manifest.json (v2: params, environment, image digest, version,
          config_hash, error_rate, fault/failover ms, honest status)
        metrics/*.csv ✦ (PrometheusExporter, M5.4) · validity.json ✦ (M5.5)
```

Data-flow invariants the tests pin: same keyId stream and load model for
every system (only the transport differs); latency charged against the
*intended* schedule (a stall inflates the tail, never hides it); a run
that lost quorum or majority-errors reports `failed`, never data.

**Maven build (M1.1) — DONE, verified by execution.** `mvn21 clean verify`
(Maven 3.9.11 on Amazon Corretto 21; `mvn21` is a local wrapper that pins
JAVA_HOME to the corretto JDK) is green: every pinned dependency resolves and a
~52 MB shaded uber-jar builds and runs (`java -jar` reproduces the M0 CLI). One
fix was required — the shade plugin now strips signed-jar signatures
(`META-INF/*.SF|DSA|RSA`) that `sshj`'s transitive Bouncy Castle jars carry,
which otherwise fail uber-jar assembly. The pom's "dependencies unverified"
caveat is now closed. (The M2.1 heads-up about `ServicesResourceTransformer`
is resolved — see the P0.1 paragraph below.)

**P0.1 (logging + `-v`) — DONE 2026-07-08, TDD + run-verified.** First unit
tests in the repo (`ArgParserTest`, red→green, proves surefire wiring); slf4j
logging with `-v/--verbose` → DEBUG phase boundaries + a verbose-only
per-second reporter thread; the hot per-op path is untouched either way. The
`-v` smoke against real etcd exposed and fixed a real packaging bug: the
uber-jar's logging had been silently NOP since M1.1 (kafka-clients' slf4j-api
1.7.36 won Maven mediation over slf4j-simple's 2.0.16; shade wasn't merging
`META-INF/services`). Fixed with a direct `slf4j-api:2.0.16` dependency +
shade `ServicesResourceTransformer` — which also closes the M2.1 gRPC
heads-up. Dependency tree verified: only slf4j 2.0.16 remains.

**P1.5 + P0.4 — DONE 2026-07-08, TDD.** The engine is pinned by 6
characterization tests (`FakeDriver`: CO correction, drain accounting,
buckets==histogram, warmup flagging, fail-not-fabricate, rate adherence) —
which exposed and fixed a real drain race (release-before-record could lose
final samples from Result). P0.4 fixed by red→green: zero-second dropping in
throughput.csv (stall evidence preserved), manifest `error_rate` + honest
status (majority-failed ≠ complete), `close()` logs instead of swallowing,
duration>warmup guard. Suite green. Manual verification of the whole
laptop-provable surface: **`docs/LOCAL_TESTING.md`** (executed commands
with real expected outputs, 9-point checklist).

**P0.2 (`LocalDockerProvider`) — DONE 2026-07-08, TDD vs real Docker.**
Digest-pinned etcd v3.4.30 clusters on a per-cluster Docker network
(etcd1..N aliases, parallel start — /health only answers once quorum
exists), `thesis-*` names, Ryuk-backed teardown. Acceptance proven: 3/3
quorum write; kill 1 → commits resume after re-election; kill 2 → writes
fail (fail-closed); zero leftover containers. Environment quirk pinned in
the pom: Docker Engine 29 (min API 1.40) rejects Testcontainers ≤1.21.3
(client API 1.32) — **must stay ≥1.21.4**. Uber-jar 52→77 MB (docker-java).
Suite: **21 tests green**; tests now require the local Docker daemon.

**P0.3 (`local-run`) — DONE 2026-07-08. THE P0 GOAL IS ACHIEVED.**
One command — `java -jar consensus-bench.jar local-run --size {1|3} --rate R
--duration D [-v]` — does idempotent pre-clean (crashed-run leftovers
force-removed, loudly), fresh digest-pinned cluster, measured run, standard
CSV/manifest, teardown guaranteed via try-with-resources. Measured from the
shaded jar: **7.6 s (size 1) / 8.2 s (size 3)** wall-clock for the whole
loop (target <30 s); quiet mode = 5 INFO lines (container-lib chatter
silenced at default level). Suite: **22 tests green.** Every later driver
and fault test is developed against this loop.

**P1.1 (typed key contract) — DONE 2026-07-08, TDD with a real red.**
The keyspace test against the old engine measured **778,324 distinct keys
over 778,324 ops** — executable proof of the unique-key bug (zero
contention, D7 unfireable). Now: `ConsensusDriver.write(int keyId, value)`
with `KEY_SPACE = 1000` (Paxi Table 3) as the SPI contract — the typed int
makes the bug inexpressible. Drivers own their encodings, precomputed at
connect() (etcd `bench/k<id>` base64 cache; Paxi `/<id>` URI table,
numeric-only for its Atoi parser — source-verified vs paxi/http.go
2026-07-16, F22). Suite: **25 tests green**, all Docker
integration tests re-passed on the new contract.

**P1.2 (D7 conflict knob) — DONE 2026-07-08, TDD.** `Config.conflictRatio`
(record-constructor validation: [0,1], NaN rejected); fraction c routes to
**key 0, exclusive to conflict traffic** (uniform draws [1,1000)) so the
realized fraction equals c by construction — no (1−c)/K bias. Result path
gains `c<pct>` segment for c>0 only (legacy tree intact; collisions
impossible); manifest gains `conflict_ratio`; identity accepts whole
percents only (0.025 → IAE — path rounding would merge distinct cells).
Statistical acceptance non-flaky by design (±0.015 at n≥10⁴ ≈ ≥5σ);
CLI run-verified (`--conflict 0.10` → `.../c10/...` + manifest field;
1.5 fails fast). Suite: **31 tests green.**

**P1.3 (real HdrHistogram + .hlog + Little's Law) — DONE 2026-07-09, TDD.**
Recorder → two auto-resizing `ConcurrentHistogram`s (0.1% precision,
wait-free hot path; auto-resize so CO-corrected stall latencies can't
overflow mid-failover); true mean replaces the avg=p50 placeholder;
**`latency.hlog`** (standard HistogramLogWriter v1.3) persisted per run —
methodology §3's pooled-histogram analysis is now *possible* (merge test:
two snapshots added == histogram of all raw samples). M0's Little's-Law
corroboration is now a permanent self-test (saturation: throughput × mean ≈
window, 3 independently measured quantities). Jar run-verified (avg=2837 ≠
p50=2263; .hlog in the run dir). Suite: **38 tests green.** The M0 "don't
trust results until real HDR" caveat is closed.

**P1.4 (failover events) — DONE 2026-07-09, TDD.** `core.EventLog`:
opt-in (4-arg engine ctor; 3-arg keeps the hot path event-free),
preallocated long[] with lock-free claimed-slot append, one clock domain
(events + fault mark = System.nanoTime in the harness JVM),
`failoverMillis()` = first commit at-or-after the mark, overflow drops
loudly (counter for validity), no recovery ⇒ empty — never fabricated.
End-to-end pinned: scripted stall, fault marked mid-stall from another
thread, recovered gap within 150 ms of ground truth. F4's kill→first-commit
measurement now exists at sub-second resolution. Suite: **45 tests green.**
Residual: manifest fields land with P1.6; the campaign runner (M3.3) sizes
the log and marks injection.

**P1.6 (manifest v2) — DONE 2026-07-09, TDD. P0 AND P1 ARE NOW FULLY
CLOSED.** The manifest pins everything methodology §1 claims: load params,
`environment` (local|hetzner — Main always "local"; "hetzner" is the
campaign runner's exclusively), `image` (provider digest; honest null for
endpoint-run), `harness_version` (jar Implementation-Version via shade
manifestEntry; "dev" from classes), `config_hash` (12-hex SHA-256 over
every cell-defining input; deterministic + param-sensitive by test), and
P1.4's `fault_injected_at_ms`/`failover_ms` (explicit nulls — absent ≠
zero). Jar-verified live. Suite: **49 tests green.** The measurement
instrument is complete: what remains before real data is drivers (P2),
remote layer (P3, gated), and the validity/obs stack (P4).

**P2.1 (jetcd EtcdDriver + leader detection) — DONE 2026-07-09, TDD.**
The production etcd driver: native async gRPC put; connect() precomputes
all 1000 key encodings and fail-closes on a status probe; leader = the
endpoint whose own member id equals the leader id it reports (endpoint
order = node order; dead members skipped loudly; no leader ⇒ empty).
Acceptance vs real Docker: detected leader **cross-validated against the
independent HTTP-gateway stack**; killing the DETECTED leader yields a
new leader within 30 s and writes keep committing on 2/3 quorum; key
contract pinned identical to EtcdHttpDriver. `local-run` now rides
jetcd (EtcdHttpDriver = fallback + G3 cross-check). **The shaded jar
drove a 3-node quorum through gRPC** — the ServicesResourceTransformer
requirement is exercised fact. Suite: **51 tests green.**

**P2.1 amendment (2026-07-15, TDD — review finding F18).** The red test
proved a write against a quorum-lost 3-node cluster completes only via
etcd's **server-side** ~7 s "request timed out" grace — and only when the
gRPC channel happens to pick the surviving member; a dead endpoint grants
no bound at all, and the engine's drain barrier waits on every completion.
Fix: `EtcdDriver.write()` now applies `orTimeout(5 s)` (the HTTP drivers'
bound — driver parity preserved for G3), and `ConsensusDriver.write`'s
javadoc makes bounded completion a contract every driver must honor
(P2.2 Kafka: `delivery.timeout.ms`). Acceptance green vs real Docker:
kill 2 of 3 → the write fails within the deadline, never commits, never
hangs. Suite: **52 tests green.**

**P2.2 (KafkaDriver, all of a/b/c) — DONE 2026-07-15, TDD.** Substrate:
LocalDockerProvider starts a digest-pinned apache/kafka 3.9.1 KRaft
single-node (testcontainers `kafka` module; multi-node fails closed until
the campaign provider). Driver: acks=all, commit timed in the send
callback, `delivery.timeout.ms=5000` (the F18 bounded-completion contract),
metadata warmed at connect() so `send()` never blocks the open-loop issue
thread; leader = bench-topic partition-0 leader mapped by endpoint match
(unmappable ⇒ throw — never kill the wrong node). Acceptance vs real
Docker: committed write; leader detection; dead broker ⇒ write fails <8 s
asynchronously. **G1 flaw-B regression**: measured against
`kafka-producer-perf-test` exec'd inside the broker container — final run
0.95x. The symmetric 15% gate moved to G3/M6.1 (cluster) with measured
justification: across 4 laptop configurations the ratio swung 0.2x–2.8x
for identified environmental reasons (oracle ramp fraction at short runs;
null-key sticky vs keyed partitioning ≤2x; window/latency Little's-Law cap
on 1 partition; 5 s vs 120 s delivery-timeout asymmetry under laptop
writeback storms) — full evidence in the parity test's javadoc. Fallout
fix (TDD): `Result.firstError` — the engine keeps and WARNs the first
failure cause (a 551-error run with no cause cost a diagnosis cycle).
**F19 also fixed (TDD)**: `FaultInjector.apply` now isolates the DETECTED
leader for NETWORK_PARTITION and takes packet-loss percent as a parameter
(red run showed partition:0 + hardcoded loss:5). Suite: **66 tests green.**

**P2.3 (CometBftDriver + single-validator substrate) — DONE 2026-07-15,
TDD, probe-first.** The RPC's real behavior was probed before any code:
HTTP 200 ≠ commit (empty tx → 200 with check_tx.code=2; v0.38 renames
deliver_tx → tx_result); duplicate tx bytes → JSON-RPC error ("tx already
exists in cache") ⇒ per-tx nonce; `rpc.max_subscription_clients` (default
100) caps concurrent broadcast_tx_commit callers — measured 99/250 at the
default, 250/250 after the provider raises it to 2000 (the plan's
"CometBFT RPC at 200 in-flight" risk, closed by measurement). One red
mid-build: a raw 8-byte nonce contains '=' (0x3d) often enough that
kvstore's exactly-two-parts CheckTx failed 12% of txs — nonce now rides as
ASCII hex (firstError named the cause instantly). **Flaw-A acceptance:
602 tx/s sustained, 100x the retired probe's ~6 tx/s ceiling; p50 = 1.09 s
≈ the block interval — the latency semantics behaving exactly as
documented.** Window 600 (the 200 floor was window-bound: 220 tx/s =
window/latency). Suite: **71 tests green.**

**Compiled, dependency-free Java skeleton** (`harness/src/main/java/`, JDK 21,
`javac`-clean, ~600 lines, 10 source files):

- `core.SystemUnderTest`, `core.Scenario` — typed identities; each system
  carries its real container-naming scheme and cluster shape; each scenario
  carries `mutatesCluster()` so the campaign runner cannot reuse a corrupted
  cluster.
- `driver.ConsensusDriver` — the central SPI: async
  `write(key,value) -> CompletionStage` that completes only on **consensus
  commitment**, plus first-class `currentLeaderIndex()`.
- `core.WorkloadEngine` — open-loop rate scheduler with a bounded in-flight
  window; latency recorded against **intended** send time (coordinated-omission
  correction); real per-second commit buckets; saturation mode too.
- `core.LatencyRecorder` — real HdrHistogram since P1.3 (2026-07-09):
  two auto-resizing `ConcurrentHistogram`s (all/warm, 3 significant digits),
  true mean, `warmSnapshot()` for pooling. The "stand-in, don't trust
  results" caveat is CLOSED.
- `driver.EtcdHttpDriver` — pure-JDK etcd driver via the v3 JSON gateway,
  pooled HttpClient.
- `driver.PaxiDriver` — pure-JDK Paxos/EPaxos driver (round-robins endpoints
  for EPaxos).
- `results.CsvResultsWriter` — writes the EXACT CSV/manifest contract the
  existing `analyse.py` and React visualizer consume; run identity includes
  cluster size in the path (the v6 collision is now inexpressible).
- `topology.ClusterProvider` — SPI (impls not yet written); faults act on typed
  `NodeHandle`s.
- `Main` — minimal CLI.

**M0 vertical slice — EXECUTED against real etcd 3.4.30** (evidence in
`harness/results/`):
- Open-loop 300 ops/s → 306.6 achieved, 0 errors, genuine per-second variance
  (not a synthetic flat line).
- Saturation (window 64) → 1020.8 ops/s, p50 58.9 ms; **Little's Law predicts
  62.7 ms → the engine's window accounting and latency measurement corroborate
  independently.**
- Accidental negative test passed: when etcd died, the harness reported all
  errors / zero committed — it does not fabricate data.
- Caveats: single-node (no quorum yet), JSON gateway (not jetcd), absolute
  numbers reflect shared sandbox CPU. None of these invalidate the contract;
  they are the ladder M2/M3 climb.

**Infrastructure (`infra/`) — locally VERIFIED with terraform 1.9.8 on
2026-07-08; NOT yet `terraform apply`-ed (G2 gate intact):**
- `cloud-init.yaml` now exists (docker, node_exporter host-network container,
  chrony, stress-ng, xxd/jq) — the previously missing hard reference.
- `main.tf` is phase-parameterized: `consensus_node_count` (default 3, max 7
  per D8, validation-enforced) and `consensus_node_type` (default ccx13;
  ccx23/33 for the BFT phase per D9); loadgen is ccx13 (dedicated vCPU, D11);
  a **spread placement group** puts every VM on a distinct physical host
  (reproducible inter-node latency); cost output computed from a price map
  (post-2026-06 repricing; confirm via `hcloud server-type list` pre-apply).
- Verified by execution: `terraform fmt`/`init`/`validate` green; dummy-token
  `plan` green for all three phases (A: 11 resources €0.292/h; B: count=7,
  node7=10.0.0.17, €0.567/h; C: 4×ccx23 €0.636/h); count=8 rejected by the
  guardrail. Destroy is provably complete: servers/network/firewall/
  placement-group/key/inventory only — no volumes, no floating IPs.
- **The dual-IP inventory is a generated OUTPUT** (`local_file.inventory`), so
  it cannot drift from reality — this structurally fixes the v6 private/public
  IP flaw. It now also records CONSENSUS_NODE_COUNT/TYPE for the phase.
- Still unexercised (needs a real apply, gated behind G2): actual boot,
  cloud-init semantics, private-net behavior — that is P3.4's canary.

**Observability starter** (`observability/`, YAML-valid): Prometheus config
templated on the inventory, obs-VM compose (Prometheus + Grafana with
provisioned datasource), and `export_queries.txt` (the fixed PromQL set the
harness archives per run into `metrics/*.csv`).

## 4. What remains NOT yet built (F34 correction 2026-07-18 — everything
   previously listed here except the items below IS built and tested; see
   §3 and the PENDING_TASKS ledger)

- **HotStuff fault scenarios** (preregistration required first —
  PENDING_TASKS NEXT-4; remote-run serves HotStuff BASELINE today) and the
  picocli CLI (M1.3 — the hand-rolled parser is now fail-closed, F32).
- `ValidityChecker` (M5.5), `PrometheusExporter` (M5.4 — per-run
  metrics/*.csv) + harness self-metrics on :9400 (M5.3), docker-events
  audit (the open half of P4.5), ZK/JMX metric-name fixtures (P4.3 — the
  Kafka dashboard panels stay untrusted until then).
- The **canary** (P3.4) and everything gate-blocked behind G2's HUMAN
  golden read-through (all seven goldens now exist and are test-matched).
- `analysis/analyse.py`'s growth to pooled histograms, Holm correction,
  ECDFs, and the 8 figures (M6.4 — the foundation now exists in-repo:
  per-cell CIs, percentile spreads, honest exclusion, `--selftest`).

**Built this session's batches 2–3 (previously listed here):** the
**M3.3-full matrix runner** (`campaign.MatrixRunner` + `campaign-run` CLI:
one system block = scenarios×rates×conflicts×reps, seeded reproducible
shuffle, manifest-resume, failure-continues, `--dry-run`); **Grafana
dashboards as code** (campaign overview + per-algorithm, each with an
embedded reading guide); **collection + offline replay**
(`scripts/collect_block.sh`, `observability/offline/`); the **analyse.py
foundation**; and the novice-facing `OBSERVABILITY_AND_EXPECTATIONS.md` +
`docs/examples/` sample data.

## 5. The plan and its gates

Authoritative plan: `IMPLEMENTATION_PLAN.md` (execution-grade — every task has
a deliverable + acceptance command + dependency). Milestones M0(done)→M6, with
three non-negotiable gates:
- **G1** (after drivers): the Kafka and CometBFT acceptance tests are the
  regression tests for the two original probe flaws.
- **G2** (before full provisioning): golden tests reviewed by a human + a
  sub-€0.10 canary green. This is the gate v6 never had.
- **G3** (before campaign): cross-validation — harness vs native tools (Kafka
  perf-test, etcd benchmark, Paxi benchmarker) on the real cluster, ≤15% or
  explained.

Methodology: `DATA_ANALYSIS_METHODOLOGY.md` (thesis-chapter grade — open-loop
load, histogram pooling not percentile-averaging, six per-run validity gates,
estimation-first stats at n=5, the claim framework, threats to validity).
Decisions/architecture: `MASTER_PLAN.md`. Campaign operations (topology,
phases, cost, run durations, storage, Prometheus retrieval protocol):
`CAMPAIGN_RUNBOOK.md`. Execution model (**one system under test at a time,
serial blocks on shared per-phase infra — no per-algorithm Terraform**),
per-system time/cost budget, artifact-collection inventory incl. the SUT-log
gap (P4.5), and the destroy-then-analyze-locally protocol:
`EXECUTION_AND_COST_MODEL.md` (2026-07-09 decision record).

## 6. Locked decisions (do not relitigate without cause)

- Multi-VM on Hetzner; Docker is the per-VM *packaging*, not the topology
  (host networking, pinned digests).
- Dedicated vCPU (CCX13) for consensus nodes — shared vCPU is a validity
  threat (CPU steal).
- Java, because it is the author's strongest language (11 yrs, Kafka/ZK), the
  ecosystem is native (kafka-clients, jetcd, HdrHistogram, Testcontainers,
  sshj), and the visualizer backend is already Java.
- Paxi is a system-under-test and a **cross-validation oracle**, NOT the
  harness (its REST client is designed for benchmarking Paxi inward; driving
  Kafka/CometBFT through it adds a hop inside every latency sample).
- Harness = primary instrument; Prometheus/Grafana = explanation + validity +
  live campaign monitor; final figures render from exported CSVs.
- Preregistered **expected-vs-observed with deviation attribution** is part of
  the methodology (predict each metric's direction/order from protocol theory
  *before* running; classify every material deviation as measurement artifact,
  implementation property, environment, or genuine protocol behavior).
- **D7 — conflict ratio (0/2/10%) is a workload factor for Paxos/EPaxos.** The
  knob EPaxos's fast path depends on; without it EPaxos ≈ Paxos. Requires fixing
  the `keyFor()` unique-key bug (reuse K=1000) + a conflict fraction. (MASTER_PLAN D7.)
- **D8 — Raft scalability {3,5,7} on 7 provisioned VMs** (etcd + KRaft), spun up
  only for those cells. (MASTER_PLAN D8.)
- **D9 — BFT nodes upsized** (≈CCX23/33) toward HotStuff's published class; the
  CFT/BFT hardware seam is an accepted, documented threat (within-family absolute
  comparisons only). (MASTER_PLAN D9, methodology §7.)
- **D10 — Kafka+ZK runs ZooKeeper colocated on the three broker nodes**, mirroring
  KRaft's combined controller+broker mode, so F6 holds hardware/colocation constant.
  ZK 3.6+ native Prometheus metrics scraped on :7000. (MASTER_PLAN D10.)
- **D11 — Loadgen on dedicated vCPU (ccx13)** — the instrument's clock/scheduler
  must not sit on shared cores while CPU steal is called a validity threat; a
  loadgen-steal check joins validity gate 1. (MASTER_PLAN D11, methodology §4.1.)
- **No Ansible** — Terraform owns infra state, cloud-init owns first-boot substrate,
  the harness's RemoteSshProvider owns dynamic orchestration (typed Java + golden
  tests). A third automation language adds risk where v6 died. (CAMPAIGN_RUNBOOK §6.)

## 7. Known honest limitations (carry into the thesis)

HotStuff is least-instrumented (no Prometheus, mean-heavy client output —
log-parsing fallback, flagged in every figure it appears in). Paxi commits
in-memory vs fsync-backed Kafka/etcd (durability asymmetry — documented, not
equalized). CometBFT latency structurally includes block-interval wait.
n=5 keeps hypothesis tests nearly decorative — CIs carry the argument.
Single datacenter, small clusters — results generalize to LAN, not WAN (WAN
discussed via the papers, not claimed from our data).

## 8. Author profile (for calibrating help)

Senior engineer, ~11 yrs, deep Kafka/ZooKeeper (a betting-platform CDC
pipeline is a live case study). Strong Java; newer to cloud/K8s. Values
simplicity, minimalism, correctness, and **honest critical review over
reassurance** — actively wants overconfident output corrected. Prefers
manual per-system validation before automating. Supervisor: Prof. Γραμματή
Πάντζιου. No hard deadline pressure on the implementation right now; do it
right.

## 9. Working agreement for Claude on this project

1. **Execute, don't assert.** If a claim can be compiled, run, or parsed in
   the sandbox, do it and show the evidence before stating it works. M0 is the
   template.
2. **TDD.** Write the failing test first, then the code; every driver/component
   lands with its acceptance test. The plan's acceptance criteria are the test
   specs.
3. **Karpathy rule — keep the human in the loop.** Work in small, reviewable
   increments; stop at natural checkpoints and surface what was done + what's
   next + what's uncertain, rather than generating large unreviewed batches.
   One component (or one gate step) per increment.
4. **Honest review every time work is claimed done.** Separate verified from
   assumed. Name what wasn't tested. History on this project is that skipped
   verification is the primary failure mode.
5. **Respect the gates.** Never advance past G1/G2/G3 on confidence; the gate's
   evidence must exist. G2's human read-through is deliberately manual.
6. Update this document at session end.
