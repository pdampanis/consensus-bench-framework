# First manual deploy — one etcd cell on real VMs, by hand

**Purpose.** Do the whole thing once with your own hands, so the automated
canary later has nothing left to teach you. Every command is typed by you;
nothing in this file is run by an agent. `terraform apply` in particular is
the author's command and no one else's (CLAUDE.md hard rule).

**Cost.** Recommended shape is **n=3** (5 VMs). Prices below are the repo's
*unverified* figures — **S3 replaces them all** and is free.

| shape | VMs | €/h (unverified) | 3 h net | 3 h gross @24 % |
|---|---|---|---|---|
| n=2 (plan's canary) | 4 | ≈0.2255 | €0.68 | €0.84 |
| **n=3 (this runbook)** | 5 | ≈0.2951 | €0.89 | **€1.10** |

Arithmetic, so you can redo it after S3: `4 x ccx13 (3 nodes + loadgen) +
1 x cpx21 (obs) = 4(0.0689) + 0.016 = 0.2916`, plus 5 primary IPv4 at
≈€0.0007/h. This differs by a fraction of a cent from `PROJECT_STATE` §6
Phase 2's `€0.2230/h`; both are unverified, and **S3 supersedes both**.

**Why n=3 and not the plan's n=2.** Quorum on two nodes is two, so
`LEADER_KILL` at n=2 leaves *zero* quorum: the cluster commits nothing and
the "simulation" demonstrates a hang, not a failover. At n=3 the kill costs
one node, the survivors re-elect, and `throughput.csv` shows the gap and the
recovery — which is the thing you want to watch. n=3 also matches
`goldens/etcd-size3-start-stop.txt`, so the SSH sequence you see in the log
is diffable against the golden line for line. The extra cost is ≈€0.26.

**Hetzner bills a partial hour as a full hour.** The instant you apply, the
minimum bill is one hour. Set a phone timer for three hours.

---

## S0 — laptop tooling (free, no sudo)

Neither `terraform` nor `hcloud` is installed. Binaries into `~/bin`:

```bash
mkdir -p ~/bin && cd /tmp
TF_VER=1.9.8   # any >= 1.6; list at https://releases.hashicorp.com/terraform/
curl -fsSLO "https://releases.hashicorp.com/terraform/${TF_VER}/terraform_${TF_VER}_linux_amd64.zip"
unzip -o "terraform_${TF_VER}_linux_amd64.zip" -d ~/bin

HC_VER=1.49.0  # list at https://github.com/hetznercloud/cli/releases
curl -fsSL "https://github.com/hetznercloud/cli/releases/download/v${HC_VER}/hcloud-linux-amd64.tar.gz" | tar xz -C /tmp
mv /tmp/hcloud ~/bin/

export PATH="$HOME/bin:$PATH"
terraform version && hcloud version
```

## S1 — SSH key (free)

`~/.ssh/hetzner_thesis` does not exist yet. It is referenced by `main.tf:43`,
by `collect_block.sh:25`, and by the generated inventory.

```bash
ssh-keygen -t ed25519 -f ~/.ssh/hetzner_thesis -N ""
```

## S2 — API token (free)

Hetzner Console → your project → **Security → API tokens** → *Generate*,
**Read & Write**. Shown once.

```bash
export TF_VAR_hcloud_token='<paste>'
export HCLOUD_TOKEN="$TF_VAR_hcloud_token"
```

## S3 — price and type reality check (free, and it is a STOP gate)

Every euro in this repo was written down from a web page in July 2026 and
**never read back from Hetzner**. This step is the only authority.

```bash
hcloud server-type list
hcloud server-type list -o json | jq -r \
  '.[] | "\(.name)  \(.prices[0].location)  net=\(.prices[0].price_hourly.net)  gross=\(.prices[0].price_hourly.gross)"'
hcloud server-type describe ccx13 -o json | jq '.prices'
hcloud location list
hcloud pricing            # if your CLI version has it; the JSON above is the fallback
```

The JSON form is the reliable one — `prices` comes straight from the API's
`server_types` object and carries **net and gross per location**, so it
settles the VAT question too. `hcloud pricing` exists in recent CLI versions
only; `PROJECT_STATE` §6 Phase 1.2 names it, and if it is missing on your
version that is not a failure of the gate.

Reconcile against `infra/main.tf:97-103`:

```
ccx13 = 0.0689   ccx23 = 0.1378   ccx33 = 0.1923   cpx21 = 0.016
```

- **If `ccx13` is absent or repriced** — edit `local.hourly_eur` and the
  defaults before applying, then re-plan.
- **If `cpx21` is absent** (the handoff pack claims it was withdrawn) — the
  obs VM has no type. Pick the cheapest shared type that *does* exist and set
  `-var obs_type=<that>`. **Do not apply with an unpriced type**: `main.tf:196`
  scores a missing type as **0**, so the cost output silently under-reports.

## S4 — plan (free)

```bash
cd ~/Downloads/cbt-main/infra
terraform init
terraform validate
terraform plan -var consensus_node_count=3
```

**Expect `11 to add`** — the count is `6 + N_vms` (6 singletons: ssh key,
network, subnet, placement group, firewall, inventory file; plus one
`hcloud_server` per VM). `LOCAL_TESTING.md §3.3` records 11 at n=3, 15 at
n=7, 12 at n=4 by execution. A different number means the file is not what
you think it is — **stop and read the plan output** before spending anything.

---

## S5 — apply. Billing starts here. (author only)

```bash
terraform apply -var consensus_node_count=3
```

Immediately:

```bash
date -u                      # write this down
cat ../deploy/inventory.env  # (from infra/) Terraform GENERATED this; never hand-edit it
```

Set the three-hour timer now.

## S6 — wait for cloud-init on all five

Set these once and keep the shell for S6–S8 and S11:

```bash
REPO=~/Downloads/cbt-main
set -a; . $REPO/deploy/inventory.env; set +a
K=~/.ssh/hetzner_thesis
SSH="ssh -i $K -o StrictHostKeyChecking=accept-new"

for ip in $PUBLIC_NODE1 $PUBLIC_NODE2 $PUBLIC_NODE3 $PUBLIC_LOADGEN $PUBLIC_OBS; do
  echo "== $ip"
  $SSH root@$ip 'cloud-init status --wait; systemctl --failed --no-legend; \
                 docker ps --format "{{.Names}}"; \
                 for b in docker chronyc stress-ng xxd jq curl; do \
                   command -v $b >/dev/null || echo "MISSING $b"; done'
done
```

Every VM should show `status: done`, no failed units, and a running
`node_exporter`. `cloud-init.yaml` has never been booted for real — this is
where N-10 closes.

## S7 — bootstrap the loadgen (the three pieces nothing provisions)

The harness runs **on the loadgen**: it SSHes to `10.0.0.11-13`, which the
laptop cannot reach. Three things it needs are provisioned by nothing —
`cloud-init.yaml` installs no JRE, Terraform installs only the *public* key,
and no script ever copies the jar.

```bash
# 1. a Java 21 runtime (plus rsync, which collect_block.sh needs)
$SSH root@$PUBLIC_LOADGEN 'apt-get update -qq && \
  apt-get install -y -qq openjdk-21-jre-headless rsync && java -version'

# 2. the PRIVATE key — the harness authenticates loadgen -> nodes with it
scp -i $K $K root@$PUBLIC_LOADGEN:/root/.ssh/hetzner_thesis
$SSH root@$PUBLIC_LOADGEN 'chmod 600 /root/.ssh/hetzner_thesis'

# 3. the jar, the inventory, and the query set — all relative to CWD=/root
$SSH root@$PUBLIC_LOADGEN 'mkdir -p /root/deploy /root/observability'
scp -i $K $REPO/harness/target/consensus-bench-0.1.0-SNAPSHOT.jar root@$PUBLIC_LOADGEN:/root/bench.jar
scp -i $K $REPO/deploy/inventory.env            root@$PUBLIC_LOADGEN:/root/deploy/inventory.env
scp -i $K $REPO/observability/export_queries.txt root@$PUBLIC_LOADGEN:/root/observability/export_queries.txt
```

`RemoteRunner.java:388` reads `observability/export_queries.txt` **relative to
the working directory**, so the harness must be started from `/root`.
(Its javadoc says `collect_block.sh` stages the repo there; it does not —
that script only copies results *back*.)

Sanity: the private key works from loadgen to a node.

```bash
$SSH root@$PUBLIC_LOADGEN \
  "ssh -i /root/.ssh/hetzner_thesis -o StrictHostKeyChecking=accept-new root@$PRIVATE_NODE1 hostname"
```

## S8 — bring up observability by hand

`observability/prometheus.yml` ships with literal `PRIVATE_NODE1`
placeholders and a hardcoded `NODE4`; nothing substitutes them (N-5) and
nothing deploys the stack (N-16). Render it yourself — this is the step the
missing scripts will automate.

```bash
cat > /tmp/prometheus.yml <<EOF
global:
  scrape_interval: 5s
  evaluation_interval: 15s
scrape_configs:
  - job_name: node
    static_configs:
      - targets: ["$PRIVATE_NODE1:9100","$PRIVATE_NODE2:9100","$PRIVATE_NODE3:9100"]
        labels: { campaign: thesis, role: consensus }
      - targets: ["$PRIVATE_LOADGEN:9100"]
        labels: { campaign: thesis, role: loadgen }
      - targets: ["$PRIVATE_OBS:9100"]
        labels: { campaign: thesis, role: obs }
  - job_name: etcd
    static_configs:
      - targets: ["$PRIVATE_NODE1:2381","$PRIVATE_NODE2:2381","$PRIVATE_NODE3:2381"]
  - job_name: harness
    static_configs:
      - targets: ["$PRIVATE_LOADGEN:9400"]
EOF
```

The `role` labels are load-bearing: `export_queries.txt` selects on them, not
on instance names. The kafka-jmx / zookeeper / cometbft jobs are **omitted on
purpose** — only etcd runs today, and a job whose targets nothing listens on
is permanently `down`.

```bash
$SSH root@$PUBLIC_OBS 'mkdir -p /root/observability'
scp -i $K -r $REPO/observability/grafana $REPO/observability/docker-compose.yml root@$PUBLIC_OBS:/root/observability/
scp -i $K /tmp/prometheus.yml root@$PUBLIC_OBS:/root/observability/prometheus.yml
$SSH root@$PUBLIC_OBS 'cd /root/observability && docker compose up -d'
```

**The gate.** `etcd` targets are down until S9 starts the cluster, and
`harness` is up only *during* a run (`HarnessMetrics` opens :9400 per cell
and closes it with the cell). So the check here is the **node** job only:

```bash
$SSH root@$PUBLIC_OBS "curl -sf localhost:9090/-/ready && \
  curl -s 'localhost:9090/api/v1/targets?state=active' | \
  jq -r '.data.activeTargets[] | \"\(.labels.job) \(.labels.instance) \(.health)\"' | sort"
```

All five `node` targets must read `up`. **If any is down, stop and fix it** —
Prometheus is not decoration. With no metrics the exporter writes header-only
CSVs, the §4 meta-rule turns every empty series into a FAIL, every run is
`valid: false`, and `analyse.py` discards the whole session while it bills in
full (this is the N-16/N-5 campaign-killer, in miniature).

If a `node` target is down: Hetzner cloud firewalls filter the **public**
interface only, so the private network is not the cause. Check the exporter
itself — `$SSH root@<ip> 'docker ps; curl -s localhost:9100/metrics | head -1'` —
and that cloud-init actually finished on that VM (S6).

Grafana, if you want it — never expose it, tunnel it:

```bash
ssh -i $K -N -L 3000:localhost:3000 root@$PUBLIC_OBS   # admin / thesis-obs
```

---

## S9 — the first simulation: one etcd baseline cell

```bash
ssh -i $K root@$PUBLIC_LOADGEN
cd /root
java -jar bench.jar remote-run \
  --system etcd --scenario baseline --size 3 \
  --rate 300 --duration 240 --warmup 60 \
  --run r001 --inventory deploy/inventory.env -v 2>&1 | tee run-r001.log
```

240 s = 60 warmup + 180 measured. The campaign default is 480; this is
shorter on purpose — it is a first look, not data.

**Watch for, in order** — this is the whole orchestration, visible:

1. `remote-run ETCD BASELINE size=3 on nodes [10.0.0.11, .12, .13]`
2. a pre-clean `docker rm -f` per node, then **three** `docker run` lines —
   compare them against `harness/src/test/resources/goldens/etcd-size3-start-stop.txt:57-59`;
   they should differ only in the container-name suffix
3. three `/health` gates going green (quorum exists)
4. `phase: warmup` → `phase: measurement`, with a per-second reporter under `-v`
5. `remote-run complete: <N> committed after warmup, 0 errors -> results/...`

Then a fault run, and this is the one worth watching:

```bash
java -jar bench.jar remote-run \
  --system etcd --scenario leader_kill --size 3 \
  --rate 300 --duration 240 --warmup 60 --fault-at 120 \
  --run r002 --inventory deploy/inventory.env -v 2>&1 | tee run-r002.log
```

`--fault-at` counts from the engine's **t0** — `WorkloadEngine.java:111`
starts the event log *before* the warmup phase — so 120 with `--warmup 60`
puts the kill 60 s into the 180 s measured window, matching the campaign
default (`warmup + 60`). What F47 fixed is that `connect()` time no longer
shifts it; `RemoteRunner`'s javadoc calls t0 "MEASUREMENT start", which is
loose and worth a one-line correction. The log names
the detected leader container before killing it. Afterwards
`throughput.csv` shows the stall and the recovery with **zeros preserved**,
and `manifest.json` carries `fault_injected_at_ms` and `failover_ms`.

## S10 — read what it produced

```bash
find results -type f | sort
cat results/etcd/leader_kill/size3/r002/manifest.json | jq .
cat results/etcd/leader_kill/size3/r002/validity.json | jq '.gates[] | "\(.state) \(.gate) :: \(.detail)"'
head -5 results/etcd/leader_kill/size3/r002/throughput.csv
ls results/etcd/leader_kill/size3/r002/metrics/
```

Per run you should have: `throughput.csv`, `latency.csv`, `latency.hlog`,
`manifest.json`, `validity.json`, `metrics/*.csv` (23 files — `README.md:59`
and `LOCAL_TESTING.md:430` say 25 and are wrong), plus `logs/` and `events/`
on the fault run.

In `manifest.json` check: `environment` is **`hetzner`** (not `local`),
`status` is `complete`, the image digest is present, `error_rate` ≈ 0.

In `validity.json` expect several **PASS**, and note honestly which gates
**SKIP** — at n=3 with one SUT some are unreachable by construction. A metric
gate that FAILs means S8 is broken, not that etcd is.

## S11 — pull everything to the laptop, before destroy

```bash
# on the laptop
cd ~/Downloads/cbt-main
scripts/collect_block.sh $PUBLIC_LOADGEN $PUBLIC_OBS ~/thesis-data ~/.ssh/hetzner_thesis
```

It rsyncs `results/`, takes a Prometheus TSDB snapshot, and **count-verifies
manifests remote vs local**. It exits non-zero on a mismatch — that exit code
is the pre-destroy gate. Do not destroy until it is 0.

Also grab, by hand, the things that expire with the hardware:

```bash
for ip in $PUBLIC_NODE1 $PUBLIC_NODE2 $PUBLIC_NODE3 $PUBLIC_LOADGEN; do
  $SSH root@$ip 'echo "== $(hostname)"; nproc; lscpu | grep -E "Model name|Thread|MHz"; \
                 free -g | head -2; df -h /; chronyc tracking | head -6'
done
```

## S12 — analyse offline

```bash
python3 analysis/analyse.py ~/thesis-data/<timestamp>/results
```

Read `excluded.csv` first, not `cells.csv`. Every exclusion is listed with a
reason — that is the honesty rule, and on a first run the exclusions are the
interesting output.

Replay the metrics offline whenever you like:

```bash
SNAPSHOT_DIR=~/thesis-data/<timestamp>/prometheus-snapshot \
  docker compose -f observability/offline/docker-compose.yml up
```

## S13 — destroy, and prove it

```bash
cd infra && terraform destroy -var consensus_node_count=3
hcloud server list      # empty
hcloud primary-ip list  # empty — an orphan IPv4 bills after the servers are gone
hcloud network list
hcloud volume list
```

Then read the real cost in the console and write it into
`docs/CANARY_EVIDENCE.md` beside the estimate. **A forgotten cluster is the
only financial risk that matters**: this shape left up for a week is ≈€50.

---

## What this session is allowed to conclude

- **Yes:** the infrastructure boots, the orchestration works end to end
  against real VMs, the artifacts are the shape the analysis expects, and the
  fault injector does what it claims.
- **No:** none of these numbers are thesis data. Sizes, durations and rates
  here are chosen for cost, not for the methodology; the pilot (Phase 4)
  fixes the thresholds, and the campaign (Phase 5) produces the record.

## Feed back into the repo

Whatever you had to do by hand in **S7** and **S8** is exactly the content of
Phase 0.15 (`scripts/bootstrap_loadgen.sh`), 0.7 (`render_obs_config.sh`) and
0.6 (`deploy_obs.sh`). Keep the shell history; it is the specification.
