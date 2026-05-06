# Hetzner Deployment Guide — Step by Step

This guide walks you through creating 5 Hetzner Cloud servers, deploying the consensus benchmark suite, running the full campaign unattended, and collecting results. All deployment scripts live in `benchmark-consensus-algorithms/deploy/`.

## Cost Summary

Five CPX21 servers (3 vCPU, 4 GB RAM each) running for 60 hours (includes setup, campaign, and buffer) costs approximately €5–8 total. Hetzner bills hourly. You pay nothing after deleting the servers.

---

## Phase 1: Prepare Your Local Machine (5 minutes)

### 1.1 Generate an SSH key

```bash
ssh-keygen -t ed25519 -f ~/.ssh/hetzner_thesis -N ""
cat ~/.ssh/hetzner_thesis.pub   # copy this for Hetzner console
```

### 1.2 Edit inventory

```bash
cd benchmark-consensus-algorithms/deploy
cp inventory.env.template inventory.env
# Edit inventory.env: fill in the private IPs after creating servers
```

---

## Phase 2: Create the Servers (10 minutes)

### Option A: Hetzner CLI (recommended)

```bash
hcloud context create thesis
# Paste API token from https://console.hetzner.cloud/projects -> Security -> API Tokens

hcloud network create --name thesis-net --ip-range 10.0.0.0/16
hcloud network add-subnet thesis-net --type cloud --network-zone eu-central --ip-range 10.0.0.0/24

hcloud ssh-key create --name thesis-key --public-key-from-file ~/.ssh/hetzner_thesis.pub

for name in node1 node2 node3 node4 loadgen; do
  hcloud server create \
    --name "thesis-$name" \
    --type cpx21 \
    --image ubuntu-24.04 \
    --location fsn1 \
    --network thesis-net \
    --ssh-key thesis-key \
    --user-data-from-file deploy/cloud-init.yaml
done

# Get private IPs and fill them into inventory.env
for name in node1 node2 node3 node4 loadgen; do
  echo "$name: $(hcloud server describe "thesis-$name" -o json | jq -r '.private_net[0].ip')"
done
```

### Option B: Web Console

Repeat for 5 servers (node1, node2, node3, node4, loadgen):
- Type: CPX21 (3 vCPU, 4 GB, 40 GB NVMe)
- Location: Falkenstein (fsn1) — all servers same location
- Image: Ubuntu 24.04
- Networking: Enable public IPv4 + IPv6, create private network "thesis-net" with subnet 10.0.0.0/24
- SSH Keys: Paste `~/.ssh/hetzner_thesis.pub`
- Cloud config: Paste contents of `deploy/cloud-init.yaml`

After creation, note each server's private IP (10.0.0.x) and fill `deploy/inventory.env`.

---

## Phase 3: Setup the Cluster (15 minutes)

```bash
chmod +x deploy/*.sh
./deploy/setup_cluster.sh
```

This script: waits for cloud-init, configures /etc/hosts, uploads the thesis bundle to loadgen, builds custom Docker images, distributes them to all nodes, pulls third-party images, and verifies inter-node latency (should show 0.2–0.5 ms between nodes on the same private network).

---

## Phase 4: Manual Exploration (Optional, 30–60 minutes)

```bash
./deploy/run_manual.sh etcd        # Start etcd, see Raft in action
./deploy/run_manual.sh kraft       # Start KRaft, produce/consume messages
./deploy/run_manual.sh tendermint  # Start CometBFT, submit BFT transactions
./deploy/run_manual.sh paxos       # Start Paxos, see leader-based writes
./deploy/run_manual.sh epaxos      # Start EPaxos, see leaderless writes
./deploy/run_manual.sh hotstuff    # Start HotStuff, run a short benchmark
```

Each script prints copy-pasteable commands. Press Enter to tear down.

---

## Phase 5: Run the Full Campaign (Unattended, ~30 hours)

```bash
nohup ./deploy/run_campaign.sh > campaign.log 2>&1 &
# or: ./deploy/run_campaign.sh session1   (single session)
# or: ./deploy/run_campaign.sh kraft baseline  (single cell)
```

### Checking progress

```bash
./deploy/check_progress.sh
```

---

## Phase 6: Download Results (5 minutes)

```bash
./deploy/download_results.sh
# Results land in: results-hetzner-YYYYMMDD_HHMMSS/
```

### Run analysis

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install pandas numpy scipy matplotlib tabulate
python3 analysis/analyse.py --results-dir results-hetzner-* --out analysis/out
```

### Run visualizer

```bash
cd consensus-visualizer
RESULTS_DIR=$(pwd)/../results-hetzner-* docker compose up -d
# Open http://localhost:8090
```

---

## Phase 7: Delete the Servers (2 minutes)

```bash
for name in node1 node2 node3 node4 loadgen; do
  hcloud server delete "thesis-$name"
done
hcloud network delete thesis-net
```

Billing stops immediately.

---

## Troubleshooting

**"Permission denied" when SSHing**: Use `ssh -i ~/.ssh/hetzner_thesis root@<IP>`. Default Hetzner Ubuntu user is `root`.

**Cloud-init not finishing**: Wait 5 minutes. Check: `ssh -i ~/.ssh/hetzner_thesis root@<PUBLIC_IP> 'cat /root/.cloud-init-done'`

**setup_cluster.sh hangs**: Checks cloud-init completion for up to 5 min per node. If stuck, delete and recreate the problematic server.

**Campaign interrupted**: Idempotent. Completed cells (status=complete in manifest.json) are skipped on re-run. Just restart `./deploy/run_campaign.sh`.

**Container fails to start**: SSH to the node and check: `docker logs <container_name>`. Most common issues: missing /etc/hosts entry (re-run setup_cluster.sh) or insufficient memory (CPX21 has 4 GB — reduce `--memory` in lib.sh if needed).
