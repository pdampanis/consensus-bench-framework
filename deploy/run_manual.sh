#!/usr/bin/env bash
# =============================================================================
# run_manual.sh — interactive manual exploration of each consensus algorithm
# =============================================================================
#
# Starts one system interactively across the 5-node cluster, prints
# copy-pasteable commands for interacting with it, and waits for you
# to press Enter before tearing down. Use this to build intuition
# before running the full campaign.
#
# Usage:
#   ./run_manual.sh kraft       # start KRaft, explore, tear down
#   ./run_manual.sh etcd        # start etcd, explore, tear down
#   ./run_manual.sh tendermint  # start CometBFT, explore, tear down
#   ./run_manual.sh paxos       # start Paxi Paxos, explore, tear down
#   ./run_manual.sh epaxos      # start Paxi EPaxos, explore, tear down
#   ./run_manual.sh hotstuff    # start HotStuff, run short benchmark
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/lib.sh"

SYSTEM="${1:?usage: $0 <kraft|zk|etcd|tendermint|paxos|epaxos|hotstuff>}"

case "$SYSTEM" in
  kraft)
    start_kraft 3
    echo ""
    echo "  KRaft cluster is running."
    echo "  Create topic:  docker exec k1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic test --partitions 3 --replication-factor 3"
    echo "  Produce:       docker exec k1 /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic test"
    echo "  Consume:       docker exec k1 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic test --from-beginning"
    echo "  Check quorum:  docker exec k1 /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe --status"
    ;;

  zk)
    start_zk
    echo ""
    echo "  Kafka+ZK cluster is running."
    echo "  Create topic:  docker exec zb1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic test --partitions 3 --replication-factor 3"
    echo "  ZK status:     echo stat | nc zookeeper 2181"
    ;;

  etcd)
    start_etcd 3
    echo ""
    echo "  etcd cluster is running."
    echo "  Put:           docker exec etcd1 etcdctl --endpoints=http://localhost:2379 put foo bar"
    echo "  Get:           docker exec etcd1 etcdctl --endpoints=http://localhost:2379 get foo"
    echo "  Members:       docker exec etcd1 etcdctl --endpoints=http://localhost:2379 member list"
    echo "  Status:        docker exec etcd1 etcdctl --endpoints=http://localhost:2379 endpoint status -w table"
    ;;

  tendermint)
    start_tendermint
    echo ""
    echo "  CometBFT cluster is running."
    echo "  Status:         curl -s http://localhost:26657/status | jq .result.sync_info"
    echo "  Send tx:        curl -s 'http://localhost:26657/broadcast_tx_commit?tx=0x68656c6c6f'"
    echo "  Validators:     curl -s http://localhost:26657/validators"
    echo "  Block height:   curl -s http://localhost:26657/block | jq .result.block.header.height"
    ;;

  paxos)
    start_paxi paxos
    echo ""
    echo "  Paxi Paxos cluster is running."
    echo "  PUT:  curl -X PUT -d 'hello' http://localhost:18080/mykey"
    echo "  GET:  curl http://localhost:18080/mykey"
    echo "  State: curl http://localhost:18080/state"
    ;;

  epaxos)
    start_paxi epaxos
    echo ""
    echo "  Paxi EPaxos cluster is running (leaderless)."
    echo "  PUT to any replica:  curl -X PUT -d 'hello' http://localhost:18080/mykey"
    echo "  GET from any:        curl http://localhost:18082/mykey"
    ;;

  hotstuff)
    start_hotstuff
    echo ""
    echo "  HotStuff container is running on loadgen."
    echo "  Run benchmark:  docker exec hotstuff /opt/hs-run.sh"
    echo "  Check summary:  docker exec hotstuff cat /work/out/fab.log | grep -A 15 SUMMARY"
    ;;
esac

echo ""
read -rp "Press Enter to tear down $SYSTEM... "
stop_system "$SYSTEM"
log "$SYSTEM torn down."
