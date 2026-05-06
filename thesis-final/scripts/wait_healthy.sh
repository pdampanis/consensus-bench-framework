#!/usr/bin/env bash
# =============================================================================
# wait_healthy.sh - block until every container in a compose stack is healthy
# =============================================================================
# Uses jq for robust JSON parsing. Exit 0 when healthy, 1 on 180s timeout.
# =============================================================================
set -u

STACK="${1:?usage: $0 <compose_name>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

FILE="$ROOT/compose/$STACK.yml"
[ -f "$FILE" ] || { echo "no such compose file: $FILE" >&2; exit 2; }

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required. sudo apt install -y jq" >&2
  exit 3
fi

DEADLINE=$(( $(date +%s) + 180 ))

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  stats=$(docker compose -f "$FILE" ps --format json 2>/dev/null \
    | jq -s '
        (if length == 1 and (.[0] | type) == "array" then .[0] else . end) as $rows
        | { total: ($rows | length),
            healthy: ($rows | map(select(.Health == "healthy")) | length),
            unhealthy: ($rows | map(select(.Health == "unhealthy")) | length)
          }
      ' 2>/dev/null)
  total=$(echo "$stats" | jq -r '.total // 0')
  healthy=$(echo "$stats" | jq -r '.healthy // 0')
  unhealthy=$(echo "$stats" | jq -r '.unhealthy // 0')

  if [ "$total" -gt 0 ] && [ "$healthy" -eq "$total" ] && [ "$unhealthy" -eq 0 ]; then
    echo "[$(date -Iseconds)] $STACK: $healthy/$total healthy"
    exit 0
  fi
  sleep 5
done

echo "[$(date -Iseconds)] TIMEOUT: $STACK not healthy within 180s" >&2
docker compose -f "$FILE" ps >&2
exit 1
