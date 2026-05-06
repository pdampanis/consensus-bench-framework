#!/usr/bin/env bash
# =============================================================================
# double_kill.sh - kill TWO nodes simultaneously
# =============================================================================
#
# In a 3-node cluster, killing 2 nodes crosses the quorum boundary (need
# 2 of 3 for majority; 1 remaining is below quorum). The expected outcome
# is that the cluster stops accepting writes entirely — throughput drops
# to zero for the rest of the measurement window. This empirically
# confirms the CAP choice: all three CFT systems choose consistency over
# availability.
#
# For CometBFT (4-node cluster), killing 2 leaves 2 of 4 alive which is
# below the 2f+1 = 3 threshold for BFT (f=1). Same outcome: no progress.
#
# The two kills are done in the background so they happen in parallel.
# Sequential kills would let the cluster briefly re-converge between
# them, producing a messy measurement.
# =============================================================================
set -u

SYSTEM="${1:?usage: $0 <kraft|zk|etcd|tendermint>}"

case "$SYSTEM" in
  kraft)      T1="k1";    T2="k2" ;;
  zk)         T1="zb1";   T2="zb2" ;;
  etcd)       T1="etcd1"; T2="etcd2" ;;
  tendermint) T1="tm1";   T2="tm2" ;;
  paxos|epaxos) T1="paxi1"; T2="paxi2" ;;
  *) echo "unknown system: $SYSTEM" >&2; exit 1 ;;
esac

echo "[$(date -Iseconds)] double kill: $T1 + $T2 in parallel"
docker kill "$T1" >/dev/null &
PID1=$!
docker kill "$T2" >/dev/null &
PID2=$!
wait "$PID1" "$PID2"
echo "[$(date -Iseconds)] $T1 and $T2 both killed"

# Containers stay dead until teardown. The cluster should stop making
# progress for the rest of the measurement window.
