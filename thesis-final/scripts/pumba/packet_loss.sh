#!/usr/bin/env bash
# =============================================================================
# packet_loss.sh - inject 2% packet loss on all SUT containers for DUR seconds
# =============================================================================
# Uses Pumba (github.com/alexei-led/pumba) which applies `tc netem` rules
# inside containers without requiring in-container tc installation.
# =============================================================================
set -u

SYSTEM="${1:?usage: $0 <s> <duration_sec>}"
DUR="${2:-300}"

case "$SYSTEM" in
  kraft)        PATTERN="re2:^k[0-9]+$" ;;
  zk)           PATTERN="re2:^(zookeeper|zb[0-9]+)$" ;;
  etcd)         PATTERN="re2:^etcd[0-9]+$" ;;
  tendermint)   PATTERN="re2:^tm[1-4]$" ;;
  paxos|epaxos) PATTERN="re2:^paxi[1-3]$" ;;
  *)     echo "unknown system: $SYSTEM" >&2; exit 1 ;;
esac

echo "[$(date -Iseconds)] injecting 2% loss on $PATTERN for ${DUR}s"

# --tc-image gives Pumba a tc-capable image to sideload into each target.
# gaiadocker/iproute2 is the standard companion image the Pumba docs use.
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  gaiaadm/pumba:latest \
  --log-level info \
  netem --duration "${DUR}s" --tc-image gaiadocker/iproute2 \
    loss --percent 2 \
    "$PATTERN"
