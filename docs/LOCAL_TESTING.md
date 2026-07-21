# Local Testing Guide — exact commands, expected output

Manual verification of everything that can be tested on the laptop, with the
**exact commands and the output you should see**. Every command in this file
was executed on 2026-07-08 and the "expect" blocks are real captures — if you
see something different, that is a finding, not noise.

Safety: nothing here talks to Hetzner or bills anything. `terraform plan`
runs with a dummy token and cannot create resources; `apply` is gated behind
G2 and is **not** part of this guide.

Conventions used below:

```bash
# run once per shell
export JAVA=/usr/lib/jvm/amazon-corretto-21.0.7.6.1/bin/java
export REPO=~/Downloads/consensus-bench-thesis
export JAR=$REPO/harness/target/consensus-bench-0.1.0-SNAPSHOT.jar
# mvn21 = your existing alias (Maven 3.9.11 on Corretto 21).
# If the alias is unavailable (non-interactive shell): ~/tools/maven/mvn21.sh
```

---

## 1. Build + full test suite (~3 min; needs the Docker daemon)

```bash
cd $REPO/harness
mvn21 clean verify
```

**Expect** (versions/counts as of 2026-07-21 — counts only grow):

```
Tests run: 170, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

and a ~77 MB shaded jar at `target/consensus-bench-0.1.0-SNAPSHOT.jar`.
The provider tests need Docker plus BOTH local image builds
(`docker build -t paxi:6823d0b infra/paxi` and
`docker build -t hotstuff:dc01ac8 infra/hotstuff`); the first ever run
pulls the pinned registry images. If you see `client version 1.32 is too
old`, the testcontainers pin regressed below 1.21.4 (Docker 29 needs
≥1.21.4). Per-algorithm selections and debugging:
`docs/PER_ALGORITHM_TEST_GUIDE.md`.

What the 170 tests pin, so you know what a failure means (headline
additions since 143: the sixth-review batch — ValidityCheckerTest ×19
[the six §4 gates, per-system fault witnesses, the consulted-metrics ↔
export_queries contract], KafkaJmxAgentTest ×3 [real broker serves the
pinned JMX names; version pinned across pom/cloud-init], the F45
deadline-polled HotStuff commit gate, the F47 pre-start fault-mark guard,
the F48 baseline-Spec pin; earlier headline additions since 114: the
4-node HotStuff formation — real client traffic
committed through BFT consensus, logs.py's own parse targets asserted on
every replica — then its remote golden + provider branch; the F33
image-presence gates; the logs.py-port analyzer with hand-computed
fixtures; the campaign layer — typed inventory, fault targeting, chunked
log transfer, upstream-client command pin, the MatrixRunner block/resume/
shuffle; the F31 pkill self-match fix and the F32 fail-closed CLI keys):
- `ArgParserTest` (6) — CLI contract: `--key value` pairs, bare `-v/--verbose`,
  fail-closed on a dangling key, duration>warmup guard.
- `EventLogTest` (3) + engine event tests — failover instrumentation:
  kill→first-commit gap recovered from a scripted stall (±150 ms on one
  clock), off-by-default, overflow drops loudly, no recovery ⇒ no number.
- `WorkloadEngineTest` (18) — the instrument itself: open-loop rate adherence,
  **coordinated-omission correction** (a scripted 1 s stall must charge ~100
  ops their queueing delay), drain accounting (no lost samples), buckets ==
  histogram **including the duration+5 boundary second** (F23: a commit the
  drain waited for must never vanish from the buckets), warmup flagging,
  dead-cluster ⇒ errors-not-data, **K=1000
  reused keyspace** (bounded + ~fully covered — the old unique-key bug
  measured 778k distinct keys over 778k ops), **D7 conflict routing**
  (realized fraction ≈ c at ≥5σ tolerance; c=0 ⇒ zero hot-key writes;
  invalid ratios rejected at construction), **Little's-Law self-consistency**
  (saturation: throughput × mean ≈ window — three independently measured
  quantities must agree or the instrument is broken).
- `LatencyRecorderTest` (4) — real HdrHistogram: percentiles within 0.1% on
  known samples; TRUE mean exposes skew p50 hides; warmup excluded from all
  statistics; **merging snapshots == pooling raw samples** (methodology §3).
- `KeyEncodingTest` (4) — per-driver key encodings: etcd path-style;
  Paxi numeric-only URL path (its API Atoi-parses the key); Kafka UTF-8
  int record key; CometBFT `k<id>=` kvstore prefix (the tx's ONLY '=').
- `CsvResultsWriterTest` (13) — results contract: every run-window second
  written zeros included, drain-buffer filtering, `error_rate` in the
  manifest, majority-error run may not claim `complete`, **conflict runs
  path-separated** (`.../size3/c10/...`) with `conflict_ratio` in the
  manifest, non-whole percents rejected (path rounding would merge cells),
  **avg is the true mean** (not the old p50 placeholder), **latency.hlog
  round-trips** (written, re-read, same count/percentiles), **manifest v2
  pins** params/environment/image/version, **config_hash deterministic +
  param-sensitive**, fault/failover fields null↔real correctly.
- `ClusterProviderCloseTest` (2) — teardown continues on failure but the
  failure is logged, never swallowed.
- `LocalDockerProviderTest` (9, integration, real Docker) — digest-pinned
  3-node etcd: quorum write; kill 1 → commits resume after re-election;
  kill 2 → writes fail (fail-closed); `stop()` leaves zero `thesis-*`
  containers; **KRaft size-1** (digest-pinned apache/kafka 3.9.1): healthy
  broker commits an acks=all produce, multi-node fails closed (P2.2a);
  **CometBFT size-1** (digest-pinned v0.38.17, kvstore app, subscription
  limit raised 100→2000): /health answers, teardown clean, multi-node
  fails closed (P2.3a); **Paxi size-3** (source-pinned build `paxi:6823d0b`
  — `docker build -t paxi:6823d0b infra/paxi` once): start() returns only
  after a committed probe write (election is lazy, no /health), size≠3
  fails closed, teardown clean (P2.4a).
- `LocalRunTest` (1, integration) — the full one-command loop twice in a
  row, including pre-clean of a planted leftover container.
- `KafkaDriverTest` (2, integration) — the production Kafka driver (P2.2b):
  committed acks=all write timed in the callback; the single broker maps to
  leader index 0; **dead broker → the write fails within delivery.timeout
  (5 s) + slack, asynchronously** (metadata warmed at connect, so `send()`
  never blocks the issue loop).
- `KafkaPerfTestParityTest` (1, integration, ~70 s) — the G1 flaw-B
  regression: harness saturation vs `kafka-producer-perf-test` exec'd
  inside the same broker, order-of-magnitude band (see the test javadoc
  for why 3x locally and 15% at G3/M6.1; last measured ratio 0.95x).
- `CometBftDriverTest` (2, integration, ~20 s) — the G1 flaw-A regression:
  committed `broadcast_tx_commit` writes (200 ≠ commit: both check_tx and
  tx_result codes must be 0), nonce-unique txs (mempool duplicate cache),
  **saturation >100 tx/s sustained** — an order-of-magnitude tripwire vs
  the ~6 tx/s blocking-client ceiling (the G1 ACCEPTANCE measured 602
  tx/s; 2026-07-16 the same code measured 297 under accumulated suite
  load, so the threshold discriminates the flaw class, not the machine's
  mood) with p50 ≈ the block interval.
- `FaultInjectorApplyTest` (6) — F13/F19 targeting pins: NETWORK_PARTITION
  isolates the DETECTED leader; PACKET_LOSS percent is a parameter;
  DOUBLE_KILL deterministic nodes 0+1; baseline touches nothing.
- `WorkloadEngineTest` also pins (P2.2 fallout) that the FIRST error's
  cause is kept on the Result — an error count with no cause is
  undebuggable.
- `PaxiDriverContractTest` (4) — P2.4b unit contracts: Ballot header
  ("n.zone.node") → leader node index; multi-zone or malformed ballots
  fail LOUD (never kill the wrong node); F24 endpoint strategy (PAXOS
  pins writes to one entry, EPAXOS round-robins).
- `PaxiDriverTest` (1, integration, ~3 s) — the production Paxos driver
  on real paxi:6823d0b: committed write; Ballot-header leader detection
  **corroborated through an independent stack AND entry** (raw JDK HTTP
  via a follower names the same leader); follower-kill → writes keep
  committing on the 2/3 majority. Leader-kill is deliberately absent
  (F26 — stock paxi has no failure detector; P3.3 preregisters it).
- `PaxiConflictSweepTest` (2, integration, ~19 s) — P2.4c: EPaxos driven
  by the engine round-robin over all three replicas (0 errors — a
  non-committing entry would surface as ~1/3 errors); the D7 c=10% run
  commits clean against the real cluster and carries its identity
  end-to-end (`.../epaxos/baseline/size3/c10/<runId>` + manifest
  `conflict_ratio: 0.10`, `status: complete`).
- `HotStuffSummaryTest` (4) — P2.5: the strict SUMMARY parser (HotStuff's
  ONLY metrics source): canonical fixture parses with thousands
  separators stripped; a missing field fails closed NAMING the field;
  zero or duplicate SUMMARY blocks are refused. Fixture is verbatim the
  upstream logs.py format (re-pin vs a real fab.log at Phase C).
- `SshExecutorContractTest` (3) — P3.3a: the recording executor (the G2
  golden-file substrate) records every command verbatim in order as
  `host:port$ command`; canned responses; `execOrThrow` fails closed
  naming the command AND the remote stderr; failures are recorded too.
- `RemoteSshProviderTest` (4) — P3.3b: the etcd remote provider's FULL
  command sequence (start+stop, size 3) compared VERBATIM against the
  reviewed golden (`src/test/resources/goldens/etcd-size3-start-stop.txt`
  — read its header checklist at G2); NodeHandle carries REAL private IPs
  (F20) and host-networked endpoints; unsupported systems and oversized
  clusters fail closed; the health gate fails closed NAMING the node.
- `RemoteSshProviderTest` also (P3.3d-paxi, +3) — the Paxos remote start
  sequence matched VERBATIM against `goldens/paxos-size3-start-stop.txt`
  (config.json with real private IPs via single-quoted printf, bind-mount,
  committed-probe-write gate); EPAXOS swaps only the `-algorithm` token;
  and the **F26 wedge** is pinned — paxi leader_kill is exactly one
  `docker kill` with nothing to heal (no failure detector).
- `SshFaultInjectorTest` (5) — P3.3c: each fault scenario's remote command
  sequence matched VERBATIM against per-scenario blocks in
  `goldens/etcd-size3-faults.txt` (read its network-invariant header at
  G2); netem shapes the iface RESOLVED from `ip -o route get` (never an
  assumed eth0); partition is pairwise node-IP DROP rules that never
  block the subnet/loadgen; heal is emitted even when injection throws
  (a persisted rule would corrupt every later run).
- `SshjExecutorTest` (1, integration, ~5 s) — the REAL sshj executor
  against a key-authenticated sshd container (digest-pinned): stdout
  verbatim, non-zero exit + stderr surfaced (never swallowed),
  fail-closed throw, pooled per-host connection reuse.
- `EtcdDriverTest` (3, integration) — the production jetcd driver:
  committed gRPC writes; leader detection **cross-validated against the
  HTTP-gateway stack** (two independent clients must agree); kill the
  detected leader → new leader found, writes continue on 2/3 quorum;
  **kill 2 of 3 → the write fails within the 5 s driver deadline** (never
  commits, never hangs the drain — F18); key encoding pinned identical to
  the fallback driver.

The engine tests take ~11 s and the Docker tests ~15-20 s (both run real
time). Total suite ≈ 35 s.

---

## 2. Smoke run against a real etcd (Docker), quiet and verbose (~2 min)

```bash
# 2.1 disposable single-node etcd
docker run -d --name smoke-etcd -p 2379:2379 quay.io/coreos/etcd:v3.4.30 \
  etcd --advertise-client-urls=http://0.0.0.0:2379 --listen-client-urls=http://0.0.0.0:2379
sleep 2 && curl -s http://127.0.0.1:2379/health
```

**Expect:** `{"health":"true"}`

```bash
# 2.2 quiet run (default INFO)
$JAVA -jar $JAR --rate 100 --duration 6 --warmup 2 --out /tmp/bench-smoke --run s1
```

**Expect — exactly 4 INFO lines, no DEBUG:**

```
17:02:52.718 [main] INFO gr.thesis.bench.Main - run=s1 system=etcd mode=100 ops/s open-loop duration=6s warmup=2s window=64 valsize=256
17:02:58.966 [main] INFO gr.thesis.bench.Main - committed(after warmup)=401 errors=0 throughput=100.3 ops/s
17:02:58.966 [main] INFO gr.thesis.bench.Main - latency us: p50=2400 p95=5696 p99=7744 p99.9=13440 max=13440
17:02:58.966 [main] INFO gr.thesis.bench.Main - results -> /tmp/bench-smoke/etcd/baseline/size1/s1
```

Checks: `committed ≈ rate × (duration−warmup)` (here 401 ≈ 100×4);
`errors=0`; throughput within ~2% of the target rate.

```bash
# 2.3 verbose run — same load, -v anywhere on the line
$JAVA -jar $JAR --rate 100 --duration 6 --warmup 2 --out /tmp/bench-smoke --run s2 -v
```

**Expect — the same 4 INFO lines PLUS phase-boundary DEBUG and a per-second
ticker** (`t=Ns committed=100 ops`), in this order:

```
DEBUG ... WorkloadEngine - phase: connect (ETCD driver)
DEBUG ... EtcdHttpDriver - phase: connect — pooled HttpClient for http://127.0.0.1:2379/v3/kv/put
DEBUG ... WorkloadEngine - phase: warmup start (mode=100 ops/s open-loop, duration=6s, warmup=2s, window=64)
DEBUG ... [bench-debug-reporter] - t=0s committed=100 ops
DEBUG ... [bench-debug-reporter] - phase: warmup end -> measurement window
DEBUG ... (one t=Ns line per second, ~100 ops each at this rate)
DEBUG ... WorkloadEngine - phase: load end, draining in-flight window
DEBUG ... WorkloadEngine - phase: run complete (401 committed after warmup, 0 errors)
DEBUG ... EtcdHttpDriver - phase: driver close
```

If the DEBUG lines are missing with `-v`, the slf4j binding in the shaded jar
is broken again (see PROJECT_STATE §3, the slf4j 1.7/2.x mediation bug).

```bash
# 2.4 inspect the results contract
cat /tmp/bench-smoke/etcd/baseline/size1/s1/manifest.json
head -3 /tmp/bench-smoke/etcd/baseline/size1/s1/throughput.csv
cat /tmp/bench-smoke/etcd/baseline/size1/s1/latency.csv
```

**Expect:** manifest v2 with `"duration_secs": 6`, `"warmup_secs": 2`,
`"ops_after_warmup": ~400`, `"errors": 0`, `"error_rate": 0.0000`,
`"status": "complete"` — plus `"environment": "local"`, `"image"` (the
digest for local-run, `null` for endpoint-run), `"harness_version"`
(0.1.0-SNAPSHOT from the jar, "dev" from classes), `"config_hash"`
(12 hex), `"rate_ops_s"/"window"/"value_size_bytes"`, and
`"fault_injected_at_ms"/"failover_ms"` (null on baseline runs);
throughput.csv rows `t,ops` for **every** second 0..5 (zeros included if
any); latency.csv with metric,value_us rows where `avg` is the TRUE mean
(typically avg > p50 — latency is right-skewed); and `latency.hlog` — the
full post-warmup histogram (HistogramLogWriter v1.3, starts with
`#consensus-bench post-warmup latency`).

```bash
# 2.5 fail-closed negative test: kill etcd, run again
docker rm -f smoke-etcd
$JAVA -jar $JAR --rate 50 --duration 4 --warmup 1 --out /tmp/bench-smoke --run dead1
grep -E "error_rate|status" /tmp/bench-smoke/etcd/baseline/size1/dead1/manifest.json
```

**Expect** — the harness must never fabricate data from a dead system:

```
committed(after warmup)=0 errors=201 throughput=0.0 ops/s
latency us: p50=0 p95=0 p99=0 p99.9=0 max=0
  "error_rate": 1.0000,
  "status": "failed",
```

```bash
# 2.6 fail-fast guard: empty measurement window is rejected before any work
$JAVA -jar $JAR --rate 100 --duration 5 --warmup 5 --out /tmp/bench-smoke --run bad1
```

**Expect:** `IllegalArgumentException: duration (5s) must exceed warmup (5s)`
(stack trace, non-zero exit, nothing written).

```bash
# 2.7 cleanup — must print 0
docker ps -aq --filter name=smoke-etcd | wc -l ; rm -rf /tmp/bench-smoke
```

### 2.8 The one-command local loop (`local-run`) — the P0 deliverable

Everything in §2.1–2.7 collapsed into one command: idempotent pre-clean →
fresh digest-pinned cluster → measured run → guaranteed teardown.

```bash
$JAVA -jar $JAR local-run --size 1 --duration 5 --warmup 2 --rate 100 \
  --out /tmp/bench-local --run cli1
```

**Expect — 5 INFO lines, ~8 s wall-clock total** (real capture):

```
19:15:53.346 [main] INFO gr.thesis.bench.Main - local-run run=cli1 system=etcd size=1 mode=100 ops/s open-loop duration=5s warmup=2s window=64 valsize=256
19:15:59.095 [main] INFO gr.thesis.bench.Main - committed(after warmup)=301 errors=0 throughput=100.3 ops/s
19:15:59.096 [main] INFO gr.thesis.bench.Main - latency us: p50=2464 p95=5824 p99=9344 p99.9=11904 max=11904
19:15:59.096 [main] INFO gr.thesis.bench.Main - results -> /tmp/bench-local/etcd/baseline/size1/cli1
19:15:59.413 [main] INFO gr.thesis.bench.Main - teardown complete — zero thesis-* containers remain
```

`--size 3` brings up a real 3-member quorum (results under `.../size3/`,
~8 s too). Add `-v` for phase boundaries + per-second ticker + container
lifecycle detail. Checks after any `local-run`:

```bash
docker ps -aq --filter name=thesis- | wc -l    # MUST print 0
```

Notes: a `testcontainers-ryuk-*` container may linger ~10 s after exit —
that is the cleanup reaper itself, not a leak. If a previous run crashed,
the next `local-run` logs `pre-clean: removed N leftover thesis-*
container(s)` and proceeds — no manual cleanup ever needed.

The D7 conflict knob (`--conflict 0.10` = 10% of ops on the shared hot key):

```bash
$JAVA -jar $JAR local-run --size 1 --duration 4 --warmup 1 --rate 200 \
  --conflict 0.10 --out /tmp/bench-c10 --run c10a
# expect: results -> /tmp/bench-c10/etcd/baseline/size1/c10/c10a   (note the c10 segment)
# and in its manifest.json:  "conflict_ratio": 0.10
# guard: --conflict 1.5 → IllegalArgumentException: conflictRatio must be in [0,1], got 1.5
```

**Important:** laptop numbers are *functional* evidence only, never
performance data (`environment=local`, shared CPU, single node) — absolute
latencies here mean nothing for the thesis.

---

## 3. Terraform — local verification, zero billing (~3 min)

```bash
# 3.1 one-time: local terraform binary (not installed system-wide)
mkdir -p ~/tools/terraform && cd ~/tools/terraform
curl -sSLo tf.zip https://releases.hashicorp.com/terraform/1.9.8/terraform_1.9.8_linux_amd64.zip
unzip -o tf.zip terraform && export PATH="$HOME/tools/terraform:$PATH"
terraform version   # expect: Terraform v1.9.8
```

```bash
# 3.2 init + validate (downloads hcloud/local providers from the registry)
cd $REPO/infra
terraform init -input=false
terraform validate
```

**Expect:** `Success! The configuration is valid.`
(If validate complains about `cloud-init.yaml`, the file is missing — it must
exist next to main.tf.)

```bash
# 3.3 dummy-token plans — CANNOT create resources, prove all 3 phase shapes.
# hcloud rejects tokens that are not exactly 64 chars, hence the printf.
ssh-keygen -q -t ed25519 -N "" -f /tmp/dummy_key
DUMMY=$(printf 'x%.0s' {1..64})

# Phase A (CFT defaults: 3x ccx13 + loadgen ccx13 + obs cpx21)
terraform plan -input=false -var hcloud_token=$DUMMY -var ssh_public_key_path=/tmp/dummy_key.pub
```

**Expect:** `Plan: 11 to add, 0 to change, 0 to destroy.` and
`hourly_cost_estimate_eur = "≈ €0.292/hour while up (5 VMs, ...)"`.

```bash
# Phase B (D8 scalability: 7 nodes)
terraform plan -input=false -var hcloud_token=$DUMMY -var ssh_public_key_path=/tmp/dummy_key.pub \
  -var consensus_node_count=7
# expect: Plan: 15 to add ... node7 with ip 10.0.0.17 ... ≈ €0.567/hour (9 VMs)

# Phase C (D9 BFT: 4x ccx23)
terraform plan -input=false -var hcloud_token=$DUMMY -var ssh_public_key_path=/tmp/dummy_key.pub \
  -var consensus_node_count=4 -var consensus_node_type=ccx23
# expect: Plan: 12 to add ... 4x server_type ccx23 ... ≈ €0.636/hour (6 VMs)

# Guardrail — MUST fail:
terraform plan -input=false -var hcloud_token=$DUMMY -var ssh_public_key_path=/tmp/dummy_key.pub \
  -var consensus_node_count=8
# expect: Error: ... consensus_node_count must be 1..7 (D8 ceiling; IPs 10.0.0.11-17).
```

Notes: `plan` writes no state and no inventory (both happen at apply);
`.terraform/` and the lock file are the only artifacts — the lock file is
meant to be committed. **Never run `apply` from this guide** (Gate G2).

---

## 4. Observability configs — promtool syntax proof (~1 min)

```bash
cd $REPO
# 4.1 scrape config
docker run --rm -v "$PWD/observability/prometheus.yml:/p.yml:ro" \
  --entrypoint promtool prom/prometheus:v2.53.0 check config /p.yml
```

**Expect:** ` SUCCESS: /p.yml is valid prometheus config file syntax`

```bash
# 4.2 every export query must parse as PromQL: wrap them as recording rules
python3 - <<'EOF'
lines = open('observability/export_queries.txt').read().splitlines()
rules = ["groups:", "- name: export_queries_parse_check", "  rules:"]
n = 0
for l in lines:
    l = l.strip()
    if not l or l.startswith('#'): continue
    name, q = [p.strip() for p in l.split('|', 1)]
    rules.append(f"  - record: check:{name}")
    rules.append(f"    expr: {q}")
    n += 1
open('/tmp/eq-rules.yml', 'w').write('\n'.join(rules) + '\n')
print(f"wrapped {n} queries")
EOF
docker run --rm -v /tmp/eq-rules.yml:/rules.yml:ro \
  --entrypoint promtool prom/prometheus:v2.53.0 check rules /rules.yml
```

**Expect:** `wrapped 22 queries` then `SUCCESS: 22 rules found`
(count grows as queries are added; any parse error names the bad query).

This proves *syntax* only — metric **names** (ZK :7000, Kafka JMX) are
verified against live exporters at M5.2 (task P4.3).

---

## 5. Green checklist

| # | Check | Command | Green means |
|---|-------|---------|-------------|
| 1 | Build + tests | `mvn21 clean verify` | `Tests run: 71+, Failures: 0` + `BUILD SUCCESS` |
| 0 | **One-command loop** | §2.8 `local-run` | complete manifest, ~8 s, zero `thesis-*` survivors |
| 2 | Quiet smoke | §2.2 | 4 INFO lines, `errors=0`, throughput ≈ rate |
| 3 | Verbose smoke | §2.3 | same INFO + phase DEBUG + per-second ticker |
| 4 | Results contract | §2.4 | manifest complete, `error_rate: 0.0000`, all seconds in throughput.csv |
| 5 | Fail-closed | §2.5 | dead etcd ⇒ `status: failed`, `error_rate: 1.0000`, zero fabricated data |
| 6 | Arg guard | §2.6 | duration ≤ warmup rejected before any work |
| 7 | No leaks | §2.7 | zero `smoke-etcd` containers |
| 8 | Terraform | §3 | validate green; plans show 11/15/12 resources; count=8 rejected |
| 9 | PromQL | §4 | config SUCCESS + all queries parse |

If all nine are green, the laptop-provable state of the project is healthy;
what remains *unprovable* locally (real VM boot, cloud-init semantics, real
scrapes) is exactly what the canary (P3.4) and M5.2 exist for.
