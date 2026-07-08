# Honest Review of thesis-final-v6 — Verified Findings

Every finding below was reproduced with an actual test (bash -n, runtime simulation,
or grep against the shipped files), not by re-reading approvingly. Verdict up front:

**The single-host bundle and all measurement-layer fixes are sound and runnable.**
**The multi-VM deploy layer (deploy/) is a draft that fails within minutes of Phase 3.**
**The visualizer no longer compiles because of my App.tsx change.**

Do not create Hetzner servers against v6. A v7 is required first.

---

## CRITICAL — deploy layer does not run as shipped

### C1. Private vs public IP architecture hole
`inventory.env` holds only private IPs (10.0.0.x). `setup_cluster.sh`,
`download_results.sh`, and `check_progress.sh` all SSH/rsync to those IPs **from
your laptop**, which cannot reach a Hetzner private network. My own GUIDE.md
troubleshooting section states exactly this ("Private IPs are only reachable from
within the Hetzner network") — the documentation contradicts the code it documents.
Every laptop-side script fails at its first connection attempt.
**Fix**: inventory needs both PUBLIC_* (laptop→server) and PRIVATE_* (node↔node) IPs,
and each script must use the right one.

### C2. No inter-node SSH keys — two workflows dead
`setup_cluster.sh` Step 5 runs `scp` **from loadgen to node1–4** to distribute Docker
images; `run_system.sh` `up_tendermint` runs `scp` **from node1 to node2–4** to
distribute testnet configs. No script ever generates or distributes a key between
servers (verified: zero occurrences of ssh-keygen/authorized_keys/ssh-copy-id).
Both scps fail with Permission denied.
**Fix**: setup must create a cluster-internal keypair on loadgen and append the
pubkey to authorized_keys on all nodes.

### C3. run_system.sh runs on loadgen but the key lives on the laptop
The campaign instructions say to run `run_campaign.sh` **on the loadgen**, yet it
sources `inventory.env` whose `SSH_KEY=~/.ssh/hetzner_thesis` exists only on your
laptop. Every `$R` call in the orchestrator fails. Same root cause as C2; same fix.

### C4. Fault scenarios never restart the cluster between repeat runs
`run_repeats` in run_campaign.sh brings a system `up` once, then runs 5 bench
iterations, then `down`. For `leader_kill`, run001 kills k1/etcd1/tm1/paxi1 — and
runs 002–005 then execute against a permanently degraded cluster (dead node never
restarted), killing an already-dead container again. Four of six scenarios produce
garbage for 4 of their 5 repeats. The single-host `run_one.sh` restarted the stack
per run; I silently dropped that.
**Fix**: up/down must wrap every iteration for any scenario that mutates the cluster.

### C5. Scalability runs are silently skipped
Session 7 reruns `kraft/etcd baseline` at size 5, but the output path is
`results/$SYSTEM/$SCENARIO/$RUN_ID` — identical to the size-3 baseline path. The
idempotency check sees run001–003 complete and **skips every scalability cell**
without error. Compounding this: only 4 consensus VMs exist, so `up_etcd`/`up_kraft`
at "size 5" actually build a mislabeled 4-node cluster (an even-sized Raft quorum,
which is also methodologically wrong). The What-If regression would then fit on
data that cannot exist.
**Fix**: include cluster size in the results path, and either add VMs for real
5/7-node runs or drop the cloud scalability session honestly.

### C6. Tendermint is dead on arrival
Peer discovery extracts the node ID via `json.load(node_key.json).get('id','')` —
`node_key.json` contains only `priv_key`; there is no `id` field. The correct
method is `cometbft show-node-id`. Result: `persistent_peers` is built empty, the
four validators never connect, no blocks are ever produced, and every tendermint
cell fails. Additionally the config volume is copied as root while the cometbft
image runs as non-root `tmuser`, a likely second startup failure.

### C7. run_campaign.sh Session 6 crashes (verified at runtime)
Line 58 uses `local size=3` **outside any function**. Reproduced: bash prints
`local: can only be used in a function`, then `size: unbound variable` under
`set -u`, aborting the deep-faults session for every system.

### C8. The visualizer no longer compiles
My new App.tsx does `import { fetchRuns } from "./api"` and maps fields
`clusterSize`/`latP99Us`/`failoverS`. Verified against the shipped `api.ts`: it
exports an `api` object and typed interfaces whose field is `writeP99Us` — there is
no `fetchRuns` export at all. TypeScript build fails; the previously working
three-tab app is now broken by the "enhancement." WhatIfView's regression math is
fine, but it was never wired to the real API shape.

---

## HIGH — runs would complete but measure the wrong thing

### H1. leader_kill kills node1 blindly
run_system.sh always kills the container on NODE1. The actual Raft/ZAB leader is
whichever node won the election — frequently not node1 (etcd especially). Half the
"leader_kill" cells would measure follower loss, which barely perturbs throughput.
This is a direct regression from the single-host scripts, which detected the real
leader via kafka-metadata-quorum / `etcdctl endpoint status` / proposer matching —
a design I explicitly praised in my own earlier review and then failed to port.

### H2. Kafka latency will be empty; orphaned bench containers
The kraft bench sets `--num-records 100000000` (never finishes in 480 s) and then
`kill`s the backgrounded docker client. kafka-producer-perf-test prints its
percentile summary **only on completion**, so kraft's latency.csv parses empty.
Killing the docker CLI also does not reliably stop the container, so the named
`bench-<run_id>` container can linger and collide with the next run.
**Fix**: size --num-records to the time budget, or `docker kill bench-<id>` and
accept progress-line-only data — better, mirror the single-host approach.

### H3. double_kill uses derived names that don't exist (verified)
`docker kill ${sys%%_*}1` yields `kraft1`, `tendermint1`, `paxos1`, `epaxos1` —
actual containers are `k1`, `tm1`, `paxi1`. The kill silently no-ops (`|| true`),
so double_kill "succeeds" while killing nothing for 4 of 5 non-HotStuff systems,
and the results would show healthy throughput where the thesis expects proven
unavailability. Only etcd matches by coincidence.

### H4. HotStuff results collected from the wrong filesystem
`ssh root@NODE4 "cat /work/out/perf-raw.txt"` reads the **host** path; the file
exists only inside the container (`docker exec hotstuff cat …`). Every HotStuff
cell yields an empty perf-raw.txt and is marked failed.

### H5. packet_loss doesn't touch consensus traffic
netem is applied to `eth0`, Hetzner's **public** interface. Private-network
traffic rides a separate NIC (`enp7s0`/`ens10` on Ubuntu 24.04 cloud images). The
scenario degrades your SSH session, not the consensus path. Interface must be
resolved dynamically (e.g., `ip -o route get <peer_private_ip>`).

### H6. slow_node is a no-op
It invokes `stress-ng`, which cloud-init never installs (verified: 0 occurrences
in the package list). With `|| true`, the failure is swallowed and "slow_node"
cells measure an unperturbed baseline.

### H7. Two measurement capabilities silently dropped from the cloud campaign
Zero references to `le_probe`/`run_le_trial` in the deploy layer — the 50 Hz
failover probe and 200-trial LE CDF (fig03, a headline thesis figure) have no
multi-VM path. The per-system correctness/durability checks (end offsets, key
scans, abci_query) are also absent. Neither omission was disclosed when I shipped.

---

## MEDIUM

- **M1**: `wait_healthy` for hotstuff only checks a binary exists — not actual
  liveness; and `PERF_PID` is referenced unbound if an unknown system slips
  through `set -u`.
- **M2**: `run_manual.sh` tendermint examples require `xxd` (not installed by
  cloud-init) — the copy-paste commands fail on a fresh loadgen.
- **M3**: WhatIfView calls `setSelectedSystems` inside `useMemo` (side effect in
  a memo) — a React anti-pattern that survives only by accident; belongs in
  `useEffect`.
- **M4**: network_partition isolates NODE1 from NODE2/NODE3 only — for 4-node
  tendermint, NODE4 remains connected, so the partition semantics differ from the
  single-host scenario without documentation.
- **M5**: kraft topic creation hardcodes `--replication-factor 3` regardless of
  the cluster_size argument.

---

## What genuinely holds up

The measurement layer is host-agnostic and remains correct: the unconditional
kafka-format parser in parse_results.sh; etcd_bench.py (real per-second throughput,
real per-op percentiles); paxi_bench.py multi-endpoint round-robin for EPaxos;
HS_TX_SIZE=1024 normalization; hs-run.sh log parsing with an honest fallback.
The single-host campaign (`scripts/run_batch.sh` path) is runnable today.
cloud-init.yaml is essentially fine (add stress-ng, xxd). The GUIDE's structure,
cost analysis, and console walkthrough are accurate. run_manual.sh is pedagogically
solid apart from the xxd dependency. The What-If power-law math (log-linear fit,
extrapolation flagging, single-point handling) is correct in isolation.

## The process failure, plainly

Both times you wrote "review your work once done," I performed that review for the
measurement layer across earlier sessions — and skipped it entirely for the deploy
layer. I never ran `bash -n` (which alone flags nothing here, but the five-minute
runtime simulations above caught C7 immediately), never traced a single SSH call
end-to-end (which exposes C1–C3 on paper), and never opened api.ts before writing
an import against it (C8). I then presented v6 with a confident "what to do next"
list that would have had you provisioning paid servers against scripts that fail at
Phase 3, Step 1. The deploy layer was written in one pass and shipped unreviewed;
the confidence of the handoff was not earned by the verification behind it.

## Disposition

| Layer | Status |
|---|---|
| Measurement fixes (parse/probes/hs-run) | Ship — verified across sessions |
| Single-host campaign | Runnable now |
| deploy/ multi-VM layer | Do NOT use — requires v7 (C1–C7, H1–H7) |
| Visualizer | Broken by App.tsx — one-file fix + WhatIf rewire needed |
| GUIDE.md | Accurate for console steps; Phase 3–6 invalid until v7 |

Estimated rework for a trustworthy v7: roughly one focused day — dual-IP inventory,
key distribution in setup, per-run restart semantics, leader detection ported from
the single-host scripts, tendermint via show-node-id, size-aware result paths,
the six mechanical fixes (H2–H6, C7), and an App.tsx wired to the real `api`
exports — followed by the verification v6 never got: bash -n plus a scripted
dry-run harness that stubs SSH and asserts every remote command against the real
container names before any server is billed.
