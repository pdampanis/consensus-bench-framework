#!/usr/bin/env bash
# =============================================================================
# export_metrics.sh - export per-container CPU / mem / network for a run window
# =============================================================================
#
# Secondary metrics only. Primary metrics (throughput, latency) come from
# the perf tool directly and live in throughput.csv / latency.csv.
#
# Writes a small CSV to stdout with ~50 samples per container per metric.
# Typical size: 100 KB per run.
# =============================================================================
set -u

SYSTEM="${1:?usage: $0 <s> <start_epoch> <end_epoch>}"
START="${2:?}"
END="${3:?}"
PROM="${PROM_URL:-http://localhost:9090}"

# Container-name regex per system
case "$SYSTEM" in
  kraft)       NAME_RE='k[0-9]+' ;;
  zk)          NAME_RE='(zookeeper|zb[0-9]+)' ;;
  etcd)        NAME_RE='etcd[0-9]+' ;;
  tendermint)  NAME_RE='tm[0-9]+' ;;
  paxos|epaxos) NAME_RE='paxi[0-9]+' ;;
  hotstuff)    NAME_RE='hotstuff' ;;
  *)           echo "unknown system: $SYSTEM" >&2; exit 1 ;;
esac

STEP=10  # seconds

QUERIES=(
  "rate(container_cpu_usage_seconds_total[30s])"
  "container_memory_working_set_bytes"
  "rate(container_network_receive_bytes_total[30s])"
  "rate(container_network_transmit_bytes_total[30s])"
)
QUERY_NAMES=(
  "cpu_rate"
  "memory_bytes"
  "net_rx_rate"
  "net_tx_rate"
)

echo "timestamp,metric,container,value"
for i in "${!QUERIES[@]}"; do
  q="${QUERIES[$i]}"
  name="${QUERY_NAMES[$i]}"
  # The cAdvisor label `name` holds the container name for docker containers.
  # We filter to just SUT containers to keep the CSV small.
  full_query="(${q}) * on(name) group_left() (container_last_seen{name=~\"${NAME_RE}\"} > 0)"
  curl -sG "$PROM/api/v1/query_range" \
    --data-urlencode "query=${full_query}" \
    --data-urlencode "start=${START}" \
    --data-urlencode "end=${END}" \
    --data-urlencode "step=${STEP}s" \
  | jq -r --arg m "$name" '
      .data.result[]?
      | .metric.name as $c
      | .values[]
      | [.[0], $m, $c, .[1]] | @csv
    ' 2>/dev/null
done
