#!/usr/bin/env bash
# =============================================================================
# hs-le-probe.sh — in-container leader-election failover probe
# =============================================================================
#
# Measures HotStuff's leader-failover latency by:
#   1. Spawning a `client` process that submits tx at 1000 tx/s to node-0.
#      The asonnino client writes its tx activity to a log file.
#   2. Sleeping 3s for a baseline window.
#   3. Killing node-0 at a known time T_KILL.
#   4. Running another 17s.
#   5. Parsing the client's log for the first successful tx acknowledgment
#      whose timestamp is after T_KILL. The delta is the failover latency.
#
# This script is invoked from run_le_trial.sh via:
#   docker exec -e OUT=... hotstuff /opt/hs-le-probe.sh
#
# It writes the failover time (in seconds, float) to stdout; the caller
# redirects this into trial.txt. On failure it writes "timeout".
#
# Assumes `fab local` has NOT been invoked, or has been torn down before
# this probe runs. This probe starts its own isolated 4-validator setup.
# =============================================================================
set -eu

OUT_DIR="${OUT_DIR:-/work/le-out}"
mkdir -p "$OUT_DIR"

# Use a fresh directory to avoid any stale state
rm -rf "$OUT_DIR"/*

# Launch the 4-validator testbed via fab local with a short duration
# (25s = 3s baseline + 22s recovery window). We background it so we can
# inject the kill at the right time.
cd /opt/hotstuff/benchmark

# Patch fabfile for a short duration
python3 - <<PATCH
import re, pathlib
p = pathlib.Path("fabfile.py")
text = p.read_text()
text = re.sub(r"'duration':\s*\d+", "'duration': 25", text)
text = re.sub(r"'rate':\s*\[\d+\]", "'rate': [1000]", text)
p.write_text(text)
PATCH

# Start fab local in background
fab local > "$OUT_DIR/fab.log" 2>&1 &
FAB_PID=$!

# Wait for the testbed to actually be running — the tmux panes need
# to come up, validators need to start, and the client needs to begin
# producing committed blocks. A three-stage readiness check:
#   Stage 1: fab log shows bootstrap messages
#   Stage 2: node-0 has a bound port (process is up and listening)
#   Stage 3: at least one "Committed" line appears in the log (consensus active)
for i in $(seq 1 20); do
  if grep -qE "(Booting|Primary|Node booted)" "$OUT_DIR/fab.log" 2>/dev/null; then
    # Stage 1 passed — check stage 2 (port binding)
    NODE0_PID=$(ss -lntp 2>/dev/null \
      | awk '/:9000 / {
          match($0, /pid=[0-9]+/);
          if (RSTART) { print substr($0, RSTART+4, RLENGTH-4); exit }
        }' | head -1)
    if [ -n "${NODE0_PID:-}" ]; then
      # Stage 2 passed — check stage 3 (consensus active)
      if grep -q "Committed" "$OUT_DIR/fab.log" 2>/dev/null; then
        break
      fi
    fi
  fi
  sleep 1
done

# Baseline window
sleep 3

# Capture T_KILL with nanosecond precision
T_KILL_NS=$(date +%s%N)
echo "$T_KILL_NS" > "$OUT_DIR/t_kill_ns"

# Find node-0's pid (listens on port 9000)
PID=$(ss -lntp 2>/dev/null \
  | awk '/:9000 / {
      match($0, /pid=[0-9]+/);
      if (RSTART) {
        print substr($0, RSTART+4, RLENGTH-4);
        exit
      }
    }' | head -1)

if [ -n "${PID:-}" ]; then
  kill -9 "$PID" 2>/dev/null || true
fi

# Wait for fab to finish (it will terminate naturally at duration=25s)
wait "$FAB_PID" 2>/dev/null || true

# Parse fab.log for tx acknowledgments after T_KILL. The asonnino client
# writes lines like "Sent sample transaction N" and "Consensus TPS: M".
# Validator logs (inside /tmp/benchmark/logs or benchmark/logs) contain
# "Committed B" lines with timestamps. We use the first "Committed"
# timestamp after T_KILL from any surviving validator.
python3 - <<PARSE > "$OUT_DIR/failover.txt"
import re, pathlib, time, sys, os

t_kill_ns = int(pathlib.Path("$OUT_DIR/t_kill_ns").read_text())
t_kill_s = t_kill_ns / 1e9

# Search for validator log files in common locations.
log_dirs = [
    pathlib.Path("/tmp/benchmark/logs"),
    pathlib.Path("/opt/hotstuff/benchmark/logs"),
    pathlib.Path("/work/logs"),
]
first_post_kill = None

for log_dir in log_dirs:
    if not log_dir.exists():
        continue
    for log_file in sorted(log_dir.glob("*.log")):
        if "node-0" in log_file.name:
            continue
        try:
            for line in log_file.read_text().splitlines():
                m = re.match(
                    r"\[(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?)[^\]]*\]",
                    line
                )
                if not m or "Committed" not in line:
                    continue
                from datetime import datetime
                for fmt in ("%Y-%m-%dT%H:%M:%S.%fZ", "%Y-%m-%dT%H:%M:%SZ",
                            "%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S"):
                    try:
                        ts = datetime.strptime(m.group(1), fmt).timestamp()
                        break
                    except ValueError:
                        continue
                else:
                    continue
                if ts > t_kill_s:
                    if first_post_kill is None or ts < first_post_kill:
                        first_post_kill = ts
                    break
        except Exception:
            pass

if first_post_kill is None:
    print("timeout")
else:
    print(f"{first_post_kill - t_kill_s:.6f}")
PARSE

cat "$OUT_DIR/failover.txt"
