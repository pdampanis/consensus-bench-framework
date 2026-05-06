#!/usr/bin/env bash
# =============================================================================
# run_batch.sh - unattended sequential runner, v3
# =============================================================================
#
# Expanded in v3 to cover the full thesis title commitments:
#   - 4 systems: kraft, zk, etcd, tendermint (BFT, newly reactivated)
#   - 6 scenarios: baseline, leader_kill, double_kill, slow_node,
#                  packet_loss, network_partition
#   - Scalability sub-matrix: kraft + etcd at cluster sizes 3/5/7, baseline only
#   - Leader-election CDF: 200 trials per system across all 4 systems
#
# All 6 sessions are idempotent and can be interrupted/restarted without
# data loss. Completed cells (with status=complete in manifest.json) are
# skipped on re-run.
#
# Resource note: all sessions fit comfortably in 32 GB. Worst case is
# session4_scalability's 7-node KRaft which needs ~13.5 GB committed.
#
# Usage:
#   ./run_batch.sh session1        # kraft + zk main scenarios    (~5.3h)
#   ./run_batch.sh session2        # etcd main scenarios          (~2.7h)
#   ./run_batch.sh session3        # tendermint (BFT) main        (~2.7h)
#   ./run_batch.sh session4        # scalability sub-matrix       (~1.6h)
#   ./run_batch.sh session5        # deep faults (double/slow)    (~4.0h)
#   ./run_batch.sh session6        # leader-election CDFs         (~5.6h)
#   ./run_batch.sh all             # everything (~21h total)
#   ./run_batch.sh <s> <scenario>  # single cell, 5 runs
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
RESULTS="$ROOT/results"
LOG="$RESULTS/batch.log"

mkdir -p "$RESULTS"

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required. Install with: sudo apt install -y jq"
  exit 3
fi

trap 'log "interrupted; tearing down all SUTs"; teardown_all_suts; exit 130' INT TERM

log() { printf '[%s] %s\n' "$(date -Iseconds)" "$*" | tee -a "$LOG"; }

# ---------------------------------------------------------------------------
# Compose file selection: (system, cluster_size) → yml file
# ---------------------------------------------------------------------------
compose_file_for() {
  local sys="$1" size="${2:-3}"
  case "$sys:$size" in
    kraft:3)      echo "$ROOT/compose/kafka-kraft.yml" ;;
    kraft:5)      echo "$ROOT/compose/kafka-kraft-5.yml" ;;
    kraft:7)      echo "$ROOT/compose/kafka-kraft-7.yml" ;;
    zk:3)         echo "$ROOT/compose/kafka-zk.yml" ;;
    etcd:3)       echo "$ROOT/compose/etcd.yml" ;;
    etcd:5)       echo "$ROOT/compose/etcd-5.yml" ;;
    etcd:7)       echo "$ROOT/compose/etcd-7.yml" ;;
    tendermint:4) echo "$ROOT/compose/tendermint.yml" ;;
    tendermint:3) echo "$ROOT/compose/tendermint.yml" ;;  # tendermint is 4-node fixed
    paxos:3)      echo "$ROOT/compose/paxi-paxos.yml" ;;
    epaxos:3)     echo "$ROOT/compose/paxi-epaxos.yml" ;;
    hotstuff:4)   echo "$ROOT/compose/hotstuff.yml" ;;
    hotstuff:3)   echo "$ROOT/compose/hotstuff.yml" ;;   # hotstuff is 4-validator fixed
    *)            return 1 ;;
  esac
}

teardown_all_suts() {
  for f in \
    "$ROOT/compose/kafka-kraft.yml" \
    "$ROOT/compose/kafka-kraft-5.yml" \
    "$ROOT/compose/kafka-kraft-7.yml" \
    "$ROOT/compose/kafka-zk.yml" \
    "$ROOT/compose/etcd.yml" \
    "$ROOT/compose/etcd-5.yml" \
    "$ROOT/compose/etcd-7.yml" \
    "$ROOT/compose/tendermint.yml" \
    "$ROOT/compose/paxi-paxos.yml" \
    "$ROOT/compose/paxi-epaxos.yml" \
    "$ROOT/compose/hotstuff.yml"; do
    [ -f "$f" ] && docker compose -f "$f" down -v 2>/dev/null || true
  done
}

# ---------------------------------------------------------------------------
# Bring up + wait for healthy using jq for robust NDJSON/array parsing.
# ---------------------------------------------------------------------------
bring_up() {
  local system="$1" size="${2:-3}"
  local file; file="$(compose_file_for "$system" "$size")" || {
    log "unknown system/size: $system/$size"; return 1; }

  log "bringing up $system (cluster_size=$size)"
  docker compose -f "$file" up -d >> "$LOG" 2>&1

  local deadline=$(( $(date +%s) + 240 ))  # 4 minutes for larger clusters
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local stats
    stats=$(docker compose -f "$file" ps --format json 2>/dev/null \
      | jq -s '
          (if length == 1 and (.[0] | type) == "array" then .[0] else . end) as $rows
          | {
              total: ($rows | length),
              healthy: ($rows | map(select(.Health == "healthy")) | length),
              unhealthy: ($rows | map(select(.Health == "unhealthy")) | length)
            }
        ' 2>/dev/null)
    local total healthy unhealthy
    total=$(echo "$stats" | jq -r '.total // 0')
    healthy=$(echo "$stats" | jq -r '.healthy // 0')
    unhealthy=$(echo "$stats" | jq -r '.unhealthy // 0')

    if [ "$total" -gt 0 ] && [ "$healthy" -eq "$total" ] && [ "$unhealthy" -eq 0 ]; then
      log "$system: $healthy/$total healthy"
      sleep 20  # settle time for consensus to elect initial leader
      return 0
    fi
    sleep 5
  done

  log "ERROR: $system did not become healthy within 240s"
  docker compose -f "$file" ps >> "$LOG" 2>&1
  return 1
}

tear_down() {
  local system="$1" size="${2:-3}"
  local file; file="$(compose_file_for "$system" "$size")" || return 1
  log "tearing down $system"
  docker compose -f "$file" down -v >> "$LOG" 2>&1
  sleep 30
}

# ---------------------------------------------------------------------------
# Run one cell if not already complete.
# ---------------------------------------------------------------------------
run_cell() {
  local system="$1" scenario="$2" run_id="$3" size="${4:-3}"

  # Cell path encodes cluster_size for scalability runs so sizes don't collide.
  local cell_dir
  if [ "$size" -ne 3 ] && [ "$system" != "tendermint" ]; then
    cell_dir="$RESULTS/${system}_n${size}/$scenario/$run_id"
  else
    cell_dir="$RESULTS/$system/$scenario/$run_id"
  fi

  if [ -f "$cell_dir/manifest.json" ] && \
     jq -e '.status == "complete"' "$cell_dir/manifest.json" >/dev/null 2>&1; then
    log "SKIP $system/$scenario/$run_id (cluster_size=$size)"
    return 0
  fi

  mkdir -p "$cell_dir"
  log "START $system/$scenario/$run_id (cluster_size=$size)"

  # Spawn run_one.sh with the run results directed to the right cell dir.
  # run_one.sh internally writes to results/<s>/<scenario>/<run_id>/, so we
  # need to either (a) rewrite run_one.sh to accept a cell dir, or
  # (b) use a symlink for scalability variants. We go with (a):
  if [ "$size" -ne 3 ] && [ "$system" != "tendermint" ]; then
    # Write into the scalability-suffixed path by temporarily symlinking
    # results/<s>/ to results/<s>_nN/
    local canonical="$RESULTS/$system/$scenario/$run_id"
    mkdir -p "$RESULTS/$system/$scenario"
    ln -sfn "$cell_dir" "$canonical" 2>/dev/null || true
    "$HERE/run_one.sh" "$system" "$scenario" "$run_id" "$size" >> "$LOG" 2>&1
    local rc=$?
    rm -f "$canonical"
  else
    "$HERE/run_one.sh" "$system" "$scenario" "$run_id" "$size" >> "$LOG" 2>&1
    local rc=$?
  fi

  [ "$rc" -eq 0 ] && log "OK    $system/$scenario/$run_id" \
                   || log "FAIL  $system/$scenario/$run_id (rc=$rc)"
  return $rc
}

# Run n repeats of (system, scenario), keeping the stack up across repeats.
run_scenario_repeats() {
  local system="$1" scenario="$2" n="${3:-5}" size="${4:-3}"
  bring_up "$system" "$size" || { log "skipping $system/$scenario"; return 1; }
  for i in $(seq 1 "$n"); do
    run_cell "$system" "$scenario" "$(printf 'run%03d' "$i")" "$size"
  done
  tear_down "$system" "$size"
}

# ---------------------------------------------------------------------------
# Session definitions
# ---------------------------------------------------------------------------
# Statistical power: baseline scenarios run n=10 to give Mann-Whitney U
# adequate power (with n₁=n₂=10, minimum detectable Vargha-Delaney A ≈ 0.78
# at α=0.05, vs A ≈ 0.90 with n=5). Fault scenarios run n=5 since the
# primary thesis claims (pairwise comparisons, CIs) are built on baseline.
BASELINE_N=10
FAULT_N=5
SCALABILITY_N=3
LE_TRIALS=200

# ---------------------------------------------------------------------------
# Network baseline calibration
# ---------------------------------------------------------------------------
calibrate_network() {
  local out="$RESULTS/environment.txt"
  log "=== Network baseline calibration ==="
  {
    echo "# Environment — $(date -Iseconds)"
    echo "hostname=$(hostname)"
    echo "kernel=$(uname -r)"
    echo "cpu=$(lscpu 2>/dev/null | grep 'Model name' | cut -d: -f2 | xargs || echo unknown)"
    echo "memory=$(free -h 2>/dev/null | awk '/^Mem:/{print $2}' || echo unknown)"
    echo "docker=$(docker --version 2>/dev/null || echo not-installed)"
    echo "deploy_mode=${DEPLOY_MODE:-local}"
    echo ""
    echo "# Inter-node latency (10 pings each, ms)"
  } > "$out"

  # VM deployment: ping across private network using /etc/hosts resolution.
  # Local deployment: ping across Docker thesis-net using an alpine container.
  if [ "${DEPLOY_MODE:-local}" = "vm" ]; then
    for host in k1 k2 k3 etcd1 etcd2 etcd3 tm1 tm2 paxi1 paxi2 hotstuff; do
      result=$(ping -c 10 -q "$host" 2>/dev/null | tail -1 | awk -F/ '{print $5}' || echo "FAIL")
      echo "$host: ${result:-FAIL}ms" >> "$out"
    done
  else
    docker run --rm --network thesis-net alpine:3.19 \
      sh -c 'for host in k1 k2 k3 etcd1 etcd2 etcd3 tm1 tm2 paxi1 paxi2 hotstuff; do
        result=$(ping -c 10 -q $host 2>/dev/null | tail -1 | awk -F/ '"'"'{print $5}'"'"' || echo "FAIL")
        echo "$host: ${result:-FAIL}ms"
      done' >> "$out" 2>/dev/null || true
  fi

  log "Network baseline written to $out"
}

SCENARIOS_MAIN=(baseline leader_kill packet_loss network_partition)
SCENARIOS_DEEP=(double_kill slow_node)

session1_kraft_zk() {
  log "=== SESSION 1: kraft + zk, main scenarios ==="
  for system in kraft zk; do
    for scenario in "${SCENARIOS_MAIN[@]}"; do
      local n=$FAULT_N
      [ "$scenario" = "baseline" ] && n=$BASELINE_N
      run_scenario_repeats "$system" "$scenario" "$n" 3
    done
  done
  log "=== SESSION 1 DONE ==="
}

session2_etcd() {
  log "=== SESSION 2: etcd, main scenarios ==="
  for scenario in "${SCENARIOS_MAIN[@]}"; do
    local n=$FAULT_N
    [ "$scenario" = "baseline" ] && n=$BASELINE_N
    run_scenario_repeats "etcd" "$scenario" "$n" 3
  done
  log "=== SESSION 2 DONE ==="
}

session3_tendermint() {
  log "=== SESSION 3: tendermint (BFT), main scenarios ==="
  for scenario in "${SCENARIOS_MAIN[@]}"; do
    local n=$FAULT_N
    [ "$scenario" = "baseline" ] && n=$BASELINE_N
    run_scenario_repeats "tendermint" "$scenario" "$n" 4
  done
  log "=== SESSION 3 DONE ==="
}

session4_scalability() {
  log "=== SESSION 4: scalability (kraft + etcd at sizes 5 and 7) ==="
  for system in kraft etcd; do
    for size in 5 7; do
      run_scenario_repeats "$system" "baseline" "$SCALABILITY_N" "$size"
    done
  done
  log "=== SESSION 4 DONE ==="
}

session5_deep_faults() {
  log "=== SESSION 5: deep fault scenarios (double_kill + slow_node) ==="
  for system in kraft zk etcd tendermint paxos epaxos hotstuff; do
    local s=3
    [ "$system" = "tendermint" ] && s=4
    [ "$system" = "hotstuff" ] && s=4
    for scenario in "${SCENARIOS_DEEP[@]}"; do
      run_scenario_repeats "$system" "$scenario" "$FAULT_N" "$s"
    done
  done
  log "=== SESSION 5 DONE ==="
}

session6_leader_election() {
  log "=== SESSION 6: leader-election CDFs ==="
  for system in kraft zk etcd tendermint paxos epaxos hotstuff; do
    local s=3
    [ "$system" = "tendermint" ] && s=4
    [ "$system" = "hotstuff" ] && s=4
    bring_up "$system" "$s" || continue
    for i in $(seq 1 $LE_TRIALS); do
      "$HERE/run_le_trial.sh" "$system" "$(printf 'le%03d' "$i")" \
        >> "$LOG" 2>&1 || true
    done
    tear_down "$system" "$s"
  done
  log "=== SESSION 6 DONE ==="
}

session7_paxi() {
  log "=== SESSION 7: paxi (paxos + epaxos), main scenarios ==="
  for system in paxos epaxos; do
    for scenario in "${SCENARIOS_MAIN[@]}"; do
      local n=$FAULT_N
      [ "$scenario" = "baseline" ] && n=$BASELINE_N
      run_scenario_repeats "$system" "$scenario" "$n" 3
    done
  done
  log "=== SESSION 7 DONE ==="
}

session8_hotstuff() {
  log "=== SESSION 8: hotstuff, main scenarios ==="
  for scenario in "${SCENARIOS_MAIN[@]}"; do
    local n=$FAULT_N
    [ "$scenario" = "baseline" ] && n=$BASELINE_N
    run_scenario_repeats "hotstuff" "$scenario" "$n" 4
  done
  log "=== SESSION 8 DONE ==="
}

all_sessions() {
  calibrate_network
  session1_kraft_zk
  session2_etcd
  session3_tendermint
  session7_paxi
  session8_hotstuff
  session4_scalability
  session5_deep_faults
  session6_leader_election
}

# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------
case "${1:-}" in
  session1) session1_kraft_zk ;;
  session2) session2_etcd ;;
  session3) session3_tendermint ;;
  session4) session4_scalability ;;
  session5) session5_deep_faults ;;
  session6|leader_election) session6_leader_election ;;
  session7) session7_paxi ;;
  session8) session8_hotstuff ;;
  all) all_sessions ;;
  "")
    echo "usage: $0 session1|session2|session3|session4|session5|session6|session7|session8|all|<s> <scenario>"
    exit 2 ;;
  *)
    [ $# -eq 2 ] || { echo "usage: ..."; exit 2; }
    cli_size=3
    [ "$1" = "tendermint" ] && cli_size=4
    [ "$1" = "hotstuff" ] && cli_size=4
    cli_n=$FAULT_N
    [ "$2" = "baseline" ] && cli_n=$BASELINE_N
    run_scenario_repeats "$1" "$2" "$cli_n" "$cli_size" ;;
esac

log "batch exit"
