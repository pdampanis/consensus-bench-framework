#!/usr/bin/env bash
# =============================================================================
# run_campaign.sh — unattended benchmark campaign across 5 Hetzner VMs
# =============================================================================
#
# Runs the full campaign (8 sessions, ~30h) across the 5-node cluster.
# Idempotent — completed cells are skipped on re-run.
#
# Architecture:
#   - Consensus nodes (node1-4) run system containers with --network host
#   - Loadgen runs benchmark clients and orchestrates via SSH
#   - Inter-container communication uses /etc/hosts → private IPs
#   - Results are written to loadgen:/root/thesis-final/results/
#
# Usage:
#   ./run_campaign.sh              # full campaign (all 8 sessions)
#   ./run_campaign.sh session1     # single session
#   ./run_campaign.sh kraft baseline  # single cell
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/lib.sh"

REMOTE_DIR="/root/thesis-final"
LOG="$HERE/campaign.log"
RESULTS_LOCAL="$LOCAL_RESULTS_DIR"

mkdir -p "$RESULTS_LOCAL"
exec &> >(tee -a "$LOG")

# ---------------------------------------------------------------------------
# Delegate to run_batch.sh on loadgen
# ---------------------------------------------------------------------------
run_on_loadgen() {
  $SSH "${SSH_USER:-root}@${LOADGEN}" "cd $REMOTE_DIR && DEPLOY_MODE=vm $*"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
SESSION="${1:-all}"

case "$SESSION" in
  all)
    log "=== FULL CAMPAIGN (8 sessions) ==="
    for s in session1 session2 session3 session7 session8 session4 session5 session6; do
      log ">>> $s starting"
      run_on_loadgen "./scripts/run_batch.sh $s" || log "ERROR in $s (continuing)"
    done
    ;;
  session1|session2|session3|session4|session5|session6|session7|session8)
    log "=== $SESSION ==="
    run_on_loadgen "./scripts/run_batch.sh $SESSION"
    ;;
  *)
    [ $# -ge 2 ] || { echo "usage: $0 [all|session1-8|<system> <scenario>]"; exit 2; }
    log "=== Single cell: $SESSION $2 ==="
    run_on_loadgen "./scripts/run_batch.sh $SESSION $2"
    ;;
esac

log "=== Campaign finished ==="
log "Download results: ./deploy/download_results.sh"
