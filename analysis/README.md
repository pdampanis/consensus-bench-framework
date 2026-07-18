# analysis/ — the offline analysis pipeline (M6.4 foundation)

`analyse.py` (Python 3.9+, stdlib only) turns a collected results tree into
per-run and per-cell CSV tables. It is the in-repo successor the F15 finding
asked for; the Holm-corrected pairwise tests, ECDFs, and the eight figures
build ON these tables in later increments.

```bash
python3 analyse.py --selftest                 # end-to-end check, no inputs needed
python3 analyse.py ~/thesis-data/2026-08-03-blockA --out analysis-out
```

Outputs in `--out`:
- `runs.csv` — one row per included run (identity, throughput mean + CoV,
  point latency stats, failover_ms when present).
- `cells.csv` — one row per (system, scenario, size, conflict, rate) cell:
  n, throughput median/mean + 95% bootstrap CI (10k resamples, seed 42),
  mean-latency mean + CI, and **per-run p50/p99 SPREADS** — deliberately
  not averages (methodology §3: percentiles are pooled via histogram
  merge, never averaged; pooling needs `pip install hdrhistogram` and
  lands with the figures increment).
- `excluded.csv` — every excluded run WITH its reason (status != complete,
  environment=local). Nothing is dropped silently, ever.

Try it on the committed sample: `python3 analyse.py ../docs/examples/sample-results
--include-local` (the sample is a laptop run, so it needs the flag — and that
is exactly the point: laptop data never enters a figure without you saying so
twice).
