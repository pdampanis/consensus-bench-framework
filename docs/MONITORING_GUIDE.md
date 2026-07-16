# Monitoring & Observation Guide — for a human, step by step

This guide is for **watching the benchmark with your own eyes** — during
development on your laptop and (later) during the real campaign on the
cluster. It assumes no prior Prometheus/Grafana experience. Every command
here was run on 2026-07-16 and the "you should see" lines are real
captures.

**The one honest caveat, up front.** Two very different things exist in
this repo, and this guide keeps them separate so you are never fooled:

- ✅ **Real and runnable today** on your laptop: the harness, the Docker
  clusters it starts, the results files it writes, and a small
  **Prometheus + Grafana learning demo** (§5) you can bring up in two
  commands.
- 🔷 **Written but never run on a VM**: the whole cloud campaign — the
  Terraform, the remote SSH provider, the fault injector, the campaign
  Prometheus config. It is verified by *golden files and dry runs*, not by
  a real server yet. The first time any of it touches a VM is the **canary**
  (a tiny, <€0.10 test), and this guide's §7 is the checklist for that day.

Laptop numbers are **functional evidence only** — proof the machinery
works, never thesis performance data. The manifest tags them
`environment: local` and analysis throws them out. Keep that in mind every
time you read a latency number on your own machine.

---

## 1. The mental model — three layers, who produces what

```
  ┌─ YOU run one command ─────────────────────────────────────────┐
  │  java -jar consensus-bench.jar local-run --size 3 ...          │
  └───────────────┬───────────────────────────────────────────────┘
                  │
  LAYER 1  the harness (the INSTRUMENT — the numbers that matter)
     • starts a fresh Docker cluster, drives load, measures commit latency
     • prints a few INFO lines while it runs
     • writes a RESULTS TREE: throughput.csv, latency.csv, latency.hlog,
       manifest.json                                  → §4, §6
                  │
  LAYER 2  Docker (the SUBSTRATE — is the system even alive?)
     • `docker ps`, `docker logs`, `docker events`    → §3
     • locally this is containers on your laptop; on the campaign it is
       one container per VM
                  │
  LAYER 3  Prometheus + Grafana (the EXPLANATION layer — WHY the numbers)
     • per-node CPU / memory / disk / network / CPU-steal
     • protocol internals (etcd leader changes, CometBFT block height, …)
     • this is the pretty graphs layer                → §5
```

Rule of thumb: **Layer 1 tells you *what* happened, Layer 3 tells you
*why*.** If throughput dropped, Layer 1 shows the dip; Layer 3 shows the
CPU pegged at 100% or the leader-change counter ticking. You need both.

---

## 2. Tools you'll use (plain language)

| Tool | What it is | You use it to… |
|------|-----------|----------------|
| `docker ps` / `logs` / `events` | Docker's own status commands | see if containers are alive, read their output, catch restarts |
| `curl` | fetch a URL from the terminal | ask a node "are you healthy?" |
| the harness jar | the benchmark itself | run a measured load and write results |
| **Prometheus** | a database that scrapes numbers every 5 s and lets you query them | ask "what was the CPU at 14:32?" |
| **Grafana** | dashboards on top of Prometheus | *see* those numbers as graphs |
| `ss`, `ip`, `ping` | Linux network tools | debug "can node A reach node B?" (§8) |

You do **not** need to learn PromQL (Prometheus's query language) to start
— the demo dashboard (§5) has the queries built in. §5.4 teaches you to
read them.

---

## 3. Watching the substrate (Docker) — the first thing to check

Whenever something looks wrong, start here. If the container isn't alive,
no latency number means anything.

```bash
# What thesis containers are running right now?
docker ps --filter name=thesis- --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
```
**You should see** one row per node, e.g. `thesis-etcd1  Up 12 seconds`.
Empty output means either nothing is running (fine between runs) or a
start failed (bad).

```bash
# Read a node's own log — the system's side of the story
docker logs --tail 40 thesis-etcd1
```
**You should see** the system's startup chatter and, for etcd, lines about
raft and "published" membership. Errors here (address in use, "no such
host", permission denied) are the root cause of most "the harness hung"
reports.

```bash
# Did anything restart or die unexpectedly during a run?
docker events --since 10m --filter event=die --filter event=kill \
  --format '{{.Time}} {{.Status}} {{.Actor.Attributes.name}}'
```
**You should see** nothing during a healthy baseline run; during a fault
run you should see exactly the `kill` you injected (and nothing else). An
*unexpected* die is a validity problem — the campaign's validity gate 4
checks precisely this.

```bash
# After any run — MUST be zero leftover containers
docker ps -aq --filter name=thesis- | wc -l          # want: 0
```
A non-zero here means a crashed run left junk behind; the next `local-run`
cleans it automatically (and says so), but it's worth knowing.

---

## 4. Watching the harness (a run in progress)

Run with `-v` to see the phases and a per-second heartbeat:

```bash
java -jar target/consensus-bench-0.1.0-SNAPSHOT.jar \
  local-run --size 3 --rate 200 --duration 20 --warmup 5 -v
```

**You should see**, in order: `phase: deploy` → `phase: connect` →
`phase: warmup start` → a `t=Ns committed=… ops` line **every second** →
`phase: warmup end -> measurement window` → `phase: load end, draining` →
the summary. The per-second line is your live pulse: at `--rate 200` each
second should report ~200 committed ops. A second that suddenly reads `0`
is a stall — note the timestamp, then go look at Layer 3 for that moment.

The three summary lines at the end are the headline:
```
committed(after warmup)=3001 errors=0 throughput=200.1 ops/s
latency us: p50=2464 p95=5824 p99=9344 p99.9=11904 max=11904
results -> .../etcd/baseline/size3/<run>
```
- `errors=0` — good; any errors and the first cause is WARNed above.
- `throughput ≈ rate` — the load actually landed.
- `p50 < p99 < max` — a healthy right-skewed latency shape.

(Full expected-output checklist for every laptop command: `LOCAL_TESTING.md`.)

---

## 5. Prometheus & Grafana, made easy — the local learning demo

This is the fastest way to *understand what you're looking at*, with zero
cloud cost. It runs Prometheus + Grafana + a `node_exporter` (the thing
that measures CPU/mem/disk/net) **on your own laptop**, so the graphs are
your real machine.

### 5.1 Bring it up (two commands)

```bash
cd observability/local-demo
docker compose up -d
```
**You should see** three containers start. Give it ~10 seconds, then:

- **Grafana** → open <http://localhost:3000>  (login `admin` / `demo-only`)
- **Prometheus** → open <http://localhost:9090>

Tear it down when done: `docker compose down` (from the same folder).

### 5.2 In Prometheus (the raw layer)

Go to **Status → Targets**. **You should see** one target,
`localhost:9100`, with a green **UP**. That green is the whole game: it
means Prometheus is successfully scraping. (On the campaign, this page
lists every VM — a target that is **DOWN** is the #1 thing to check when a
node's graphs go blank.)

Now go to **Graph**, paste this, press Execute, pick the **Graph** tab:
```
1 - avg(rate(node_cpu_seconds_total{mode="idle"}[1m]))
```
**You should see** a line — your laptop's CPU-busy fraction (0 = idle,
1 = fully busy). Wiggle it by running something heavy; the line rises. That
is the entire idea of Prometheus: numbers over time you can query.

### 5.3 In Grafana (the pretty layer)

Grafana auto-loads a starter dashboard from the repo (dashboards-as-code —
the repo is the source of truth, not the browser). Open the menu
(top-left) → **Dashboards** → **Node Overview (health & validity)**.

**You should see** four panels updating live:

| Panel | What it shows | What "healthy" looks like |
|-------|---------------|---------------------------|
| **CPU busy %** | how loaded each node is | on a *consensus node* under load: high is fine. On the **loadgen**: must stay **< 70%** or the instrument itself is the bottleneck (validity gate 1) |
| **CPU steal %** | time the cloud stole from your VM | **~0** on dedicated vCPUs; any real spike = a noisy neighbour corrupting the measurement (validity gate 4) |
| **Memory used %** | RAM in use | flat is good; a steady climb across a long run hints at a leak |
| **Network rx/tx** | bytes in/out on the real NIC | steady during load; a **drop to zero** on one node during a fault run is what a kill/partition *looks like* |

Every panel has an ⓘ in its title — hover it for the same explanation
in-place.

### 5.4 Reading a graph without knowing PromQL

Three habits cover 90% of debugging:

1. **Look at the shape, not the number.** A flat line that suddenly steps
   up or drops to zero is the story. The absolute value matters less than
   *when it changed*.
2. **Line up the time.** If the harness reported a stall at `t=12s`, set
   the same time window in Grafana (top-right time picker) and look at what
   moved at that second. The whole point of Layer 3 is answering "what else
   happened at that moment?"
3. **Blank panel = broken pipe, not "zero".** An empty graph almost always
   means Prometheus lost the target (Status → Targets is DOWN), *not* that
   the value is genuinely zero. The campaign's validity rule is deliberately
   strict about this: **an empty metric series fails the run**, never passes
   — because an empty series usually means the measurement broke.

### 5.5 What the demo does NOT show (honest)

The demo only scrapes your laptop's node_exporter. It does **not** show
etcd/Kafka/CometBFT protocol internals — those come from per-system
exporters that only run on the campaign VMs (etcd's `:2381`, Kafka's JMX
agent, CometBFT's `:26660`; wired at milestone M5.2). The full campaign
query set lives in `observability/export_queries.txt`. So on the laptop,
Grafana teaches you the **node-health** panels; the protocol panels light
up only on the cluster.

---

## 6. After a test — reading the results tree

Every run writes a self-contained folder. Look at it:

```bash
RUN=results-local/etcd/baseline/size3/<yourRunId>
cat $RUN/manifest.json
head -5 $RUN/throughput.csv
cat $RUN/latency.csv
```

**`manifest.json`** is the run's identity card. The fields that matter:

| Field | Read it as |
|-------|-----------|
| `status` | `complete` = trustworthy; `failed` = the run errored out or lost majority — **do not use its numbers** |
| `error_rate` | fraction of ops that failed; `0.0000` on a clean run |
| `environment` | `local` = laptop, never thesis data; `hetzner` = the real cluster |
| `image` | the exact digest-pinned container that ran (reproducibility) |
| `config_hash` | same hash = same experiment; changes if any parameter changed |
| `fault_injected_at_ms` / `failover_ms` | `null` on baseline; on a fault run, when the fault hit and how long recovery took (`null` = never recovered — an honest absence, not a zero) |

**`throughput.csv`** is `t,ops` per second — every second of the run,
zeros included (a zero second is stall evidence, kept on purpose). Plot it
or just eyeball it: a flat ~200 with a dip is a fault; a ragged line is a
noisy machine.

**`latency.csv`** is the point summary (avg/p50/p95/p99/p99.9/max, in
microseconds). `avg` is the *true mean*, so `avg > p50` is normal (latency
is right-skewed).

**`latency.hlog`** is the full histogram — you don't read it by hand; it's
the input the analysis pools across runs (never average percentiles — the
methodology pools whole histograms).

---

## 7. Verifying the network topology — before / after deploy / after test

This is the part that killed the previous rewrite ("v6"): the network
looked fine on paper and failed at first contact. So verify it in three
stages. **Nothing below bills anything until you run `terraform apply`,
which is gated behind the G2 review — do not run it casually.**

### 7.1 BEFORE deploy (laptop, free) — is the plan sane?

```bash
cd infra
terraform plan -input=false -var hcloud_token=$(printf 'x%.0s' {1..64}) \
  -var ssh_public_key_path=/tmp/dummy_key.pub
```
**You should see** `Plan: 11 to add` (Phase A) and a cost line
`≈ €0.292/hour`. Confirm the IP scheme in the plan output: consensus nodes
`10.0.0.11–13`, loadgen `10.0.0.20`, obs `10.0.0.21`. (Full three-phase
walk-through with expected numbers: `LOCAL_TESTING.md` §3.) This proves the
*shape* is right; it cannot prove the VMs boot — that's §7.2.

The golden files are the other half of "before": open
`harness/src/test/resources/goldens/*.txt` and read the header checklist in
each. These are the **exact commands** the harness will run on each VM.
Reading them is the G2 gate — you are checking, e.g., that netem shapes the
*private* interface and partitions never block the whole subnet.

### 7.2 AFTER deploy (the canary day) — did the network actually form?

Once VMs exist, from the loadgen (or via SSH), confirm the private network
is real before trusting any measurement:

```bash
# from loadgen — can I reach every consensus node on the PRIVATE net?
for ip in 10.0.0.11 10.0.0.12 10.0.0.13; do
  ping -c1 -W1 $ip >/dev/null && echo "$ip reachable" || echo "$ip UNREACHABLE"
done

# which interface carries the private traffic? (this is what netem must shape)
ip -o route get 10.0.0.11        # note the "dev <iface>" — must be the PRIVATE nic

# clocks disciplined? (validity gate 6 needs < 5 ms offset)
chronyc tracking | grep 'System time'

# is every Prometheus target UP?  (open http://obs:9090/targets via SSH tunnel)
```
**You should see** all three `reachable`, a private `dev` (not the public
one), a sub-5 ms clock offset, and every target green. Any `UNREACHABLE`
means the private subnet or firewall is wrong — stop and fix before
spending a euro measuring.

### 7.3 AFTER a test — did the fault do what you think?

A fault run is only valid if the fault *actually happened*. Cross-check the
client story (Layer 1) against the ground truth (Layer 3):

```bash
# the harness said it injected at fault_injected_at_ms — did the node go quiet?
#   In Grafana, set the time window to ±60 s around that timestamp and look
#   at the Network panel: the killed node's rx/tx should flatline.
# And the protocol counter should move:
#   etcd:    etcd_server_leader_changes_seen_total  ticks up
#   CometBFT: cometbft_consensus_rounds             jumps
```
**You should see** the leader-change counter increment within the window.
If a run labelled `leader_kill` shows **no** leader change, the fault hit a
follower — the campaign reclassifies that run rather than averaging it in.
(Paxi is the honest exception: it has no failure detector, so its
`leader_kill` *wedges* with no recovery — that is the expected, documented
result, not a bug. See methodology §7.)

---

## 8. Network debugging cheat-sheet (when something can't talk)

Work outward from the node:

```bash
docker ps                       # is the container even up?
ss -ltnp | grep -E '2379|9092|8080|26657'   # is the port listening?
curl -sf http://127.0.0.1:2379/health       # does the service answer locally?
ip -o route get <peer_ip>       # which NIC routes to the peer?
ping -c1 <peer_ip>              # is the peer reachable at all?
sudo iptables -L -n --line-numbers          # is a partition rule still in place?
tc qdisc show dev <iface>       # is a netem (packet-loss/delay) still applied?
```

The last two are the ones that bite after a fault run: if `heal` failed,
an `iptables` DROP rule or a `tc` netem qdisc can survive and silently
corrupt the *next* run. That is why the fault injector emits `heal` in a
`finally` and logs loudly on any non-zero — but always confirm with these
two commands if a post-fault run looks strangely broken.

---

## 9. Quick reference — where each thing lives

| Want to… | Go to |
|----------|-------|
| exact laptop commands + expected output | `docs/LOCAL_TESTING.md` |
| what each metric means, per system | `docs/METRICS_AND_SOURCES.md` |
| the campaign PromQL set | `observability/export_queries.txt` |
| the local Grafana demo | `observability/local-demo/` (this guide §5) |
| the campaign obs stack (for the VMs) | `observability/docker-compose.yml` |
| how results become figures | `docs/DATA_ANALYSIS_METHODOLOGY.md` |
| what's built vs planned | `docs/PROJECT_STATE.md` |
| the exact remote commands (G2 review) | `harness/src/test/resources/goldens/` |
