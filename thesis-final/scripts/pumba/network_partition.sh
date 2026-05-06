#!/usr/bin/env bash
# =============================================================================
# network_partition.sh - isolate one node for DUR seconds
# =============================================================================
# Makes the chosen node drop 100% of its traffic for DUR seconds, which is
# equivalent to a network partition from the quorum's point of view. The
# remaining 2 of 3 nodes still form a quorum, so the cluster should
# continue making progress while the isolated node cannot commit.
# =============================================================================
set -u

SYSTEM="${1:?usage: $0 <s> <duration_sec>}"
DUR="${2:-300}"

case "$SYSTEM" in
  kraft)        TARGET="k1" ;;
  zk)           TARGET="zb1" ;;
  etcd)         TARGET="etcd1" ;;
  tendermint)   TARGET="tm1" ;;
  paxos|epaxos) TARGET="paxi1" ;;
  *) echo "unknown system: $SYSTEM" >&2; exit 1 ;;
esac

echo "[$(date -Iseconds)] isolating $TARGET for ${DUR}s"

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  gaiaadm/pumba:latest \
  --log-level info \
  netem --duration "${DUR}s" --tc-image gaiadocker/iproute2 \
    loss --percent 100 \
    "$TARGET"
