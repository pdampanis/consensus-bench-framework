#!/usr/bin/env bash
# =============================================================================
# run_one.sh - execute ONE (system, scenario, run_id) matrix cell
# =============================================================================
#
# All four systems produce the same result structure:
#   results/<s>/<scenario>/<run_id>/
#     manifest.json       run metadata (system, scenario, times, status)
#     throughput.csv      timestamp,ops_per_sec (one row per bucket)
#     latency.csv         metric,value_us (avg, p50, p95, p99, p99.9, max)
#     correctness.txt     durability check result (PASS / FAIL / SKIP)
#     run.log             combined log output
#
# Perf tools used (one per system):
#   kraft, zk   - kafka-producer-perf-test.sh (ships inside apache/kafka)
#   etcd        - /usr/local/bin/benchmark    (ships inside coreos/etcd)
#   tendermint  - cmt_bench.py                (ships in le-probe image)
#
# All three tools' raw outputs are normalized into the same two-file
# format by parse_results.sh, so downstream code is system-agnostic.
#
# Scenarios dispatch to scripts in scripts/pumba/.
# =============================================================================
set -uo pipefail

SYSTEM="${1:?usage: $0 <s> <scenario> <run_id> [cluster_size]}"
SCENARIO="${2:?}"
RUN_ID="${3:?}"
CLUSTER_SIZE="${4:-3}"  # default 3; scalability runs override to 5 or 7

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT="$ROOT/results/$SYSTEM/$SCENARIO/$RUN_ID"
mkdir -p "$OUT"

WARMUP_SECS=180
MEASURE_SECS=300
TOTAL_SECS=$(( WARMUP_SECS + MEASURE_SECS ))

START_EPOCH=$(date +%s)
START_ISO=$(date -Iseconds)

log() { printf '[%s] %s\n' "$(date -Iseconds)" "$*" | tee -a "$OUT/run.log"; }

# ---------------------------------------------------------------------------
# Per-system configuration. The BOOTSTRAP variable is used by Kafka; the
# ETCD_ENDPOINTS by etcd; TM_RPC_URL by tendermint.
# ---------------------------------------------------------------------------
case "$SYSTEM" in
  kraft)
    if [ "$CLUSTER_SIZE" -eq 3 ]; then
      BOOTSTRAP="k1:9092,k2:9092,k3:9092"
    elif [ "$CLUSTER_SIZE" -eq 5 ]; then
      BOOTSTRAP="k1:9092,k2:9092,k3:9092,k4:9092,k5:9092"
    else
      BOOTSTRAP="k1:9092,k2:9092,k3:9092,k4:9092,k5:9092,k6:9092,k7:9092"
    fi
    FIRST_NODE="k1"
    ;;
  zk)
    BOOTSTRAP="zb1:9092,zb2:9092,zb3:9092"
    FIRST_NODE="zb1"
    ;;
  etcd)
    if [ "$CLUSTER_SIZE" -eq 3 ]; then
      ETCD_ENDPOINTS="http://etcd1:2379,http://etcd2:2379,http://etcd3:2379"
    elif [ "$CLUSTER_SIZE" -eq 5 ]; then
      ETCD_ENDPOINTS="http://etcd1:2379,http://etcd2:2379,http://etcd3:2379,http://etcd4:2379,http://etcd5:2379"
    else
      ETCD_ENDPOINTS="http://etcd1:2379,http://etcd2:2379,http://etcd3:2379,http://etcd4:2379,http://etcd5:2379,http://etcd6:2379,http://etcd7:2379"
    fi
    FIRST_NODE="etcd1"
    ;;
  tendermint)
    TM_RPC_URL="http://tm1:26657"
    FIRST_NODE="tm1"
    ;;
  paxos|epaxos)
    # Paxi serves its HTTP API on port 8080 inside each replica. The
    # benchmark driver connects to paxi1 and Paxi's internal forwarding
    # redirects requests to the leader (for paxos) or the opportunistic
    # leader for each command (for epaxos).
    PAXI_BASE_URL="http://paxi1:8080"
    FIRST_NODE="paxi1"
    ;;
  hotstuff)
    # HotStuff runs 4 validator processes inside ONE container. The
    # benchmark is orchestrated by hs-run.sh inside the container,
    # invoked here via `docker exec`. Fault injection likewise uses
    # `docker exec` with hs-inject.sh instead of Pumba. See
    # docs/algorithms/hotstuff.md for the methodology rationale.
    FIRST_NODE="hotstuff"
    ;;
  *)
    echo "unknown system: $SYSTEM" >&2; exit 1 ;;
esac

log "START $SYSTEM/$SCENARIO/$RUN_ID (cluster_size=$CLUSTER_SIZE)"

# ---------------------------------------------------------------------------
# Kafka path
# ---------------------------------------------------------------------------
if [ "$SYSTEM" = "kraft" ] || [ "$SYSTEM" = "zk" ]; then
  log "creating topic 'bench'"
  docker exec "$FIRST_NODE" /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --create --if-not-exists \
    --topic bench --partitions 6 --replication-factor "$CLUSTER_SIZE" \
    >> "$OUT/run.log" 2>&1 || true

  log "starting kafka-producer-perf-test ($TOTAL_SECS sec budget)"
  ( docker exec "$FIRST_NODE" timeout "$TOTAL_SECS" \
      /opt/kafka/bin/kafka-producer-perf-test.sh \
      --topic bench \
      --num-records 100000000 \
      --throughput -1 \
      --record-size 1024 \
      --producer-props \
          bootstrap.servers=localhost:9092 \
          acks=all \
          linger.ms=5 \
          batch.size=65536 \
          compression.type=none \
      > "$OUT/perf-raw.txt" 2>&1
  ) &
  PERF_PID=$!
fi

# ---------------------------------------------------------------------------
# etcd path
# ---------------------------------------------------------------------------
if [ "$SYSTEM" = "etcd" ]; then
  log "starting etcd benchmark ($TOTAL_SECS sec budget)"
  docker run --rm --network thesis-net \
    --name "etcd-bench-${RUN_ID}" \
    quay.io/coreos/etcd:v3.5.15 \
    timeout "$TOTAL_SECS" \
    /usr/local/bin/benchmark \
      --endpoints="$ETCD_ENDPOINTS" \
      --conns=6 --clients=6 \
      put --total=100000000 \
          --val-size=1024 \
          --sequential-keys \
    > "$OUT/perf-raw.txt" 2>&1 &
  PERF_PID=$!
fi

# ---------------------------------------------------------------------------
# CometBFT / Tendermint path
# ---------------------------------------------------------------------------
if [ "$SYSTEM" = "tendermint" ]; then
  log "starting cmt_bench ($TOTAL_SECS sec budget)"
  ( docker run --rm --network thesis-net \
      --name "cmt-bench-${RUN_ID}" \
      -v "$OUT:/out" \
      le-probe:latest \
      python3 /cmt_bench.py \
        --rpc-url "$TM_RPC_URL" \
        --duration-sec "$TOTAL_SECS" \
        --clients 6 \
        --val-size 1024 \
        --out-raw /out/perf-raw.txt \
      > /dev/null 2>&1
  ) &
  PERF_PID=$!
fi

# ---------------------------------------------------------------------------
# Paxi path (paxos or epaxos)
# ---------------------------------------------------------------------------
if [ "$SYSTEM" = "paxos" ] || [ "$SYSTEM" = "epaxos" ]; then
  log "starting paxi_bench ($TOTAL_SECS sec budget)"
  ( docker run --rm --network thesis-net \
      --name "paxi-bench-${RUN_ID}" \
      -v "$OUT:/out" \
      le-probe:latest \
      python3 /paxi_bench.py \
        --base-url "$PAXI_BASE_URL" \
        --duration-sec "$TOTAL_SECS" \
        --clients 6 \
        --val-size 1024 \
        --out-raw /out/perf-raw.txt \
      > /dev/null 2>&1
  ) &
  PERF_PID=$!
fi

# ---------------------------------------------------------------------------
# HotStuff path: the benchmark is orchestrated INSIDE the container by
# hs-run.sh which shells out to `fab local` from the vendored asonnino
# repo. We `docker exec` the runner and wait for it to produce
# /work/out/perf-raw.txt, which we then `docker cp` into $OUT.
# This block self-manages the duration by asking hs-run.sh to use
# TOTAL_SECS as its budget, so the warmup+fault-injection loop below
# is a no-op for hotstuff (the fault is applied inside the container
# via hs-inject.sh at a point determined by WARMUP_SECS below).
# ---------------------------------------------------------------------------
if [ "$SYSTEM" = "hotstuff" ]; then
  log "starting hs-run ($TOTAL_SECS sec budget, inside container)"
  ( docker exec \
      -e HS_DURATION_SEC="$TOTAL_SECS" \
      -e HS_RATE=1000 \
      -e HS_TX_SIZE=512 \
      -e HS_OUT_DIR=/work/out \
      hotstuff \
      /opt/hs-run.sh > /dev/null 2>&1
    # After hs-run.sh finishes, copy its output back into $OUT so the
    # rest of run_one.sh (parse_results.sh etc.) sees it like any
    # other system's output.
    docker cp hotstuff:/work/out/perf-raw.txt "$OUT/perf-raw.txt" 2>/dev/null || true
    docker cp hotstuff:/work/out/fab.log      "$OUT/fab.log"      2>/dev/null || true
  ) &
  PERF_PID=$!
fi

# ---------------------------------------------------------------------------
# Warmup + fault injection at warmup boundary + measurement window
# ---------------------------------------------------------------------------
log "warmup ${WARMUP_SECS}s"
sleep "$WARMUP_SECS"

FAULT_ISO=$(date -Iseconds)
log "fault: $SCENARIO"

# Fault injection path: use VM-aware SSH-based faults when running on
# Hetzner cloud (DEPLOY_MODE=vm), otherwise use local Pumba scripts.
if [ "${DEPLOY_MODE:-local}" = "vm" ] && [ "$SCENARIO" != "baseline" ]; then
  # The deploy/ dir is at the thesis bundle root (same level as scripts/)
  FAULTS_SCRIPT="$ROOT/deploy/faults.sh"
  if [ -x "$FAULTS_SCRIPT" ]; then
    "$FAULTS_SCRIPT" "$SCENARIO" "$SYSTEM" "$MEASURE_SECS" >> "$OUT/run.log" 2>&1 &
    FAULT_PID=$!
  else
    log "WARN: VM fault script not found at $FAULTS_SCRIPT, skipping fault injection"
  fi
else
  # HotStuff does fault injection inside the container (4 processes in
  # one container means Pumba targeting the container would affect them
  # all at once). Route through hs-inject.sh for every non-baseline
  # scenario.
  if [ "$SYSTEM" = "hotstuff" ] && [ "$SCENARIO" != "baseline" ]; then
    docker exec hotstuff /opt/hs-inject.sh "$SCENARIO" \
      >> "$OUT/run.log" 2>&1 || true
  else
    case "$SCENARIO" in
      baseline)
        : ;;
      leader_kill)
        "$HERE/pumba/leader_kill.sh" "$SYSTEM" >> "$OUT/run.log" 2>&1 || true ;;
      double_kill)
        "$HERE/pumba/double_kill.sh" "$SYSTEM" >> "$OUT/run.log" 2>&1 || true ;;
      packet_loss)
        "$HERE/pumba/packet_loss.sh" "$SYSTEM" "$MEASURE_SECS" >> "$OUT/run.log" 2>&1 & ;;
      network_partition)
        "$HERE/pumba/network_partition.sh" "$SYSTEM" "$MEASURE_SECS" >> "$OUT/run.log" 2>&1 & ;;
      slow_node)
        "$HERE/pumba/slow_node.sh" "$SYSTEM" "$MEASURE_SECS" >> "$OUT/run.log" 2>&1 & ;;
      *)
        log "unknown scenario: $SCENARIO" ;;
    esac
  fi
fi

log "measure ${MEASURE_SECS}s"
wait "$PERF_PID" 2>/dev/null || true
PERF_RC=$?

END_ISO=$(date -Iseconds)

# ---------------------------------------------------------------------------
# Parse the perf tool's raw output into the canonical CSVs.
# ---------------------------------------------------------------------------
log "parsing results"
"$HERE/parse_results.sh" "$SYSTEM" "$OUT" >> "$OUT/run.log" 2>&1

# ---------------------------------------------------------------------------
# Correctness check: did the cluster retain the writes we made?
# For each system, count the records and compare to what the perf tool
# reported as "sent". The check is meaningful only for baseline, where
# no writes should be lost; for fault scenarios we expect some loss.
# ---------------------------------------------------------------------------
log "correctness check"
CORRECTNESS="SKIP"
if [ "$SCENARIO" = "baseline" ] && [ -f "$OUT/perf-raw.txt" ]; then
  SENT=$(grep -oE '^[0-9]+ records sent' "$OUT/perf-raw.txt" | tail -1 | awk '{print $1}')
  RECEIVED=0

  case "$SYSTEM" in
    kraft|zk)
      # For Kafka, read the end offsets of 'bench' across all partitions
      # and sum them. This approximates the count of committed records.
      # Connect to a SURVIVING broker (not FIRST_NODE if it was killed).
      for N in $FIRST_NODE k2 k3 zb2 zb3; do
        [ -z "$(docker inspect "$N" 2>/dev/null)" ] && continue
        RECEIVED=$(docker exec "$N" /opt/kafka/bin/kafka-run-class.sh \
          kafka.tools.GetOffsetShell \
          --broker-list localhost:9092 --topic bench 2>/dev/null \
          | awk -F: '{sum += $3} END {print sum+0}')
        [ -n "$RECEIVED" ] && [ "$RECEIVED" -gt 0 ] && break
      done
      ;;
    etcd)
      # For etcd, count keys via a prefix scan. The benchmark tool with
      # --sequential-keys writes keys of the form "0000...0000000000"
      # through increasing decimal. A range of "\x00" to "\xff" covers all.
      RECEIVED=$(docker exec etcd1 etcdctl \
        --endpoints=http://localhost:2379 \
        get "" --prefix --keys-only 2>/dev/null | wc -l)
      ;;
    tendermint)
      # For CometBFT, count committed transactions via the latest
      # block height (each block has at least one tx, often more).
      # Rough proxy — the key metric is just "did any survive".
      H=$(curl -s http://localhost:26657/status 2>/dev/null \
          | grep -oE '"latest_block_height":"[0-9]+"' \
          | head -1 | grep -oE '[0-9]+')
      [ -n "$H" ] && RECEIVED="$H"
      ;;
    paxos|epaxos)
      # For Paxi, do a small number of GETs against known keys that
      # paxi_bench.py wrote. We cannot enumerate all keys (no scan API),
      # but we can verify a sample survives. Success = at least one
      # known key returns 200. Count those successes.
      RECEIVED=0
      for w in 0 1 2 3 4 5; do
        for k in 10 100 500; do
          if curl -sf -o /dev/null "http://localhost:18080/w${w}k${k}" 2>/dev/null; then
            RECEIVED=$((RECEIVED + 1))
          fi
        done
      done
      # Scale the small sample up to make the ratio comparable to other
      # systems. If all 18 sample keys are present, we consider it PASS.
      if [ "$RECEIVED" -ge 15 ]; then
        SENT=18
        RECEIVED=$RECEIVED
      fi
      ;;
    hotstuff)
      # HotStuff's asonnino implementation does not expose a read API —
      # there is no "get this key back" endpoint. The benchmark is a
      # closed-loop measurement: the client sends txns, the validators
      # commit them, and the fabric harness reports end-to-end TPS.
      #
      # Correctness assessment: verify the benchmark completed normally
      # (SUMMARY block present, TPS above a noise floor). This is an
      # execution-completeness check, NOT a durability check. Document
      # this limitation in the thesis methodology chapter.
      if [ -f "$OUT/fab.log" ]; then
        if grep -q "End-to-end TPS:" "$OUT/fab.log"; then
          TPS_VAL=$(grep "End-to-end TPS:" "$OUT/fab.log" \
                    | head -1 | grep -oE '[0-9,]+' | head -1 | tr -d ',')
          CONSENSUS_TPS=$(grep "Consensus TPS:" "$OUT/fab.log" \
                    | head -1 | grep -oE '[0-9,]+' | head -1 | tr -d ',')
          if [ -n "$TPS_VAL" ] && [ "$TPS_VAL" -gt 0 ]; then
            SENT="N/A"
            RECEIVED="N/A"
            CORRECTNESS="NO_DURABILITY_API (benchmark completed, e2e_tps=$TPS_VAL, consensus_tps=${CONSENSUS_TPS:-N/A})"
          else
            CORRECTNESS="NO_DURABILITY_API (benchmark ran but zero TPS — possible crash)"
          fi
        else
          CORRECTNESS="NO_DURABILITY_API (benchmark did not produce SUMMARY — check fab.log)"
        fi
      else
        CORRECTNESS="NO_DURABILITY_API (no fab.log — benchmark did not execute)"
      fi
      ;;
  esac

  # PASS iff at least 95% of sent records are retrievable. 95% rather
  # than 100% because the final in-flight batch can legitimately be
  # uncommitted when the producer exits.
  if [ -n "$SENT" ] && [ "$SENT" -gt 0 ] && [ "$RECEIVED" -gt 0 ]; then
    RATIO_X100=$(( RECEIVED * 100 / SENT ))
    if [ "$RATIO_X100" -ge 95 ]; then
      CORRECTNESS="PASS ($RECEIVED/$SENT = ${RATIO_X100}%)"
    else
      CORRECTNESS="FAIL ($RECEIVED/$SENT = ${RATIO_X100}%)"
    fi
  else
    CORRECTNESS="INDETERMINATE (sent=$SENT received=$RECEIVED)"
  fi
fi
echo "$CORRECTNESS" > "$OUT/correctness.txt"
log "correctness: $CORRECTNESS"

# ---------------------------------------------------------------------------
# Manifest
# ---------------------------------------------------------------------------
NROWS=$(wc -l < "$OUT/throughput.csv" 2>/dev/null || echo 0)
STATUS="complete"
# double_kill is a special case: we EXPECT zero throughput for the rest
# of the measurement window, so 'zero rows' is actually success.
if [ "$SCENARIO" != "double_kill" ] && [ "$NROWS" -lt 10 ]; then
  STATUS="failed"
fi

cat > "$OUT/manifest.json" <<EOF
{
  "system": "$SYSTEM",
  "scenario": "$SCENARIO",
  "run_id": "$RUN_ID",
  "cluster_size": $CLUSTER_SIZE,
  "started_at": "$START_ISO",
  "fault_injected_at": "$FAULT_ISO",
  "ended_at": "$END_ISO",
  "warmup_secs": $WARMUP_SECS,
  "measure_secs": $MEASURE_SECS,
  "perf_rc": $PERF_RC,
  "throughput_rows": $NROWS,
  "correctness": "$(echo "$CORRECTNESS" | sed 's/"/\\"/g')",
  "status": "$STATUS",
  "kernel": "$(uname -r)",
  "docker": "$(docker --version | head -c 80)"
}
EOF

log "done ($STATUS, $NROWS throughput samples, correctness=$CORRECTNESS)"
[ "$STATUS" = "complete" ]
