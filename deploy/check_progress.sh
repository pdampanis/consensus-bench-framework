#!/usr/bin/env bash
# =============================================================================
# check_progress.sh — check campaign progress without SSH-ing manually
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
INVENTORY="$HERE/inventory.env"
source "$INVENTORY"

SSH="ssh -i $SSH_KEY -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -o BatchMode=yes"

echo "=== Campaign Progress ==="
echo "Loadgen: $LOADGEN"
echo

# Check if loadgen is reachable
if ! $SSH "${SSH_USER}@${LOADGEN}" "echo ok" 2>/dev/null; then
  echo "ERROR: Cannot reach loadgen at $LOADGEN"
  exit 1
fi

# Latest batch log lines
echo "--- Last 10 lines of batch log ---"
$SSH "${SSH_USER}@${LOADGEN}" "tail -10 /root/thesis-final/results/batch.log 2>/dev/null || echo '(no batch log yet)'"

echo
echo "--- Run status summary ---"
$SSH "${SSH_USER}@${LOADGEN}" "
  find /root/thesis-final/results -name 'manifest.json' 2>/dev/null \
    | while read m; do
        jq -r '\"\(.system)/\(.scenario)/\(.run_id)  \(.status)\"' \"\$m\" 2>/dev/null
      done
" 2>/dev/null | sort || echo "(no manifests yet)"

echo
echo "--- Completed / Total cells ---"
COMPLETED=$($SSH "${SSH_USER}@${LOADGEN}" \
  "find /root/thesis-final/results -name 'manifest.json' -exec jq -r 'select(.status==\"complete\") | \"x\"' {} \; 2>/dev/null | wc -l" 2>/dev/null || echo "0")
TOTAL=$($SSH "${SSH_USER}@${LOADGEN}" \
  "find /root/thesis-final/results -name 'manifest.json' | wc -l" 2>/dev/null || echo "0")
echo "$COMPLETED / $TOTAL cells complete"

# Check for errors
echo
echo "--- Errors / failures ---"
$SSH "${SSH_USER}@${LOADGEN}" "
  grep -i 'error\|fail\|FATAL' /root/thesis-final/results/batch.log 2>/dev/null | tail -5
" 2>/dev/null || echo "(none found)"

# Node status
echo
echo "--- Node reachability ---"
for ip in "$NODE1" "$NODE2" "$NODE3" "$NODE4"; do
  if $SSH "${SSH_USER}@${ip}" "echo ok" 2>/dev/null >/dev/null; then
    echo "  $ip: UP"
  else
    echo "  $ip: DOWN"
  fi
done
