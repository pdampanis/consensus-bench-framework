#!/usr/bin/env bash
# =============================================================================
# setup_cluster.sh — one-time cluster setup for Hetzner consensus benchmark
# =============================================================================
#
# Prerequisites:
#   1. 5 Hetzner CPX21 VMs created with cloud-init.yaml
#   2. inventory.env filled in with private IPs and SSH key path
#   3. All VMs in the same private network (thesis-net, 10.0.0.0/24)
#
# What this script does:
#   1. Waits for cloud-init to complete on all 5 nodes
#   2. Configures /etc/hosts on every node for container name resolution
#   3. Creates a Docker network on the loadgen for benchmark clients
#   4. Uploads the thesis bundle to the loadgen
#   5. Builds custom Docker images (paxi, hotstuff, le-probe) on the loadgen
#   6. Distributes custom images to all consensus nodes
#   7. Pulls third-party images on all nodes
#   8. Verifies inter-node connectivity and measures latency
#
# Usage:
#   cp inventory.env.template inventory.env
#   # edit inventory.env with your IPs and SSH key
#   ./setup_cluster.sh
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
INVENTORY="$HERE/inventory.env"

if [ ! -f "$INVENTORY" ]; then
  echo "ERROR: $INVENTORY not found. Copy from inventory.env.template and fill in your IPs."
  exit 1
fi
source "$INVENTORY"

SSH="ssh -i $SSH_KEY -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -o BatchMode=yes"
SCP="scp -i $SSH_KEY -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -o BatchMode=yes"
NODES=("$NODE1" "$NODE2" "$NODE3" "$NODE4")
ALL_NODES=("${NODES[@]}" "$LOADGEN")
NODE_NAMES=(node1 node2 node3 node4 loadgen)

log() { printf '[%s] %s\n' "$(date -Iseconds)" "$*"; }

# ---------------------------------------------------------------------------
# Phase 1: Wait for cloud-init on all nodes (up to 5 min per node)
# ---------------------------------------------------------------------------
log "=== Phase 1: Waiting for cloud-init ==="
for i in "${!ALL_NODES[@]}"; do
  ip="${ALL_NODES[$i]}"
  name="${NODE_NAMES[$i]}"
  log "checking cloud-init on $name ($ip)..."
  deadline=$(( $(date +%s) + 300 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if $SSH "${SSH_USER}@${ip}" "test -f /root/.cloud-init-done" 2>/dev/null; then
      status=$($SSH "${SSH_USER}@${ip}" "cat /root/.cloud-init-done" 2>/dev/null || echo "unknown")
      log "$name: cloud-init done ($status)"
      break
    fi
    sleep 10
  done
  if ! $SSH "${SSH_USER}@${ip}" "test -f /root/.cloud-init-done" 2>/dev/null; then
    log "WARN: $name cloud-init may still be running; continuing anyway"
  fi
done

# ---------------------------------------------------------------------------
# Phase 2: Configure /etc/hosts on all nodes
# ---------------------------------------------------------------------------
log "=== Phase 2: Configuring /etc/hosts ==="

HOSTS_BLOCK="
# === thesis consensus cluster ===
$NODE1 k1 paxi1 etcd1 tm1 zookeeper zb1
$NODE2 k2 paxi2 etcd2 tm2 zb2
$NODE3 k3 paxi3 etcd3 tm3 zb3
$NODE4 k4 tm4 zb4
$LOADGEN loadgen
# === end thesis cluster ===
"

for i in "${!ALL_NODES[@]}"; do
  ip="${ALL_NODES[$i]}"
  name="${NODE_NAMES[$i]}"
  log "configuring /etc/hosts on $name ($ip)"
  # Remove any previous thesis block, then append the new one
  $SSH "${SSH_USER}@${ip}" "sed -i '/# === thesis consensus cluster ===/,/# === end thesis cluster ===/d' /etc/hosts" 2>/dev/null || true
  echo "$HOSTS_BLOCK" | $SSH "${SSH_USER}@${ip}" "cat >> /etc/hosts"
done

# ---------------------------------------------------------------------------
# Phase 3: Upload thesis bundle to loadgen
# ---------------------------------------------------------------------------
log "=== Phase 3: Uploading thesis bundle ==="
$SSH "${SSH_USER}@${LOADGEN}" "mkdir -p /root/thesis-final"

# Upload only what's needed (exclude docs, visualizer to keep it lean)
rsync -avz -e "ssh -i $SSH_KEY -o StrictHostKeyChecking=accept-new" \
  --exclude='.git' \
  --exclude='visualizer' \
  --exclude='docs' \
  --exclude='__pycache__' \
  --exclude='*.pyc' \
  --exclude='.venv' \
  --exclude='results' \
  "$ROOT/thesis-final/" \
  "${SSH_USER}@${LOADGEN}:/root/thesis-final/"

$SSH "${SSH_USER}@${LOADGEN}" "chmod +x /root/thesis-final/scripts/*.sh /root/thesis-final/scripts/pumba/*.sh"

# Also create a deploy dir on loadgen with inventory and fault scripts
$SSH "${SSH_USER}@${LOADGEN}" "mkdir -p /root/thesis-final/deploy"
$SCP "$INVENTORY" "${SSH_USER}@${LOADGEN}:/root/thesis-final/deploy/inventory.env"
$SCP "$HERE/faults.sh" "${SSH_USER}@${LOADGEN}:/root/thesis-final/deploy/faults.sh"
$SSH "${SSH_USER}@${LOADGEN}" "chmod +x /root/thesis-final/deploy/faults.sh"

# ---------------------------------------------------------------------------
# Phase 4: Create Docker network on loadgen (for benchmark clients)
# ---------------------------------------------------------------------------
log "=== Phase 4: Docker network ==="
$SSH "${SSH_USER}@${LOADGEN}" "docker network create thesis-net 2>/dev/null || true"

# ---------------------------------------------------------------------------
# Phase 5: Build custom images on loadgen
# ---------------------------------------------------------------------------
log "=== Phase 5: Building custom Docker images ==="

log "building thesis-hotstuff (5-10 min)..."
$SSH "${SSH_USER}@${LOADGEN}" "cd /root/thesis-final && docker build -t thesis-hotstuff:latest docker/hotstuff/"

log "building thesis-paxi..."
$SSH "${SSH_USER}@${LOADGEN}" "cd /root/thesis-final && docker build -t thesis-paxi:latest docker/paxi/"

log "building le-probe..."
$SSH "${SSH_USER}@${LOADGEN}" "cd /root/thesis-final && docker build -t le-probe:latest scripts/probes/"

# ---------------------------------------------------------------------------
# Phase 6: Distribute custom images to consensus nodes
# ---------------------------------------------------------------------------
log "=== Phase 6: Distributing images ==="
for node_ip in "${NODES[@]}"; do
  for img in thesis-hotstuff:latest thesis-paxi:latest le-probe:latest; do
    log "saving $img to node $node_ip..."
    $SSH "${SSH_USER}@${LOADGEN}" "docker save $img" \
      | $SSH "${SSH_USER}@${node_ip}" "docker load"
  done
done

# ---------------------------------------------------------------------------
# Phase 7: Pull third-party images on all nodes
# ---------------------------------------------------------------------------
log "=== Phase 7: Pulling third-party images ==="
THIRD_PARTY_IMAGES=(
  "apache/kafka:3.7.0"
  "quay.io/coreos/etcd:v3.5.15"
  "cometbft/cometbft:v0.38.12"
)

for node_ip in "${ALL_NODES[@]}"; do
  for img in "${THIRD_PARTY_IMAGES[@]}"; do
    log "pulling $img on $node_ip..."
    $SSH "${SSH_USER}@${node_ip}" "docker pull $img" &
  done
  wait
done

# ---------------------------------------------------------------------------
# Phase 8: Verify connectivity
# ---------------------------------------------------------------------------
log "=== Phase 8: Verifying connectivity ==="
ALL_IPS=("${ALL_NODES[@]}")
for i in "${!ALL_IPS[@]}"; do
  for j in "${!ALL_IPS[@]}"; do
    if [ "$i" -lt "$j" ]; then
      a="${ALL_IPS[$i]}"
      b="${ALL_IPS[$j]}"
      latency=$($SSH "${SSH_USER}@${a}" "ping -c 5 -q $b 2>/dev/null | tail -1 | awk -F/ '{print \$5}'" 2>/dev/null || echo "FAIL")
      log "${NODE_NAMES[$i]} -> ${NODE_NAMES[$j]}: ${latency}ms"
    fi
  done
done

log "=== Setup complete ==="
log "Next: run './deploy/run_campaign.sh' to start the benchmark"
