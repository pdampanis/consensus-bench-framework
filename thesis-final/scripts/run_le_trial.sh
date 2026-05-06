#!/usr/bin/env bash
# =============================================================================
# run_le_trial.sh - ONE leader-election failover-time trial
# =============================================================================
#
# Procedure:
#   1. Start an external probe container that issues PUTs at 50 Hz.
#   2. Sleep 3 seconds to let the probe establish a steady baseline.
#   3. Kill the current leader/proposer, recording t_kill.
#   4. Wait for the probe to finish (total duration 20s).
#   5. Compute failover from the probe CSV:
#        t_first_success_after_kill - t_kill
#   6. Restart the killed container so the next trial starts from a
#      healthy quorum.
#
# Because the probe runs in its own container on thesis-net, it survives
# the kill. Because we measure time-to-first-success, the measurement
# reflects actual consensus recovery.
# =============================================================================
set -u

SYSTEM="${1:?usage: $0 <s> <run_id>}"
RUN_ID="${2:?}"

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT="$ROOT/results/$SYSTEM/leader_election/$RUN_ID"
mkdir -p "$OUT"

# Case-based compose file lookup, no substitution tricks (fixes the v2
# substring-replacement bug that produced non-existent paths).
case "$SYSTEM" in
  kraft)
    ENDPOINTS="k1:9092,k2:9092,k3:9092"
    COMPOSE_FILE="$ROOT/compose/kafka-kraft.yml"
    docker exec k1 /opt/kafka/bin/kafka-topics.sh \
      --bootstrap-server localhost:9092 \
      --create --if-not-exists \
      --topic bench --partitions 6 --replication-factor 3 \
      >/dev/null 2>&1 || true
    ;;
  zk)
    ENDPOINTS="zb1:9092,zb2:9092,zb3:9092"
    COMPOSE_FILE="$ROOT/compose/kafka-zk.yml"
    docker exec zb1 /opt/kafka/bin/kafka-topics.sh \
      --bootstrap-server localhost:9092 \
      --create --if-not-exists \
      --topic bench --partitions 6 --replication-factor 3 \
      >/dev/null 2>&1 || true
    ;;
  etcd)
    ENDPOINTS="http://etcd1:2379,http://etcd2:2379,http://etcd3:2379"
    COMPOSE_FILE="$ROOT/compose/etcd.yml"
    ;;
  tendermint)
    ENDPOINTS="http://tm1:26657,http://tm2:26657,http://tm3:26657,http://tm4:26657"
    COMPOSE_FILE="$ROOT/compose/tendermint.yml"
    ;;
  paxos)
    ENDPOINTS="http://paxi1:8080,http://paxi2:8080,http://paxi3:8080"
    COMPOSE_FILE="$ROOT/compose/paxi-paxos.yml"
    ;;
  epaxos)
    ENDPOINTS="http://paxi1:8080,http://paxi2:8080,http://paxi3:8080"
    COMPOSE_FILE="$ROOT/compose/paxi-epaxos.yml"
    ;;
  hotstuff)
    # HotStuff LE trials are done entirely inside the container via
    # hs-le-probe.sh. The compose file is used only for container
    # lifecycle management. No external probe is needed because the
    # asonnino client binary has no stable network API the le-probe
    # image could hit — the test and measurement both happen inside
    # the hotstuff container.
    ENDPOINTS="N/A"
    COMPOSE_FILE="$ROOT/compose/hotstuff.yml"

    # Run the in-container LE probe. Produces trial.txt directly.
    FAILOVER=$(docker exec hotstuff /opt/hs-le-probe.sh 2>/dev/null | tail -1)
    if [ -z "$FAILOVER" ] || [ "$FAILOVER" = "timeout" ]; then
      echo "$SYSTEM,$RUN_ID,timeout" > "$OUT/trial.txt"
    else
      echo "$SYSTEM,$RUN_ID,$FAILOVER" > "$OUT/trial.txt"
    fi
    # HotStuff container is self-contained; no restart needed between
    # trials because each trial spawns its own 4-validator testbed via
    # `fab local` and tears it down at the end.
    exit 0
    ;;
  *)
    echo "unknown system: $SYSTEM" >&2; exit 1 ;;
esac

PROBE_NAME="le-probe-${SYSTEM}-${RUN_ID}"
PROBE_DURATION=20

# Start the probe in a detached container bind-mounted at /probe.
docker run -d \
  --name "$PROBE_NAME" \
  --network thesis-net \
  -v "$OUT:/probe" \
  le-probe:latest \
  python3 /le_probe.py \
    --system "$SYSTEM" \
    --endpoints "$ENDPOINTS" \
    --duration-sec "$PROBE_DURATION" \
    --out /probe/probe.csv \
  >/dev/null

# Baseline window
sleep 3

# Kill the leader/proposer at a known time (ns)
T_KILL_NS=$(date +%s%N)
"$HERE/pumba/leader_kill.sh" "$SYSTEM" > "$OUT/kill.log" 2>&1

# Wait for the probe to finish
docker wait "$PROBE_NAME" >/dev/null 2>&1
docker logs "$PROBE_NAME" > "$OUT/probe.log" 2>&1 || true
docker rm -f "$PROBE_NAME" >/dev/null 2>&1

# Compute failover
if [ ! -s "$OUT/probe.csv" ]; then
  echo "$SYSTEM,$RUN_ID,timeout" > "$OUT/trial.txt"
else
  python3 - "$OUT/probe.csv" "$T_KILL_NS" <<'PY' > "$OUT/trial.txt.tmp"
import sys, csv
csv_path, t_kill_ns = sys.argv[1], int(sys.argv[2])
with open(csv_path) as f:
    r = csv.DictReader(f)
    for row in r:
        ts = int(row["timestamp_ns"])
        ok = int(row["success"])
        if ts > t_kill_ns and ok == 1:
            print(f"{(ts - t_kill_ns) / 1e9:.6f}")
            break
    else:
        print("timeout")
PY
  RECOVERED=$(cat "$OUT/trial.txt.tmp")
  echo "$SYSTEM,$RUN_ID,$RECOVERED" > "$OUT/trial.txt"
  rm -f "$OUT/trial.txt.tmp"
fi

# Restart the killed container so the next trial starts from quorum.
docker compose -f "$COMPOSE_FILE" start >/dev/null 2>&1 || true
sleep 5
