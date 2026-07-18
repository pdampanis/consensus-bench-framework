#!/usr/bin/env bash
# collect_block.sh — one command: EVERYTHING a finished system block produced
# lands in ONE dated directory on the laptop, offline-viewable.
# (CAMPAIGN_RUNBOOK §4 + EXECUTION_AND_COST_MODEL §6: results are rsync'd and
# COUNT-VERIFIED on the laptop BEFORE terraform destroy — nothing on any VM
# is the sole copy of anything by the time billing stops.)
#
# Usage:
#   scripts/collect_block.sh <loadgen_public_ip> <obs_public_ip> <dest_root> [ssh_key]
#
# Produces <dest_root>/<UTC timestamp>/
#   results/               harness CSVs + hlog + manifests + per-run logs/
#   campaign-log.jsonl     failed-cell record (if any cell failed)
#   prometheus-snapshot/   a Prometheus TSDB snapshot (explanation layer)
#
# Offline replay afterwards:
#   SNAPSHOT_DIR=<dest>/prometheus-snapshot docker compose \
#     -f observability/offline/docker-compose.yml up
set -euo pipefail

if [ $# -lt 3 ]; then
  grep '^# ' "$0" | sed 's/^# //'
  exit 1
fi
LOADGEN=$1; OBS=$2; KEY=${4:-$HOME/.ssh/hetzner_thesis}
DEST=$3/$(date -u +%Y-%m-%dT%H%MZ)
SSH="ssh -i $KEY -o StrictHostKeyChecking=accept-new"
mkdir -p "$DEST"

echo "== 1/4 results tree from loadgen $LOADGEN"
rsync -a -e "$SSH" "root@$LOADGEN:results/" "$DEST/results/"
rsync -a -e "$SSH" "root@$LOADGEN:results/campaign-log.jsonl" "$DEST/" 2>/dev/null \
  || echo "   (no campaign-log.jsonl — no failed cells recorded)"

echo "== 2/4 Prometheus snapshot on obs $OBS"
SNAP=$($SSH "root@$OBS" \
  "curl -sf -XPOST localhost:9090/api/v1/admin/tsdb/snapshot | jq -r .data.name")
[ -n "$SNAP" ] && [ "$SNAP" != "null" ] \
  || { echo "snapshot API returned nothing — is --web.enable-admin-api on?"; exit 1; }
$SSH "root@$OBS" "PROM=\$(docker ps --format '{{.Names}}' | grep -m1 prometheus) \
  && rm -rf /root/prom-snapshot \
  && docker cp \"\$PROM:/prometheus/snapshots/$SNAP\" /root/prom-snapshot"

echo "== 3/4 snapshot to laptop"
rsync -a -e "$SSH" "root@$OBS:/root/prom-snapshot/" "$DEST/prometheus-snapshot/"
$SSH "root@$OBS" "rm -rf /root/prom-snapshot"

echo "== 4/4 count-verify (the pre-destroy gate)"
REMOTE=$($SSH "root@$LOADGEN" "find results -name manifest.json | wc -l")
LOCAL=$(find "$DEST/results" -name manifest.json | wc -l)
echo "   manifests: remote=$REMOTE local=$LOCAL"
if [ "$REMOTE" != "$LOCAL" ]; then
  echo "COUNT MISMATCH — do NOT destroy; rerun this script"; exit 1
fi

echo "collected -> $DEST"
echo "next: python3 analysis/analyse.py '$DEST/results'"
echo "      SNAPSHOT_DIR='$DEST/prometheus-snapshot' docker compose -f observability/offline/docker-compose.yml up"
