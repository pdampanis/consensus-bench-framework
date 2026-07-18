# examples/ — what the data looks like (novice-friendly, field by field)

Three tiny samples so you can SEE the output contract before any campaign.
Two are labeled SYNTHETIC (hand-written shapes, no such run happened); one
is REAL but from a laptop, which the analysis excludes by default — try it:

```bash
python3 analysis/analyse.py docs/examples/sample-results            # excludes it (environment=local)
python3 analysis/analyse.py docs/examples/sample-results --include-local   # includes it
```

## sample-results/etcd/baseline/size1/sample01/ — a REAL run (laptop, 2026-07-18)

Generated with the shaded jar in ~8 s:
`java -jar consensus-bench.jar local-run --size 1 --rate 100 --duration 6 --warmup 2 --out docs/examples/sample-results --run sample01`

The path IS the identity: `<system>/<scenario>/size<N>[/c<pct>]/<runId>/`
(cluster size lives in the path so different sizes can never collide —
the v6 bug that silently ate scalability cells).

- **manifest.json** — the run's identity + honesty record. Key fields:
  `environment: "local"` (laptop — NEVER thesis data; analysis filters it);
  `image` (the digest actually run); `config_hash` (same hash = same
  experiment); `ops_after_warmup` / `errors` / `error_rate`;
  `fault_injected_at_ms` and `failover_ms` are `null` — explicit nulls,
  because ABSENT measurement ≠ zero; `status: "complete"` (a majority-failed
  run says "failed" and analysis excludes it, listing the reason).
- **throughput.csv** — `t,ops` for EVERY second from t=0, zeros included
  (a zero second is stall evidence, not noise); t counts from run start,
  so rows with t < warmup_secs are warmup (kept here, excluded from stats).
- **latency.csv** — point summary in MICROSECONDS: avg is the TRUE mean
  (tails move it; p50 does not), then p50/p95/p99/p99_9/max. Post-warmup
  samples only.
- **latency.hlog** — the FULL HdrHistogram (standard v1.3 log format).
  This is the analysis input: per-run histograms are MERGED for pooled
  percentiles, never averaged (methodology §3). latency.csv is for humans.

## SYNTHETIC-fault-run-example/ — the shape of a fault-run manifest

What changes vs baseline: `scenario: "leader_kill"`,
`fault_injected_at_ms: 240000` (240 s = warmup 180 + 60, the runbook's
injection point) and `failover_ms: 890` — the client-measured gap from the
kill to the first subsequent commit. For a Paxi leader_kill this field
would be `null` WITH status complete — the preregistered F26 wedge
(no failure detector = no recovery), a documented absence, not a bug.
A real fault run's directory also contains `logs/<container>.log` — full
SUT logs pulled after the run for forensics.

## SYNTHETIC-hotstuff-example/ — HotStuff's different (honest) contract

HotStuff produces NO throughput.csv/latency.hlog — its own client is the
load generator and its logs are the metrics. A real run dir holds
`summary.txt` (this exact canonical block, produced by the logs.py-port
analyzer and validated by the strict parser — the shown numbers are the
parser test fixture, not measurements), `logs/client.log`,
`logs/thesis-hs1..4.log` (the raw evidence), and a reduced manifest.
End-to-end TPS/latency are the client-observed primaries; Consensus
TPS/latency are protocol-internal. Every figure using these numbers
carries the "log-derived, no server metrics" caveat.
