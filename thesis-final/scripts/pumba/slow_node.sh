#!/usr/bin/env bash
# =============================================================================
# slow_node.sh - degrade ONE node's CPU for DUR seconds
# =============================================================================
#
# Instead of a clean crash, this simulates a "slow/degraded" node — one
# that is still alive and still in the quorum but responding sluggishly.
# This is realistically the most common production failure mode: noisy
# neighbour on a shared host, GC pauses, runaway process, etc.
#
# Consensus protocols are supposed to tolerate this gracefully by keeping
# the slow node in the quorum but not waiting for it on the fast path.
# The measurement shows how well each protocol actually handles it.
#
# Pumba's `stress` subcommand runs stress-ng inside the target container
# with the specified CPU load. We apply 70% CPU load to 1 CPU core for
# the full measurement window.
# =============================================================================
set -u

SYSTEM="${1:?usage: $0 <s> <duration_sec>}"
DUR="${2:-300}"

case "$SYSTEM" in
  kraft)      TARGET="k2" ;;
  zk)         TARGET="zb2" ;;
  etcd)       TARGET="etcd2" ;;
  tendermint) TARGET="tm2" ;;
  paxos|epaxos) TARGET="paxi2" ;;
  *) echo "unknown system: $SYSTEM" >&2; exit 1 ;;
esac

# We deliberately pick node 2 (not node 1) so that if any other script
# targets "the first node" we are stressing a different one.

echo "[$(date -Iseconds)] stressing $TARGET for ${DUR}s (70% CPU, 1 core)"

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  gaiaadm/pumba:latest \
  --log-level info \
  stress \
    --duration "${DUR}s" \
    --stressors "--cpu 1 --cpu-load 70" \
    "$TARGET"
