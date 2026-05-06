#!/usr/bin/env bash
# =============================================================================
# leader_kill.sh - kill the current consensus leader / proposer
# =============================================================================
#
# Each system's leader-detection logic is different:
#   kraft       - kafka-metadata-quorum.sh returns "LeaderId: N"
#   zk          - /controller znode holds {"brokerid": N, ...}
#   etcd        - endpoint status -w simple, field 5 is "true" for leader
#   tendermint  - /block + /validators matched to find proposer index
#   paxos       - Paxi /state reports isLeader; query each replica
#   epaxos      - leaderless; kill a random replica. The measurement is
#                 still meaningful because EPaxos's recovery path is
#                 triggered by dead-peer detection, not by leader loss.
# =============================================================================
set -u

SYSTEM="${1:?usage: $0 <kraft|zk|etcd|tendermint|paxos|epaxos>}"

case "$SYSTEM" in

  kraft)
    LID=$(docker exec k1 /opt/kafka/bin/kafka-metadata-quorum.sh \
            --bootstrap-server localhost:9092 describe --status 2>/dev/null \
          | awk '/^LeaderId:/ {print $2; exit}')
    [ -z "$LID" ] && LID=1
    TARGET="k${LID}"
    ;;

  zk)
    CID=$(echo "get /controller" | docker exec -i zookeeper zkCli.sh 2>/dev/null \
          | awk -F'"brokerid":' 'NF>1 {print $2}' \
          | grep -oE '^[0-9]+' \
          | head -1)
    [ -z "$CID" ] && CID=1
    TARGET="zb${CID}"
    ;;

  etcd)
    TARGET=""
    for i in 1 2 3 4 5 6 7; do
      docker inspect "etcd$i" >/dev/null 2>&1 || continue
      if docker exec "etcd$i" etcdctl \
           --endpoints=http://localhost:2379 \
           endpoint status -w simple 2>/dev/null \
         | awk -F', ' '{print $5}' | grep -q 'true'; then
        TARGET="etcd$i"
        break
      fi
    done
    [ -z "$TARGET" ] && TARGET="etcd1"
    ;;

  tendermint)
    BLOCK_JSON=$(curl -s http://localhost:26657/block 2>/dev/null)
    PROP_ADDR=$(echo "$BLOCK_JSON" \
      | grep -oE '"proposer_address":"[A-Fa-f0-9]+"' \
      | head -1 \
      | grep -oE '[A-Fa-f0-9]+"$' \
      | tr -d '"')

    TARGET=""
    if [ -n "$PROP_ADDR" ]; then
      VALS_JSON=$(curl -s http://localhost:26657/validators 2>/dev/null)
      IDX=$(echo "$VALS_JSON" \
            | grep -oE '"address":"[A-Fa-f0-9]+"' \
            | grep -oE '[A-Fa-f0-9]+"$' \
            | tr -d '"' \
            | awk -v target="$PROP_ADDR" 'toupper($0)==toupper(target){print NR; exit}')
      if [ -n "$IDX" ]; then
        TARGET="tm${IDX}"
      fi
    fi
    [ -z "$TARGET" ] && TARGET="tm1"
    ;;

  paxos)
    # Paxi's /state endpoint reports per-replica state including ballot
    # number. The replica with the highest ballot and an active state
    # is the leader. In practice the leader tends to stay fixed once
    # elected, so we ask paxi1 for its view and target whichever ID it
    # reports as leader. Fallback: paxi1.
    LID=$(curl -s http://localhost:18080/state 2>/dev/null \
          | grep -oE '"leader":"[0-9.]+"' \
          | head -1 | grep -oE '[0-9]+\.[0-9]+')
    case "$LID" in
      1.1) TARGET="paxi1" ;;
      1.2) TARGET="paxi2" ;;
      1.3) TARGET="paxi3" ;;
      *)   TARGET="paxi1" ;;
    esac
    ;;

  epaxos)
    # EPaxos is leaderless: any replica can commit any command via the
    # fast path. There is no single "leader" to kill. The experiment's
    # value here is testing how the cluster recovers when ANY replica
    # dies — which is the real contribution of EPaxos. We target paxi2
    # (middle node) rather than paxi1 (probe connection target) so we
    # observe a genuine replica loss from the probe's perspective.
    TARGET="paxi2"
    ;;

  *)
    echo "unknown system: $SYSTEM" >&2
    exit 1 ;;

esac

echo "[$(date -Iseconds)] killing leader: $TARGET"
docker kill "$TARGET" >/dev/null
echo "[$(date -Iseconds)] $TARGET killed"
