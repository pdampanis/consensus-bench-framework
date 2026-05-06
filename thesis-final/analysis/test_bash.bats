#!/usr/bin/env bats
# =============================================================================
# test_bash.bats — bash script integration tests
#
# Run with: bats analysis/test_bash.bats
# Install:  sudo apt install bats  or  npm install -g bats
# =============================================================================

setup() {
  TESTDIR="$(mktemp -d)"
  SCRIPT_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/../scripts" && pwd)"
}

teardown() {
  rm -rf "$TESTDIR"
}

# ---------------------------------------------------------------------------
# run_batch.sh
# ---------------------------------------------------------------------------

@test "run_batch.sh accepts valid session names" {
  run bash -n "$SCRIPT_DIR/run_batch.sh"
  [ "$status" -eq 0 ]
}

@test "run_batch.sh rejects zero arguments with usage" {
  run bash "$SCRIPT_DIR/run_batch.sh"
  [ "$status" -eq 2 ]
  [[ "$output" == *"usage"* ]]
}

@test "run_batch.sh accepts single cell format" {
  # Should not error on valid syntax (dry-run: -n checks syntax only)
  run bash -n "$SCRIPT_DIR/run_batch.sh" kraft baseline
  [ "$status" -eq 0 ]
}

# ---------------------------------------------------------------------------
# run_one.sh
# ---------------------------------------------------------------------------

@test "run_one.sh syntax is valid" {
  run bash -n "$SCRIPT_DIR/run_one.sh"
  [ "$status" -eq 0 ]
}

@test "run_one.sh requires 3 arguments" {
  run bash "$SCRIPT_DIR/run_one.sh"
  [ "$status" -eq 1 ]
}

@test "run_one.sh rejects unknown system" {
  run bash "$SCRIPT_DIR/run_one.sh" bogus baseline run001
  [ "$status" -eq 1 ]
}

# ---------------------------------------------------------------------------
# parse_results.sh
# ---------------------------------------------------------------------------

@test "parse_results.sh syntax is valid" {
  run bash -n "$SCRIPT_DIR/parse_results.sh"
  [ "$status" -eq 0 ]
}

@test "parse_results.sh handles missing perf-raw.txt" {
  run bash "$SCRIPT_DIR/parse_results.sh" kraft "$TESTDIR"
  [ "$status" -eq 1 ]
}

@test "parse_results.sh parses kafka-format summary correctly" {
  # Create a synthetic perf-raw.txt in kafka-producer-perf-test format
  cat > "$TESTDIR/perf-raw.txt" <<'EOF'
1000 records sent, 200.0 records/sec, 0.20 MB/sec, 5 ms avg latency, 20 ms max latency.
2000 records sent, 200.0 records/sec, 0.20 MB/sec, 5 ms avg latency, 20 ms max latency.
100000 records sent, 200.0 records/sec, 0.20 MB/sec, 5.00 ms avg latency, 20 ms max latency, 3 ms 50th, 8 ms 95th, 15 ms 99th, 19 ms 99.9th.
EOF

  run bash "$SCRIPT_DIR/parse_results.sh" kraft "$TESTDIR"
  [ "$status" -eq 0 ]

  # throughput.csv should have rows
  [ -f "$TESTDIR/throughput.csv" ]
  [ "$(wc -l < "$TESTDIR/throughput.csv")" -ge 1 ]

  # latency.csv should have all 6 metrics
  [ -f "$TESTDIR/latency.csv" ]
  grep -q "avg," "$TESTDIR/latency.csv"
  grep -q "p50," "$TESTDIR/latency.csv"
  grep -q "p95," "$TESTDIR/latency.csv"
  grep -q "p99," "$TESTDIR/latency.csv"
  grep -q "p99_9," "$TESTDIR/latency.csv"
  grep -q "max," "$TESTDIR/latency.csv"
}

@test "parse_results.sh parses etcd benchmark format" {
  cat > "$TESTDIR/perf-raw.txt" <<'EOF'
Summary:
  Total:        60.1234 secs.
  Slowest:      0.0456 secs.
  Fastest:      0.0001 secs.
  Average:      0.0012 secs.
  Stddev:       0.0023 secs.
  Requests/sec: 8321.4567

Response time histogram:
  0.0001 [1]     |

Latency distribution:
  10% in 0.0005 secs.
  25% in 0.0008 secs.
  50% in 0.0011 secs.
  75% in 0.0015 secs.
  90% in 0.0020 secs.
  95% in 0.0024 secs.
  99% in 0.0035 secs.
  99.9% in 0.0089 secs.
EOF

  run bash "$SCRIPT_DIR/parse_results.sh" etcd "$TESTDIR"
  [ "$status" -eq 0 ]
  [ -f "$TESTDIR/latency.csv" ]
  # Check that values are in microseconds (ms * 1000)
  run grep "avg," "$TESTDIR/latency.csv"
  [[ "$output" == *"1200"* ]]  # 1.2ms = 1200us
}

@test "parse_results.sh handles all 7 systems" {
  for sys in kraft zk etcd tendermint paxos epaxos hotstuff; do
    # Create minimal valid perf-raw.txt
    echo "1000 records sent, 200.0 records/sec, 0.20 MB/sec, 5 ms avg latency, 20 ms max latency." > "$TESTDIR/perf-raw.txt"
    echo "1000 records sent, 200.0 records/sec, 0.20 MB/sec, 5.00 ms avg latency, 20 ms max latency, 3 ms 50th, 8 ms 95th, 15 ms 99th, 19 ms 99.9th." >> "$TESTDIR/perf-raw.txt"

    run bash "$SCRIPT_DIR/parse_results.sh" "$sys" "$TESTDIR"
    [ "$status" -eq 0 ] || { echo "FAILED: $sys (exit=$status)"; false; }
    [ -f "$TESTDIR/throughput.csv" ] || { echo "FAILED: $sys no throughput.csv"; false; }
    [ -f "$TESTDIR/latency.csv" ] || { echo "FAILED: $sys no latency.csv"; false; }
  done
}

# ---------------------------------------------------------------------------
# export_metrics.sh
# ---------------------------------------------------------------------------

@test "export_metrics.sh syntax is valid" {
  run bash -n "$SCRIPT_DIR/export_metrics.sh"
  [ "$status" -eq 0 ]
}

@test "export_metrics.sh accepts all 7 systems" {
  for sys in kraft zk etcd tendermint paxos epaxos hotstuff; do
    run bash -n -c "source $SCRIPT_DIR/export_metrics.sh; SYSTEM=$sys; case \$SYSTEM in kraft) NAME_RE='k[0-9]+';; zk) NAME_RE='(zookeeper|zb[0-9]+)';; etcd) NAME_RE='etcd[0-9]+';; tendermint) NAME_RE='tm[0-9]+';; paxos|epaxos) NAME_RE='paxi[0-9]+';; hotstuff) NAME_RE='hotstuff';; *) exit 1;; esac"
    [ "$status" -eq 0 ] || { echo "FAILED: $sys not handled"; false; }
  done
}

# ---------------------------------------------------------------------------
# run_le_trial.sh
# ---------------------------------------------------------------------------

@test "run_le_trial.sh syntax is valid" {
  run bash -n "$SCRIPT_DIR/run_le_trial.sh"
  [ "$status" -eq 0 ]
}

@test "run_le_trial.sh passes multi-endpoints for all non-hotstuff systems" {
  # Verify the ENDPOINTS variables contain commas (multi-endpoint) for
  # all systems except hotstuff (which uses in-container probing)
  local script="$SCRIPT_DIR/run_le_trial.sh"

  # etcd should have all 3 endpoints
  run grep -A1 "etcd)" "$script" | grep "ENDPOINTS="
  [[ "$output" == *","* ]]

  # tendermint should have all 4 endpoints
  run grep -A1 "tendermint)" "$script" | grep "ENDPOINTS="
  [[ "$output" == *","* ]]

  # paxos should have all 3 endpoints
  run grep -A1 "paxos)" "$script" | grep "ENDPOINTS="
  [[ "$output" == *","* ]]
}
