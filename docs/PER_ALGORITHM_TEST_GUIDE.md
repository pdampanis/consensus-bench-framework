# Per-Algorithm Test & Debug Guide

How to run, debug, and read the tests and the benchmark **one algorithm at a
time**. Companion to `HOW_TO_CONTINUE.md` (the map) and `LOCAL_TESTING.md`
(whole-suite manual verification). Everything here is a real command against
this repo.

Conventions used throughout:

```bash
MVN=~/tools/maven/mvn21.sh          # Maven on JDK 21 (or your mvn21 alias)
cd harness
# Run one test class (or several, comma-separated):
$MVN -Dtest='KafkaDriverTest' -DfailIfNoTests=false test
# Whole suite + shaded jar (the only "done" signal):
$MVN clean verify
```

## 0. Where to look when ANYTHING fails

1. **Surefire reports** — `harness/target/surefire-reports/<Class>.txt`
   carries the assertion message; the project's tests are written to NAME
   the failing node/field/command in that message. Read it before anything
   else.
2. **Live containers during a test** — every harness container is named
   `thesis-*`:
   `docker ps -a --filter name=thesis-` then
   `docker logs <name>` (add `-f` to follow, `--tail 50` for the end).
   Formation tests tear down in `finally`, so to inspect a failure state,
   comment the `stop()` calls locally (never commit that) or grab logs in
   another terminal while the test sleeps.
3. **Leftovers after a crash** — `docker ps -aq --filter name=thesis- |
   xargs -r docker rm -f` (exactly what pre-clean runs).
4. **Verbose harness runs** — add `-v` to any jar command: DEBUG phase
   boundaries (deploy / wait-healthy / connect / warmup / fault / teardown)
   plus a per-second committed counter.
5. **A run's own evidence** — every run dir has `manifest.json`
   (`status`, `error_rate`, `firstError` is WARNed in the log),
   `throughput.csv` (zero rows = stall evidence), `latency.csv/.hlog`;
   fault runs add `logs/<container>.log` (full SUT logs, chunk-collected).
6. **Laptop-margin failures (F27)** — the two flaw tripwires are
   order-of-magnitude bands on purpose; if a perf assertion fails on a
   loaded laptop, check `uptime`/thermals and re-run the single class
   before suspecting a regression. Cluster numbers are the thesis data,
   laptop numbers never are.
7. **Expected vs observed, per algorithm** — before judging ANY number,
   read `OBSERVABILITY_AND_EXPECTATIONS.md`: the preregistered baseline
   per system with its corpus anchor, the dashboard reading guide, the
   false-positive catalogue, and the cleanup checklist (§7 there: what to
   remove after Docker runs, what the VMs clean themselves).
8. **Sample data** — `docs/examples/` holds a real (tiny, laptop) run and
   two labeled synthetic shapes, field-by-field explained; run
   `python3 analysis/analyse.py --selftest` and then point it at the
   sample to see the whole analysis path before any campaign.

## 1. etcd (Raft)

**Images**: pulled automatically (digest-pinned `quay.io/coreos/etcd v3.4.30`).

**Test selection**:
```bash
$MVN -Dtest='EtcdDriverTest,EtcdHttpDriverTest,LocalDockerProviderTest,LocalRunTest' test
# SSH layer (no Docker needed — golden dry-runs):
$MVN -Dtest='RemoteSshProviderTest,SshFaultInjectorTest' test
```

**Checklist — tests (what green proves)**:
1. `EtcdDriverTest` — jetcd commits against a real 3-node quorum; the
   DETECTED leader is cross-validated against the independent HTTP stack;
   killing that leader re-elects within 30 s and writes continue on 2/3;
   a quorum-lost write FAILS within the 5 s bound (F18) — never hangs.
2. `LocalDockerProviderTest` — kill 1 of 3 → commits resume; kill 2 of 3 →
   writes fail (fail-closed, no fabricated commits); zero leftovers.
3. `RemoteSshProviderTest#etcd*` — the recorded SSH sequence matches
   `goldens/etcd-size3-start-stop.txt` verbatim.

**Debug**: `docker logs thesis-etcd1-<suffix>`; the /health gate answers
only once quorum exists — a stuck wait-healthy on size 3 means peers can't
reach each other (network/alias problem), not a slow etcd.

**Checklist — benchmark (remote cell)**:
```bash
java -jar target/consensus-bench-*.jar remote-run --system etcd --scenario baseline \
  --size 3 --rate 300 --inventory ../deploy/inventory.env --run r001 -v
```
1. Log shows: pre-clean per node → 3 docker runs → 3 health gates → connect.
2. `manifest.json`: `environment=hetzner`, image digest present,
   `error_rate 0.0000`, `status complete`.
3. Sanity magnitudes (LAN, ccx13): p50 in single-digit ms; achieved ≥99% of
   target rate (validity gate 1).
4. leader_kill cell: log names the detected leader container; failover_ms
   sub-second-to-seconds in the manifest; throughput.csv shows the gap and
   recovery (zeros preserved).
5. Prometheus corroboration: `etcd_server_leader_changes_seen_total`
   increments within ±60 s of `fault_injected_at_ms`.

## 2. KRaft (Kafka, Raft mode)

**Test selection**:
```bash
$MVN -Dtest='KafkaDriverTest,KafkaPerfTestParityTest,KraftMultiBrokerFormationTest' test
```

**Checklist — tests**:
1. `KafkaDriverTest` — acks=all commit in the send callback; dead broker →
   asynchronous failure < 8 s (delivery.timeout 5 s, F18); partition-0
   leader mapped by endpoint or THROWS (never kills the wrong node).
2. `KafkaPerfTestParityTest` — order-of-magnitude band vs
   `kafka-producer-perf-test` (the G1 flaw-B tripwire); its javadoc carries
   the laptop pressure-diagnosis rule — read it before doubting a ratio.
3. `KraftMultiBrokerFormationTest` — 3 combined-mode brokers, quorum,
   acks=all under min.insync.replicas=2, Isr=3 (the execution-verified
   shape the remote golden encodes).

**Debug**: broker container: `docker logs thesis-k1… | grep -E 'started|ERROR'`;
KRaft mode logs `Kafka Server started (KafkaRaftServer)`. Quorum oracle by
hand: `docker exec thesis-k1 /opt/kafka/bin/kafka-broker-api-versions.sh
--bootstrap-server <ip>:9092 | grep -c '(id:'` — must equal cluster size
(2 of 3 serves acks=all silently degraded; the gate refuses it).

**Checklist — benchmark**: as etcd, plus:
1. Endpoints are BARE `ip:9092` (bootstrap contract).
2. Cluster state dies with containers (fixed KAFKA_CLUSTER_ID, no volume) —
   a rerun must format fresh storage, byte-identical behavior.
3. leader_kill targets the bench-topic partition-0 leader (stated in the
   preregistration; on multi-partition spread this is ONE partition's
   leader).

## 3. Kafka+ZooKeeper (ZAB) — D10 colocated

**Test selection**:
```bash
$MVN -Dtest='KafkaZkColocatedFormationTest,KafkaDriverTest' test
```

**Checklist — tests**:
1. `KafkaZkColocatedFormationTest` — zookeeper:3.9 ensemble from
   ZOO_MY_ID/ZOO_SERVERS with Prometheus on :7000 (znode_count served —
   P4.3's names); brokers run the SAME kafka image digest as KRaft in ZK
   mode by BYPASSING the entrypoint (printf'd server.properties +
   kafka-server-start.sh) — F6 stays identical-binaries; ZK mode logs
   `started (kafka.server.KafkaServer)`, never KafkaRaftServer.
2. Remote golden `kafka_zk-size3-start-stop.txt`: TWO containers per VM,
   teardown brokers FIRST then the ensemble.

**Debug**: `docker logs thesis-zk1` for ensemble state
(`curl <ip>:7000/metrics | grep znode_count` proves the scrape target);
broker wrong-mode failures show as `KafkaRaftServer` in logs or the
entrypoint's "Formatting is only supported for clusters in KRaft mode"
(means the entrypoint bypass regressed).

**Checklist — benchmark**: as KRaft, plus:
1. The fault handle is the BROKER; the colocated ZK survives a kill — the
   D10 comparison's semantic (check both containers per VM: `docker ps`).
2. ZK scrape job up on :7000 for all three nodes before measuring.

## 4. CometBFT (Tendermint)

**Test selection**:
```bash
$MVN -Dtest='CometBftDriverTest,CometBftMultiValidatorFormationTest' test
```

**Checklist — tests**:
1. `CometBftDriverTest` — HTTP 200 ≠ commit: success requires no JSON-RPC
   error AND check_tx.code==0 AND tx_result.code==0; nonce rides as ASCII
   hex (kvstore splits on '='); flaw-A tripwire ≥100 tx/s (G1 acceptance
   was 602 — 100x the retired probe's ceiling).
2. `CometBftMultiValidatorFormationTest` — 4 validators from DISTRIBUTED
   files (testnet keygen one-shots, four small JSONs per node,
   persistent_peers via CLI flag excluding self, CMTHOME not --home);
   n_peers=3; a tx commits through 3-of-4 precommits.

**Debug**: `curl -s <ip>:26657/status | jq .result.sync_info` — height
stuck at 0 with RPC up = fewer than 2/3 validators signing (key/genesis
mismatch); `net_info` for n_peers. `max_subscription_clients` must be 2000
(the provider seds it; at the default 100 you lose ~60% of in-flight
broadcast_tx_commit calls — measured).

**Checklist — benchmark**:
1. p50 ≈ block interval (~1 s) — structural, stated with every figure.
2. Window ≥ its floor: throughput caps at window/latency (Little's Law);
   the remote default window 200 at ~1 s latency caps at ~200 tx/s — raise
   `--window` (600 was the local proof) before calling a ceiling a result.
3. Faults: proposer rotates — replica 0 is the documented target.

## 5. Paxos / EPaxos (Paxi)

**Images**: build once per machine: `docker build -t paxi:6823d0b infra/paxi`.
On VMs: ship it (`docker save paxi:6823d0b | ssh root@<node> docker load`) —
the provider's F33 gate refuses to start without it, naming the node.

**Test selection**:
```bash
$MVN -Dtest='PaxiDriverContractTest,PaxiDriverTest,PaxiConflictSweepTest' test
```

**Checklist — tests**:
1. `PaxiDriverContractTest` — Ballot-header parsing ("n.zone.node", the
   node IS the leader), fail-loud on multi-zone/malformed; PAXOS pins
   endpoint 0, EPAXOS round-robins (F24).
2. `PaxiDriverTest` — real cluster: committed write; leader corroborated
   via an independent raw-HTTP stack through a FOLLOWER; follower-kill →
   commits continue on 2/3. Leader-kill is deliberately NOT tested locally
   (F26 wedge — see benchmark below).
3. `PaxiConflictSweepTest` — EPaxos driven round-robin (0 errors) and the
   D7 c=10% identity end-to-end (`…/c10/` path + manifest field).

**Debug**: paxi logs are terse; election is LAZY — the only readiness
signal is a committed probe write (`curl -sf -X PUT --data-binary v
http://<ip>:8080/1` → 200). No /health, no /state (F22 — do not chase
them).

**Checklist — benchmark**:
1. Baseline runs commit via node 1's endpoint (PAXOS) / all three (EPAXOS).
2. **leader_kill = THE WEDGE (F26, preregistered)**: expected result is
   NO recovery — writes fail at the 5 s bound until the run ends;
   `failover_ms: null` is CORRECT DATA (paxi ships no failure detector —
   an implementation property, contrasted with Raft's sub-second
   re-election). Do not "fix" it.
3. D7 sweep only for this pair: `--conflict 0.02` / `0.10` → `c2`/`c10`
   path segment, realized fraction = c by construction.

## 6. HotStuff

**Images**: `docker build -t hotstuff:dc01ac8 infra/hotstuff` (rust 1.85
build, ~minutes; image id 8501e107d4bf is the reproducibility anchor). Ship
to the four BFT nodes AND the loadgen (client runs there).

**Test selection**:
```bash
$MVN -Dtest='HotStuffSummaryTest,HotStuffLogAnalyzerTest,HotStuffMultiNodeFormationTest' test
```

**Checklist — tests**:
1. `HotStuffSummaryTest` — the strict SUMMARY parser: missing field →
   named error; duplicate blocks refused.
2. `HotStuffLogAnalyzerTest` — the logs.py port: hand-computed TPS/latency
   from fixture timestamps; zero commits REFUSED (never an all-zero row);
   client Error / node panic / missing config line / misaligned sample →
   loud failures.
3. `HotStuffMultiNodeFormationTest` — 4 nodes on static IPs commit real
   client traffic; every replica logs `Committed B<n> -> …=` (logs.py's own
   regex); the sample-tx path exists (e2e latency computable).

**Debug**: node: `docker logs thesis-hs<i> | grep -E 'booted|Committed|panic'`;
client: `docker logs thesis-hs-client | grep -E 'Start|rate|Error'`. A
booted-but-not-committing committee is a committee.json mismatch (keys vs
addresses) — regenerate, never hand-edit. Committee addresses are IPs ONLY
(Rust SocketAddr — a hostname will not parse).

**Checklist — benchmark** (`remote-run --system hotstuff --rate <R>`):
1. BASELINE only (fault scenarios preregistered, not implemented).
2. `--rate` is REQUIRED (the upstream client is fixed-rate; sweep it).
3. Output = `summary.txt` (validated) + `logs/` (raw evidence) +
   `manifest.json`; NO throughput.csv/hlog — its logs ARE its metrics,
   every figure carries the caveat.
4. First VM run must confirm the live -vv lines match logs.py's regexes at
   dc01ac8 (config lines, `Start `, sample tx, Committed) — the analyzer
   fails closed naming whatever is missing.
5. `rate too high` in the client log = the offered rate exceeded what the
   client could push — lower it or the run undercounts (logs.py warns the
   same way).

## 7. SSH layer + campaign plumbing (system-independent)

```bash
$MVN -Dtest='SshjExecutorTest,SshExecutorContractTest,SshFaultInjectorTest,FaultInjectorApplyTest' test
$MVN -Dtest='InventoryTest,RemoteLogsTest,RemoteRunnerTest,MatrixRunnerTest' test
```
Pins: real-sshd exec semantics (incl. the F28 backgrounding shape and its
<5 s bound), fault targeting (detected leader; replica 0 only for
EPaxos/CometBFT — documented, not guessed), golden verbatim-match for every
system, inventory fail-closed parsing, chunked log transfer (2 MB window),
and the matrix executor (seeded shuffle, manifest-resume,
failure-continues, dry-run). Block preflight on the loadgen:
`java -jar consensus-bench.jar campaign-run --system etcd --scenarios
baseline,leader_kill --rates 300,600 --reps 5 --dry-run` prints the
ordered cell list without executing anything.

## 8. Canary first-contact checklist (P3.4 — before any full phase)

1. `hcloud server-type list` → sync prices in `main.tf` (P3.5).
2. `terraform apply` (2 VMs variant) — G2 must be signed off FIRST.
3. cloud-init done? `cloud-init status --wait`; images pre-pulled
   (`docker images`); chrony synced (`chronyc tracking`).
4. Ship paxi/hotstuff images if this canary touches them.
5. One etcd baseline cell via remote-run; then one leader_kill cell.
6. VERIFY on the VM: `sudo tc qdisc show`, `sudo iptables -L` clean after
   heal; pkill heal WARN noise is EXPECTED once per slow_node (nothing to
   undo after --timeout) but must not appear as a channel error (F31).
7. rsync results to the laptop, `terraform destroy`, `hcloud server list`
   empty.
