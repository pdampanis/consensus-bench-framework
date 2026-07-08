# Hetzner Deployment Guide — Step by Step

This guide walks you through creating 5 Hetzner Cloud servers, deploying the consensus benchmark suite, running the full campaign unattended, and collecting results. No prior cloud experience is required.

## Cost Summary

Five CPX21 servers (3 vCPU, 4 GB RAM each) running for 60 hours (includes setup, campaign, and buffer) costs approximately €5–8 total. Hetzner bills hourly. You pay nothing after deleting the servers.

---

## Phase 1: Prepare Your Local Machine (15 minutes)

### 1.1 Generate an SSH key

Open a terminal on your local machine (Mac/Linux terminal, or Windows PowerShell):

```bash
ssh-keygen -t ed25519 -f ~/.ssh/hetzner_thesis -N ""
```

This creates two files: `~/.ssh/hetzner_thesis` (private key, never share) and `~/.ssh/hetzner_thesis.pub` (public key, paste into Hetzner). Display the public key and copy it to your clipboard:

```bash
cat ~/.ssh/hetzner_thesis.pub
```

### 1.2 Install the Hetzner CLI (optional but recommended)

The `hcloud` CLI lets you create servers from the command line instead of the web console. Install it:

```bash
# macOS
brew install hcloud

# Linux
curl -sL https://github.com/hetznercloud/cli/releases/latest/download/hcloud-linux-amd64.tar.gz | tar xz
sudo mv hcloud /usr/local/bin/
```

Create an API token in the Hetzner Console (top-right menu → API tokens → Generate API token with Read & Write). Then configure the CLI:

```bash
hcloud context create thesis
# Paste your API token when prompted
```

### 1.3 Prepare the thesis bundle

Ensure you have the thesis-final directory with all fixes applied. The `deploy/` directory should contain: `inventory.env`, `cloud-init.yaml`, `setup_cluster.sh`, `run_system.sh`, `run_campaign.sh`, `run_manual.sh`, `download_results.sh`, `check_progress.sh`.

Make all scripts executable:

```bash
chmod +x deploy/*.sh scripts/*.sh scripts/pumba/*.sh
```

---

## Phase 2: Create the Servers (10 minutes)

### Option A: Using the Hetzner CLI (recommended)

This creates all 5 servers in one go with the correct configuration:

```bash
# Create a private network first
hcloud network create --name thesis-net --ip-range 10.0.0.0/16
hcloud network add-subnet thesis-net --type cloud --network-zone eu-central --ip-range 10.0.0.0/24

# Upload your SSH key
hcloud ssh-key create --name thesis-key --public-key-from-file ~/.ssh/hetzner_thesis.pub

# Create 5 servers (CPX21: 3 vCPU, 4 GB, €~8/mo, hourly billing)
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
```

After creation, get the private IPs:

```bash
for name in node1 node2 node3 node4 loadgen; do
  echo "$name: $(hcloud server describe "thesis-$name" -o json | jq -r '.private_net[0].ip')"
done
```

### Option B: Using the Web Console

Repeat these steps 5 times (once for each server: node1, node2, node3, node4, loadgen):

**Step 1 — Type**: Select "Shared Resources", then "CPX21" (3 vCPUs, 4 GB RAM, 40 GB NVMe). Price should show approximately €7.99/month. Do NOT select CCX23 (that is the dedicated tier at €39/month — unnecessary).

**Step 2 — Location**: Select Falkenstein (fsn1). All 5 servers must be in the same location.

**Step 3 — Image**: Select Ubuntu 24.04.

**Step 4 — Networking**: Check "Public IPv4" (required for SSH access from your machine). Check "Public IPv6". **Check "Private networks"** and create a new network called "thesis-net" with subnet 10.0.0.0/24. Attach the server to it. This is the critical step — the private network is how your consensus nodes will communicate with each other.

**Step 5 — SSH Keys**: Click "Add SSH key", paste the contents of `~/.ssh/hetzner_thesis.pub`, name it "thesis-key".

**Step 6 — Volumes**: Skip (no additional storage needed).

**Step 7 — Firewalls**: Skip.

**Step 8 — Backups**: Skip.

**Step 9 — Placement groups**: Skip.

**Step 10 — Labels**: Skip.

**Step 11 — Cloud config**: Paste the entire contents of `deploy/cloud-init.yaml` into the "Cloud-init configuration" text box. This installs Docker and tunes the kernel on first boot.

**Step 12 — Name**: Enter "thesis-node1" for the first server. Repeat for "thesis-node2", "thesis-node3", "thesis-node4", "thesis-loadgen".

**Step 13**: Click "Create & Buy now". The server starts within 30 seconds. Repeat for all 5 servers.

After all 5 servers are created, go to each server's "Networking" tab in the console and note its Private IP address (10.0.0.x). These are the IPs you need for `inventory.env`.

### Fill in inventory.env

Edit `deploy/inventory.env` with the private IPs from the Hetzner console:

```bash
NODE1=10.0.0.2      # thesis-node1's private IP
NODE2=10.0.0.3      # thesis-node2's private IP
NODE3=10.0.0.4      # thesis-node3's private IP
NODE4=10.0.0.5      # thesis-node4's private IP
LOADGEN=10.0.0.6    # thesis-loadgen's private IP
SSH_KEY=~/.ssh/hetzner_thesis
```

Important: the IPs above are examples. Use the actual IPs shown in your Hetzner console for each server.

---

## Phase 3: Setup the Cluster (15 minutes)

Wait 3 minutes after creating the servers for cloud-init to finish, then run:

```bash
./deploy/setup_cluster.sh
```

This script connects to all 5 servers via SSH (using their **public** IPs for initial access), waits for cloud-init to complete, configures `/etc/hosts` for name resolution, uploads the thesis bundle to the loadgen, builds the 3 custom Docker images, and distributes them to all nodes. It finishes by verifying inter-node connectivity and printing the measured latency between each pair of nodes.

If you see latency values of 0.2–0.5 ms between nodes, the cluster is working correctly.

---

## Phase 4: Manual Exploration (Optional, 30–60 minutes)

Before running the full campaign, explore each algorithm interactively. SSH into the loadgen:

```bash
ssh -i ~/.ssh/hetzner_thesis root@<LOADGEN_PUBLIC_IP>
cd /root/thesis-final
```

Then run each algorithm's manual test:

```bash
./deploy/run_manual.sh etcd        # Start etcd, see Raft in action
./deploy/run_manual.sh kraft       # Start Kafka KRaft, produce/consume messages
./deploy/run_manual.sh tendermint  # Start CometBFT, submit BFT transactions
./deploy/run_manual.sh paxos       # Start Paxos, see leader-based writes
./deploy/run_manual.sh epaxos      # Start EPaxos, see leaderless writes
./deploy/run_manual.sh hotstuff    # Start HotStuff, run a short benchmark
```

Each script starts the system, prints copy-pasteable commands you can use to interact with it, and waits for you to press Enter before tearing down. This is where you build the intuition for what each protocol does — how writes propagate, what happens when a leader dies, how BFT differs from CFT.

---

## Phase 5: Run the Full Campaign (Unattended, ~30 hours)

SSH into the loadgen and start the campaign in the background:

```bash
ssh -i ~/.ssh/hetzner_thesis root@<LOADGEN_PUBLIC_IP>
cd /root/thesis-final
nohup ./deploy/run_campaign.sh > results/campaign.log 2>&1 &
```

You can now disconnect from SSH. The campaign continues running. `nohup` ensures the process survives SSH disconnection.

### Checking progress

From your local machine, at any time:

```bash
./deploy/check_progress.sh
```

Or SSH in and tail the log:

```bash
ssh -i ~/.ssh/hetzner_thesis root@<LOADGEN_PUBLIC_IP>
tail -f /root/thesis-final/results/campaign.log
```

---

## Phase 6: Download Results (5 minutes)

When the campaign is complete (check_progress.sh shows all cells complete), download everything to your local machine:

```bash
./deploy/download_results.sh
```

This copies the entire results directory to `results-hetzner-YYYYMMDD_HHMMSS/` on your local machine. Nothing is deleted from the server — you can download again if needed.

### Run the analysis locally

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install pandas numpy scipy matplotlib tabulate
python3 analysis/analyse.py --results-dir results-hetzner-* --out analysis/out
```

This produces `analysis/out/` with all figures, CSVs, and the markdown report.

---

## Phase 7: Delete the Servers (2 minutes)

After you have downloaded and verified your results, delete all servers to stop billing:

```bash
# Using the CLI
for name in node1 node2 node3 node4 loadgen; do
  hcloud server delete "thesis-$name"
done
hcloud network delete thesis-net

# Or: go to the Hetzner Console, select each server, click Delete
```

Billing stops immediately on deletion. Your total bill will be approximately €5–15 depending on how long the servers ran.

---

## Troubleshooting

**"Permission denied" when SSHing**: Ensure you are using the correct key file: `ssh -i ~/.ssh/hetzner_thesis root@<IP>`. The default user on Hetzner Ubuntu is `root`.

**Cloud-init not finishing**: Wait 5 minutes. Check with: `ssh -i ~/.ssh/hetzner_thesis root@<PUBLIC_IP> 'cat /root/cloud-init.log'`

**Cannot reach private IPs from local machine**: Private IPs (10.0.0.x) are only reachable from within the Hetzner network. Use the server's public IP for SSH. The scripts use private IPs for inter-node communication but public IPs for your SSH access.

**setup_cluster.sh hangs**: It waits up to 5 minutes per server for cloud-init. If a server is genuinely stuck, delete it from the console and create a new one.

**Campaign interrupted**: The campaign is idempotent. Completed cells (with status=complete in manifest.json) are skipped on re-run. Just restart `run_campaign.sh`.

**"No space left on device"**: CPX21 has 40 GB NVMe. The campaign produces approximately 1 GB of data. If Docker images consume too much space, run `docker system prune -f` on the affected node.
