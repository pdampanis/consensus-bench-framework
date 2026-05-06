#!/usr/bin/env bash
# =============================================================================
# lib.sh — shared functions for Hetzner cloud deployment scripts
#
# Sourced by run_campaign.sh, run_manual.sh, setup_cluster.sh.
# Provides: container placement, service start/stop, remote execution.
# =============================================================================

# Source inventory if not already loaded
if [ -z "${NODE1:-}" ]; then
  HERE_LIB="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  source "$HERE_LIB/inventory.env"
fi

SSH="ssh -i ${SSH_KEY:-~/.ssh/hetzner_thesis} -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -o BatchMode=yes"

# ---------------------------------------------------------------------------
# Container placement map
# ---------------------------------------------------------------------------
declare -A PLACEMENT
PLACEMENT=(
  ["k1"]="$NODE1"  ["k2"]="$NODE2"  ["k3"]="$NODE3"
  ["k4"]="$NODE4"  ["k5"]="$NODE4"  ["k6"]="$NODE1"  ["k7"]="$NODE2"
  ["zb1"]="$NODE1"  ["zb2"]="$NODE2"  ["zb3"]="$NODE3"
  ["zookeeper"]="$NODE4"
  ["etcd1"]="$NODE1"  ["etcd2"]="$NODE2"  ["etcd3"]="$NODE3"
  ["etcd4"]="$NODE4"  ["etcd5"]="$NODE4"  ["etcd6"]="$NODE1"  ["etcd7"]="$NODE2"
  ["tm1"]="$NODE1"  ["tm2"]="$NODE2"  ["tm3"]="$NODE3"  ["tm4"]="$NODE4"
  ["paxi1"]="$NODE1"  ["paxi2"]="$NODE2"  ["paxi3"]="$NODE3"
  ["hotstuff"]="$LOADGEN"
)

container_ip() {
  local name="$1"
  echo "${PLACEMENT[$name]:-$LOADGEN}"
}

ssh_node() {
  local node_name="$1"; shift
  local ip; ip=$(container_ip "$node_name")
  $SSH "${SSH_USER:-root}@${ip}" "$@"
}

log() { printf '[%s] %s\n' "$(date -Iseconds)" "$*"; }

# ---------------------------------------------------------------------------
# System lifecycle
# ---------------------------------------------------------------------------

start_kraft() {
  local size="${1:-3}"
  log "starting KRaft (size=$size)"
  for i in $(seq 1 "$size"); do
    local name="k$i" ip node_id=$((i - 1))
    ip=$(container_ip "$name")
    local voters=""
    for j in $(seq 0 $((size - 1))); do
      [ -n "$voters" ] && voters+=","
      voters+="$j@k$((j + 1)):9093"
    done
    $SSH "${SSH_USER:-root}@${ip}" "
      docker rm -f $name 2>/dev/null || true
      docker run -d --name $name --network host \
        -e KAFKA_NODE_ID=$node_id \
        -e KAFKA_PROCESS_ROLES=broker,controller \
        -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093 \
        -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://$name:9092 \
        -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
        -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
        -e KAFKA_CONTROLLER_QUORUM_VOTERS=$voters \
        -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=$size \
        -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=$size \
        -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=2 \
        -e KAFKA_LOG_FLUSH_INTERVAL_MESSAGES=10000 \
        -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
        --memory=1600m --cpus=2 \
        apache/kafka:3.7.0
    " &
  done
  wait
  sleep 20
}

start_zk() {
  log "starting Kafka+ZK"
  $SSH "${SSH_USER:-root}@${NODE4}" "
    docker rm -f zookeeper 2>/dev/null || true
    docker run -d --name zookeeper --network host \
      -e ZOOKEEPER_CLIENT_PORT=2181 -e ZOOKEEPER_TICK_TIME=2000 \
      confluentinc/cp-zookeeper:7.5.0
  "
  sleep 10
  for i in 1 2 3; do
    local name="zb$i" ip bid="$i"
    ip=$(container_ip "$name")
    $SSH "${SSH_USER:-root}@${ip}" "
      docker rm -f $name 2>/dev/null || true
      docker run -d --name $name --network host \
        -e KAFKA_BROKER_ID=$bid \
        -e KAFKA_ZOOKEEPER_CONNECT=zookeeper:2181 \
        -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092 \
        -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://$name:9092 \
        -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=3 \
        confluentinc/cp-kafka:7.5.0
    " &
  done
  wait
  sleep 15
}

start_etcd() {
  local size="${1:-3}"
  log "starting etcd (size=$size)"
  local cluster=""
  for i in $(seq 1 "$size"); do
    [ -n "$cluster" ] && cluster+=","
    cluster+="etcd$i=http://etcd$i:2380"
  done
  for i in $(seq 1 "$size"); do
    local name="etcd$i" ip
    ip=$(container_ip "$name")
    $SSH "${SSH_USER:-root}@${ip}" "
      docker rm -f $name 2>/dev/null || true
      docker run -d --name $name --network host \
        -e ETCD_NAME=$name \
        -e ETCD_LISTEN_CLIENT_URLS=http://0.0.0.0:2379 \
        -e ETCD_ADVERTISE_CLIENT_URLS=http://$name:2379 \
        -e ETCD_LISTEN_PEER_URLS=http://0.0.0.0:2380 \
        -e ETCD_INITIAL_ADVERTISE_PEER_URLS=http://$name:2380 \
        -e ETCD_INITIAL_CLUSTER='$cluster' \
        -e ETCD_INITIAL_CLUSTER_TOKEN=thesis-etcd \
        -e ETCD_INITIAL_CLUSTER_STATE=new \
        --memory=512m --cpus=1 \
        quay.io/coreos/etcd:v3.5.15 /usr/local/bin/etcd
    " &
  done
  wait
  sleep 10
}

start_tendermint() {
  log "starting CometBFT (4 validators)"
  # Generate testnet config on loadgen. cometbft testnet --populate-persistent-peers
  # writes correct peer IDs (hex pubkey hashes) into each node's config.toml.
  # Each node mounts its pre-generated config directory as /cometbft.
  $SSH "${SSH_USER:-root}@${LOADGEN}" "
    mkdir -p /tmp/cmt-testnet
    docker run --rm -v /tmp/cmt-testnet:/testnet \
      cometbft/cometbft:v0.38.12 testnet \
        --v 4 --o /testnet --populate-persistent-peers \
        --hostname-prefix 'tm' --hostname-suffix '' \
        --hostname 'tm1,tm2,tm3,tm4' 2>/dev/null || true
  "
  for i in 1 2 3 4; do
    local name="tm$i" ip node_idx=$((i - 1))
    ip=$(container_ip "$name")
    # Copy pre-generated config to the target node (node0 → tm1, node1 → tm2, etc.)
    $SSH "${SSH_USER:-root}@${LOADGEN}" "cd /tmp/cmt-testnet && tar czf - node${node_idx}" \
      | $SSH "${SSH_USER:-root}@${ip}" "mkdir -p /tmp/cmt && cd /tmp/cmt && rm -rf node${node_idx} && tar xzf -"
    # The config.toml already has correct persistent_peers with real peer IDs.
    # No --p2p.persistent_peers flag needed — config.toml handles it.
    $SSH "${SSH_USER:-root}@${ip}" "
      docker rm -f $name 2>/dev/null || true
      docker run -d --name $name --network host \
        -v /tmp/cmt/node${node_idx}:/cometbft \
        cometbft/cometbft:v0.38.12 node \
        --proxy_app=kvstore \
        --rpc.laddr=tcp://0.0.0.0:26657
    " &
  done
  wait
  sleep 15
}

start_paxi() {
  local algorithm="${1:-paxos}"
  log "starting Paxi (algorithm=$algorithm)"
  for i in 1 2 3; do
    local name="paxi$i" ip
    ip=$(container_ip "$name")
    $SSH "${SSH_USER:-root}@${ip}" "
      docker rm -f $name 2>/dev/null || true
      docker run -d --name $name --network host \
        thesis-paxi:latest \
        -id 1.$i -algorithm=$algorithm -config /etc/paxi/config.json
    " &
  done
  wait
  sleep 10
}

start_hotstuff() {
  log "starting HotStuff on loadgen"
  $SSH "${SSH_USER:-root}@${LOADGEN}" "
    docker rm -f hotstuff 2>/dev/null || true
    docker run -d --name hotstuff --network host \
      --cap-add=NET_ADMIN --cap-add=SYS_PTRACE --init \
      -v hotstuff-work:/work \
      thesis-hotstuff:latest sleep infinity
  "
  sleep 5
}

stop_system() {
  local system="$1"
  local containers=""
  case "$system" in
    kraft) containers="k1 k2 k3 k4 k5 k6 k7" ;;
    zk)    containers="zb1 zb2 zb3 zookeeper" ;;
    etcd)  containers="etcd1 etcd2 etcd3 etcd4 etcd5 etcd6 etcd7" ;;
    tendermint) containers="tm1 tm2 tm3 tm4" ;;
    paxos|epaxos) containers="paxi1 paxi2 paxi3" ;;
    hotstuff) containers="hotstuff" ;;
    all)    containers="k1 k2 k3 k4 k5 k6 k7 zb1 zb2 zb3 zookeeper etcd1 etcd2 etcd3 etcd4 etcd5 etcd6 etcd7 tm1 tm2 tm3 tm4 paxi1 paxi2 paxi3 hotstuff" ;;
    *) return 1 ;;
  esac
  for c in $containers; do
    local ip
    ip=$(container_ip "$c" 2>/dev/null || echo "")
    [ -z "$ip" ] && continue
    $SSH "${SSH_USER:-root}@${ip}" "docker rm -f $c 2>/dev/null || true" &
  done
  wait
}
