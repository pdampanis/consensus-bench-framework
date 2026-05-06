#!/usr/bin/env bash
# =============================================================================
# faults.sh — VM-aware fault injection for Hetzner cloud deployment
# =============================================================================
#
# Sourced by lib.sh when DEPLOY_MODE=vm is set.
# Replaces the Pumba-based fault injection in scripts/pumba/*.sh with
# SSH-based injection targeting containers on remote nodes.
#
# Each function mirrors the interface of scripts/pumba/<scenario>.sh:
#   ./faults.sh <scenario> <system> [duration_sec]
#
# On VMs with --network host, containers listen directly on the VM's
# interfaces. Fault injection targets the container process or the
# VM's network stack.
# =============================================================================
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
if [ -z "${NODE1:-}" ]; then
  source "$HERE/inventory.env"
fi

SSH="ssh -i ${SSH_KEY:-~/.ssh/hetzner_thesis} -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -o BatchMode=yes"
SSH_USER="${SSH_USER:-root}"

SCENARIO="${1:?usage: $0 <scenario> <system> [duration_sec]}"
SYSTEM="${2:?}"
DURATION="${3:-300}"

# Container placement (must match lib.sh)
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

# Which container is the "leader node" for each system
leader_container() {
  case "$SYSTEM" in
    kraft)
      # KRaft controller leader (active controller)
      for c in k1 k2 k3 k4 k5 k6 k7; do
        local ip="${PLACEMENT[$c]:-}"
        [ -z "$ip" ] && continue
        local lid
        lid=$($SSH "${SSH_USER}@${ip}" \
          "docker exec $c /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe --status 2>/dev/null | awk '/^LeaderId:/{print \$2; exit}'" 2>/dev/null || echo "")
        if [ -n "$lid" ]; then
          echo "k$((lid + 1))"
          return
        fi
      done
      echo "k1" ;;
    zk)
      local cid
      cid=$(echo "get /controller" | $SSH "${SSH_USER}@${NODE4}" \
        "docker exec -i zookeeper zkCli.sh 2>/dev/null" 2>/dev/null \
        | awk -F'"brokerid":' 'NF>1 {print $2}' | grep -oE '^[0-9]+' | head -1 || echo "1")
      echo "zb${cid}" ;;
    etcd)
      for c in etcd1 etcd2 etcd3 etcd4 etcd5 etcd6 etcd7; do
        local ip="${PLACEMENT[$c]:-}"
        [ -z "$ip" ] && continue
        if $SSH "${SSH_USER}@${ip}" \
          "docker exec $c etcdctl --endpoints=http://localhost:2379 endpoint status -w simple 2>/dev/null | awk -F', ' '\$5==\"true\"{print}'" 2>/dev/null | grep -q .; then
          echo "$c"
          return
        fi
      done
      echo "etcd1" ;;
    tendermint)
      # Query tm1 for the proposer address of latest block
      local prop_addr
      prop_addr=$(curl -s "http://tm1:26657/block" 2>/dev/null \
        | grep -oE '"proposer_address":"[A-Fa-f0-9]+"' | head -1 | grep -oE '[A-Fa-f0-9]+' | tail -1 || echo "")
      if [ -n "$prop_addr" ]; then
        for i in 1 2 3 4; do
          local addr
          addr=$(curl -s "http://tm$i:26657/status" 2>/dev/null \
            | grep -oE '"address":"[A-Fa-f0-9]+"' | head -1 | grep -oE '[A-Fa-f0-9]+' | tail -1 || echo "")
          if [ "${addr,,}" = "${prop_addr,,}" ]; then
            echo "tm$i"
            return
          fi
        done
      fi
      echo "tm1" ;;
    paxos)
      for i in 1 2 3; do
        if curl -s "http://paxi$i:8080/state" 2>/dev/null | grep -q '"isLeader":true'; then
          echo "paxi$i"
          return
        fi
      done
      echo "paxi1" ;;
    epaxos)
      echo "paxi2" ;;   # leaderless; kill middle node
    hotstuff)
      echo "hotstuff" ;;
    *) echo "unknown" ;;
  esac
}

kill_container() {
  local name="$1"
  local ip="${PLACEMENT[$name]:-}"
  [ -z "$ip" ] && { echo "no placement for $name"; return 1; }
  $SSH "${SSH_USER}@${ip}" "docker kill $name 2>/dev/null || true"
}

start_container() {
  local name="$1"
  local ip="${PLACEMENT[$name]:-}"
  [ -z "$ip" ] && { echo "no placement for $name"; return 1; }
  $SSH "${SSH_USER}@${ip}" "docker start $name 2>/dev/null || true"
}

tc_on_node() {
  local node_ip="$1"; shift
  $SSH "${SSH_USER}@${node_ip}" "$@"
}

ts() { date -Iseconds; }

# ---------------------------------------------------------------------------
# Scenario dispatch
# ---------------------------------------------------------------------------
case "$SCENARIO" in
  leader_kill)
    TARGET=$(leader_container)
    echo "[$(ts)] leader_kill: $SYSTEM -> $TARGET"
    kill_container "$TARGET"
    echo "[$(ts)] killed $TARGET"
    ;;

  double_kill)
    T1=$(leader_container)
    echo "[$(ts)] double_kill: $SYSTEM -> $T1 + secondary"
    kill_container "$T1"
    case "$SYSTEM" in
      kraft)  T2="k2"; [ "$T1" = "k2" ] && T2="k3" ;;
      zk)     T2="zb2"; [ "$T1" = "zb2" ] && T2="zb3" ;;
      etcd)   T2="etcd2"; [ "$T1" = "etcd2" ] && T2="etcd3" ;;
      tendermint) T2="tm2"; [ "$T1" = "tm2" ] && T2="tm3" ;;
      paxos|epaxos) T2="paxi2"; [ "$T1" = "paxi2" ] && T2="paxi3" ;;
      hotstuff) T2="hotstuff" ;;  # both processes killed inside container
      *) T2="unknown" ;;
    esac
    kill_container "$T2"
    echo "[$(ts)] double-killed $T1 + $T2"
    ;;

  packet_loss)
    echo "[$(ts)] packet_loss: adding 5% loss on all nodes"
    for ip in "$NODE1" "$NODE2" "$NODE3" "$NODE4"; do
      tc_on_node "$ip" "
        tc qdisc add dev eth0 root netem loss 5% 2>/dev/null \
          || tc qdisc change dev eth0 root netem loss 5%
      " &
    done
    wait
    echo "[$(ts)] packet_loss active for ${DURATION}s"
    sleep "$DURATION"
    echo "[$(ts)] removing packet loss"
    for ip in "$NODE1" "$NODE2" "$NODE3" "$NODE4"; do
      tc_on_node "$ip" "tc qdisc del dev eth0 root 2>/dev/null || true" &
    done
    wait
    ;;

  network_partition)
    TARGET=$(leader_container)
    TARGET_IP="${PLACEMENT[$TARGET]:-}"
    echo "[$(ts)] network_partition: isolating $TARGET ($TARGET_IP)"
    # Block traffic to/from target on all other nodes
    for ip in "$NODE1" "$NODE2" "$NODE3" "$NODE4"; do
      [ "$ip" = "$TARGET_IP" ] && continue
      tc_on_node "$ip" "
        iptables -A INPUT -s $TARGET_IP -j DROP 2>/dev/null
        iptables -A OUTPUT -d $TARGET_IP -j DROP 2>/dev/null
      " &
    done
    # Also block traffic on the target itself (complete isolation)
    for ip in "$NODE1" "$NODE2" "$NODE3" "$NODE4"; do
      [ "$ip" = "$TARGET_IP" ] && continue
      tc_on_node "$TARGET_IP" "
        iptables -A INPUT -s $ip -j DROP 2>/dev/null
        iptables -A OUTPUT -d $ip -j DROP 2>/dev/null
      " &
    done
    wait
    echo "[$(ts)] partition active for ${DURATION}s"
    sleep "$DURATION"
    echo "[$(ts)] healing partition"
    for ip in "$NODE1" "$NODE2" "$NODE3" "$NODE4"; do
      [ "$ip" = "$TARGET_IP" ] && continue
      tc_on_node "$ip" "
        iptables -D INPUT -s $TARGET_IP -j DROP 2>/dev/null
        iptables -D OUTPUT -d $TARGET_IP -j DROP 2>/dev/null
      " &
    done
    for ip in "$NODE1" "$NODE2" "$NODE3" "$NODE4"; do
      [ "$ip" = "$TARGET_IP" ] && continue
      tc_on_node "$TARGET_IP" "
        iptables -D INPUT -s $ip -j DROP 2>/dev/null
        iptables -D OUTPUT -d $ip -j DROP 2>/dev/null
      " &
    done
    wait
    ;;

  slow_node)
    TARGET=$(leader_container)
    TARGET_IP="${PLACEMENT[$TARGET]:-}"
    echo "[$(ts)] slow_node: stressing CPU on $TARGET ($TARGET_IP) for ${DURATION}s"
    $SSH "${SSH_USER}@${TARGET_IP}" "
      # Run CPU-bound stress for the fault duration
      timeout ${DURATION}s stress-ng --cpu 2 --timeout ${DURATION}s 2>/dev/null \
        || timeout ${DURATION}s stress --cpu 2 --timeout ${DURATION}s 2>/dev/null \
        || ( end=\$(( \$(date +%s) + $DURATION )); while [ \"\$(date +%s)\" -lt \"\$end\" ]; do yes > /dev/null & pid=\$!; sleep 0.1; kill \$pid 2>/dev/null; done )
    " &
    echo "[$(ts)] CPU stress started on $TARGET"
    ;;

  *)
    echo "unknown scenario: $SCENARIO" >&2
    exit 2 ;;
esac
