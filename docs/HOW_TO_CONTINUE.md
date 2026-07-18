# HOW TO CONTINUE — one page, low noise

State date: 2026-07-18. Suite: one batched `mvn21 clean verify` at session end
(user-authorized deviation from per-increment runs — see PENDING_TASKS ledger).
Detail lives in `PROJECT_STATE.md` / `PENDING_TASKS.md`; per-algorithm test &
debug commands live in `PER_ALGORITHM_TEST_GUIDE.md`. This page is the map.

## The ladder

```
 DONE                                          OPEN (in order)
 ─────────────────────────────                 ──────────────────────────────────
 P0  local one-command loop        ┌────►  1. G2 golden read-through (HUMAN)
 P1  measurement instrument        │       2. Ship local images to VMs
 P2  all drivers, G1 signed off    │       3. terraform apply + P3.4 canary
 P3.3 SSH layer: 7/7 systems ──────┘       4. remote-run smoke per system
      golden-served + faults               5. Full-matrix runner (M3.3-full)
 M3.3-core remote-run CLI                  6. P4 validity + observability
 HotStuff analyzer (logs.py port)          7. G3 cross-validation
                                           8. Campaign A→B→C, then analysis
 laptop ──[jar]──► loadgen VM ──ssh──► node VMs (SUT containers)
                        │                       ▲ faults (tc/iptables/kill)
                        └──── results CSVs ─────┘   Prometheus on obs VM
```

## Numbered items + status

1. **G2 read-through** — OPEN, blocks everything billed. Author reads all 7
   goldens in `harness/src/test/resources/goldens/` (headers carry the
   checklists): etcd, etcd-faults, paxos, kraft, kafka_zk, tendermint(4),
   hotstuff(4). Sign off in PENDING_TASKS.
2. **Ship local-built images** — OPEN. `paxi:6823d0b` + `hotstuff:dc01ac8`
   exist in no registry (F33). After apply:
   `docker save paxi:6823d0b | ssh root@<node> docker load` (each node; same
   for hotstuff on the BFT phase; hotstuff also to loadgen for the client).
   Registry images pre-pull at boot via cloud-init.
3. **Canary (P3.4)** — OPEN. 2 VMs, one etcd cell end-to-end, < €0.10.
   First-contact checks listed in PER_ALGORITHM_TEST_GUIDE §canary
   (sudo, tc/iptables wording, pkill heal WARN noise, cloud-init).
   Run P3.5 first: `hcloud server-type list` → sync prices in main.tf.
4. **remote-run smoke per system** — OPEN. One short cell each on Phase-A
   infra; the numbered per-system checklists are in the guide.
5. **Full-matrix runner** — OPEN, deliberately thin: loop remote-run specs
   (randomized order, n=5, resume-on-manifest-present). LLM-ready plan in
   PENDING_TASKS "M3.3-full".
6. **P4** — OPEN: ValidityChecker (six gates), PrometheusExporter,
   ZK/JMX metric-name pinning, dashboards. P4.5 log capture: DONE for fault
   runs + HotStuff (RemoteLogs), docker-events audit still open.
7. **G3** — OPEN: harness vs native tools on the real cluster (≤15% or
   explained; the parity test's pressure-diagnosis rule applies).
8. **Campaign + analysis** — OPEN: phases A→B→C per CAMPAIGN_RUNBOOK,
   destroy between phases; then analyse.py v2 + 8 figures (F15: vendor the
   old analyse.py/visualizer first).

## HotStuff remote runs (the odd one out)

`remote-run --system hotstuff` starts the 4 nodes, runs the UPSTREAM client
on the loadgen (its own load generator = the measurement boundary), collects
all logs whole (chunked — sshj's 2 MB window), and ports logs.py exactly to
produce `summary.txt` (validated by the strict parser). BASELINE only;
fault scenarios preregistered in PENDING_TASKS. Its logs ARE its metrics —
no per-second CSV, no histogram; every figure says so.

## Session rules that survive every handoff

- Execute, don't assert; TDD; one increment per step; honest review; gates
  G1(done)/G2/G3 — G2's read-through is manual on purpose.
- Never `terraform apply` before G2. Laptop numbers are never thesis data.
- Update PROJECT_STATE + PENDING_TASKS at session end; commit and push.
