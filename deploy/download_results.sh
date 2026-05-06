#!/usr/bin/env bash
# =============================================================================
# download_results.sh — rsync benchmark results from loadgen to local
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
INVENTORY="$HERE/inventory.env"
source "$INVENTORY"

SCP="scp -i $SSH_KEY -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10"
SSH="ssh -i $SSH_KEY -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -o BatchMode=yes"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
DEST="${LOCAL_RESULTS_DIR}-${TIMESTAMP}"

log() { printf '[%s] %s\n' "$(date -Iseconds)" "$*"; }

log "downloading results from $LOADGEN to $DEST"

# Check results exist
if ! $SSH "${SSH_USER}@${LOADGEN}" "test -d /root/thesis-final/results" 2>/dev/null; then
  echo "ERROR: no results directory on loadgen. Campaign may not have been run."
  exit 1
fi

# Check campaign completion
RUNS=$($SSH "${SSH_USER}@${LOADGEN}" \
  "find /root/thesis-final/results -name 'manifest.json' -exec jq -r '.status' {} \; 2>/dev/null | sort | uniq -c" 2>/dev/null || echo "unknown")
log "Run statuses on loadgen:"
echo "$RUNS"

# Rsync results
mkdir -p "$DEST"
rsync -avz -e "ssh -i $SSH_KEY -o StrictHostKeyChecking=accept-new" \
  "${SSH_USER}@${LOADGEN}:/root/thesis-final/results/" \
  "$DEST/"

TOTAL=$(find "$DEST" -name "manifest.json" | wc -l)
log "Downloaded $TOTAL manifest files to $DEST"
log "Run analysis: python3 analysis/analyse.py --results-dir $DEST --out analysis/out"
