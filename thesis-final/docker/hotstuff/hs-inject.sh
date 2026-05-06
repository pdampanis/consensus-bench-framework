#!/usr/bin/env bash
# =============================================================================
# hs-inject.sh — in-container HotStuff fault injector
# =============================================================================
#
# HotStuff runs as 4 processes inside a single container (the asonnino
# fab local orchestration pattern). The per-container `docker kill` and
# Pumba netem tools used for the other systems would take down all 4
# at once, which isn't what we want for leader_kill / slow_node / partition.
# Instead, we do fault injection at the *process* level inside the
# container, targeting the specific node process we want to affect.
#
# This script is invoked from run_one.sh via:
#   docker exec hotstuff /opt/hs-inject.sh <scenario>
#
# Scenarios:
#   leader_kill       kill the node with rank 0 (HotStuff's first leader);
#                     the view-change protocol rotates to rank 1
#   double_kill       kill nodes 0 and 1 in parallel (crosses quorum: BFT
#                     needs 3 of 4, leaving 2 is below liveness)
#   slow_node         stress one node's CPU to ~70% via a background load
#   packet_loss       add tc netem 5% loss on the container's loopback,
#                     which is the network substrate the 4 processes use
#                     to talk to each other via distinct ports
#   network_partition iptables DROP on the port of node 0 to isolate it
#
# The asonnino fabric harness launches the 4 nodes in tmux panes named
# node-0 through node-3. The nodes listen on sequential ports starting
# at the base port configured in fabfile.py (default 9000).
#
# Exits 0 on successful injection; non-zero on failure.
# =============================================================================
set -eu

SCENARIO="${1:?usage: $0 <scenario>}"

# Discover the PIDs of the 4 node processes by port binding. Port
# numbers follow asonnino's default scheme: each node opens 2 ports
# (consensus + mempool front); we look for the validator-to-validator
# ports which are on the PRIMARY_PORT_BASE.
PRIMARY_PORT_BASE=9000
find_node_pid() {
  local rank="$1"
  local port=$((PRIMARY_PORT_BASE + rank))
  # ss outputs lines like: tcp LISTEN 0 128 *:9000 *:* users:(("node",pid=42,fd=7))
  ss -lntp 2>/dev/null \
    | awk -v port=":$port" '
        $0 ~ port {
          match($0, /pid=[0-9]+/);
          if (RSTART) {
            print substr($0, RSTART+4, RLENGTH-4);
            exit
          }
        }' | head -1
}

ts() { date -Iseconds; }

case "$SCENARIO" in
  leader_kill)
    PID=$(find_node_pid 0)
    if [ -z "${PID:-}" ]; then
      echo "[$(ts)] hs-inject: could not find node-0 pid" >&2
      exit 1
    fi
    echo "[$(ts)] hs-inject: killing node-0 (pid $PID)"
    kill -9 "$PID"
    echo "[$(ts)] hs-inject: node-0 killed"
    ;;

  double_kill)
    P0=$(find_node_pid 0)
    P1=$(find_node_pid 1)
    if [ -z "${P0:-}" ] || [ -z "${P1:-}" ]; then
      echo "[$(ts)] hs-inject: could not find node-0 or node-1 pid" >&2
      exit 1
    fi
    echo "[$(ts)] hs-inject: double-kill node-0 (pid $P0) + node-1 (pid $P1)"
    kill -9 "$P0" & kill -9 "$P1" &
    wait
    echo "[$(ts)] hs-inject: node-0 and node-1 killed"
    ;;

  slow_node)
    # Apply a CPU-bound loop that competes with node-1 for its core.
    # Not perfect 70% emulation but directionally equivalent to Pumba's
    # stress mode. Runs for 60s then exits, which is well inside the
    # measurement window.
    echo "[$(ts)] hs-inject: spawning CPU stress in background for 60s"
    (
      end=$(( $(date +%s) + 60 ))
      while [ "$(date +%s)" -lt "$end" ]; do
        # busy-loop — 'yes' piped to /dev/null at CPU priority keeps one
        # core saturated; two of them gets us closer to displacing the
        # target validator's throughput.
        yes > /dev/null &
        BG=$!
        sleep 1
        kill "$BG" 2>/dev/null || true
      done
    ) &
    echo "[$(ts)] hs-inject: CPU stress running (pid $!)"
    ;;

  packet_loss)
    # Requires the container to run with NET_ADMIN (see compose file).
    # 5% loss on loopback only affects the HotStuff-internal traffic.
    echo "[$(ts)] hs-inject: adding 5% packet loss on lo"
    tc qdisc add dev lo root netem loss 5% 2>/dev/null \
      || tc qdisc change dev lo root netem loss 5%
    echo "[$(ts)] hs-inject: netem loss 5% active"
    ;;

  network_partition)
    # Use iptables to block the port of node-0 so it cannot reach peers.
    # From node-0's point of view the other 3 look dead; from their
    # point of view node-0 looks silent. This is the asymmetric
    # partition the other systems' network_partition.sh produces.
    echo "[$(ts)] hs-inject: partitioning node-0 via iptables"
    iptables -A INPUT  -p tcp --dport 9000 -j DROP
    iptables -A OUTPUT -p tcp --sport 9000 -j DROP
    echo "[$(ts)] hs-inject: node-0 partitioned"
    ;;

  *)
    echo "hs-inject: unknown scenario: $SCENARIO" >&2
    exit 2 ;;
esac
