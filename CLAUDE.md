# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

The experimental bundle (v5.1) for the thesis *Evolution and Comparison of Consensus Protocols in Distributed Systems*. It benchmarks 7 consensus systems under 6 fault scenarios and produces the thesis figures and statistics. This repo is frozen: active work continues in a separate Java/Maven harness (`consensus-bench-framework`) that shares no history with this repo.

Layout:
- `thesis-final/`: the benchmark pipeline (scripts, compose files, Docker images, analysis, docs). All pipeline paths are relative to this folder.
- `deploy/`: Hetzner 5-VM campaign scripts. They live at the repo root, not in `thesis-final/`.
- `dedicated-servers/DEPLOYMENT_GUIDE.md`: step-by-step VM guide.
- `visualizer/`: Spring Boot + React app that browses the results tree.
- Root `.md`/`.bib` files: thesis writing material (`STRATEGY.md` maps the thesis summary to files, `THESIS_OUTLINE.md` is the chapter guide, `citation_map.md` maps papers to sections).

## Commands

From `thesis-final/`. pytest is not installed system-wide; use a venv:

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install pandas numpy scipy matplotlib tabulate pytest

pytest analysis/test_pipeline.py                                  # 20 tests
pytest "analysis/test_pipeline.py::TestLoadRuns::test_one_run"    # single test
bats analysis/test_bash.bats      # 15 bash syntax/CLI tests (bats not installed here)

python analysis/analyse.py --results-dir ./results --out ./analysis/out
```

Local single-host campaign (needs `jq` and Docker):

```bash
docker network create thesis-net                 # compose files use it as an external network
docker compose -f compose/observability.yml up -d
docker build -t le-probe:latest scripts/probes/
docker build -t thesis-paxi:latest docker/paxi/
docker build -t thesis-hotstuff:latest docker/hotstuff/   # 5–10 min first time

./scripts/run_batch.sh kraft baseline   # one (system, scenario) cell, all repeats
./scripts/run_batch.sh session1         # session1..session8, or `all`
```

VM campaign (from the repo root): copy `deploy/inventory.env.template` to `deploy/inventory.env` (gitignored), then `deploy/setup_cluster.sh`, `deploy/run_campaign.sh [sessionN | <system> <scenario>]`, `deploy/check_progress.sh`, `deploy/download_results.sh`. `deploy/run_manual.sh <system>` starts one system on the VMs for manual exploration.

Visualizer (from `visualizer/`): `RESULTS_DIR=/abs/path/results docker compose up -d` serves on :8090. Dev mode: `cd backend && APP_RESULTS_DIR=... mvn spring-boot:run` (:8080, H2) and `cd frontend && npm install && npm run dev` (:5173). `npm run build` type-checks (`tsc --noEmit`). The backend has no tests.

## Architecture

A chain of bash scripts with a fixed file contract between stages:

1. **`scripts/run_batch.sh`** defines sessions 1–8 and maps (system, cluster size) to a compose file in `compose_file_for`. For each (system, scenario) it brings the stack up once (`bring_up`: waits up to 240 s for all containers healthy, then 20 s settle), runs every repeat, then tears it down with `down -v`. A cell is skipped when its `manifest.json` has `status == "complete"`, so campaigns can be stopped and re-run.
2. **`scripts/run_one.sh`** runs one repeat against an already-running stack: 180 s warm-up + 300 s measured. The load tool depends on the system: `kafka-producer-perf-test.sh` (kraft, zk), etcd `benchmark`, `scripts/probes/cmt_bench.py` (tendermint), `paxi_bench.py` (paxos, epaxos), asonnino's `fab local` (hotstuff).
3. **Fault injection** starts after warm-up: `scripts/pumba/<scenario>.sh` locally, or `$ROOT/deploy/faults.sh` when `DEPLOY_MODE=vm`.
4. **`scripts/parse_results.sh`** converts each tool's raw output into the shared contract: `throughput.csv` (`time_offset_sec,ops_per_sec`) and `latency.csv` (`metric,value_us` for avg, p50, p95, p99, p99_9, max). Everything downstream is tool-agnostic. etcd only gives a summary rate, not per-second throughput.
5. `run_one.sh` then runs a per-system durability check (`correctness.txt`) and writes `manifest.json`. Status is `failed` if `throughput.csv` has fewer than 10 rows, except for `double_kill`, where zero throughput is the expected result.
6. **`analysis/analyse.py`** reads the results tree and writes summary/pairwise CSVs, figures fig01–fig06, and `methodology.md`. Statistics: Mann-Whitney U + Vargha-Delaney A, seeded bootstrap CI. The visualizer backend imports every `manifest.json` at startup.

Results tree (gitignored):
- `results/<system>/<scenario>/runNNN/{manifest.json, throughput.csv, latency.csv, correctness.txt, run.log}`
- Scalability runs (size ≠ 3) go to `results/<system>_n<size>/...`. `run_one.sh` always writes to `results/<system>/...`, so `run_cell` temporarily symlinks that path to the `_n<size>` dir.
- Leader-election trials (session 6, `scripts/run_le_trial.sh`): `results/<system>/leader_election/leNNN/trial.txt` holding `system,run_id,failover`.

Matrix: systems kraft, zk, etcd, tendermint, paxos, epaxos, hotstuff; scenarios baseline, leader_kill, packet_loss, network_partition (main) and double_kill, slow_node (deep, session 5). Cluster size 3, except tendermint and hotstuff (fixed at 4). kraft and etcd also run at 5 and 7. Baseline n=10, fault n=5, scalability n=3, leader election 200 trials.

**HotStuff is the odd one out.** One container runs 4 validators (`docker/hotstuff/`). Fault injection (`hs-inject.sh`) and leader-election trials (`hs-le-probe.sh`) run inside that container, not through Pumba. It reports mean-only latency and summary TPS, which `analyse.py` flags as methodology alerts.

## Gotchas

- `analysis/conftest.py` is both the pytest conftest and a config module (`SYSTEMS`, `FAULT_MODEL`, `LATENCY_METRICS`, ...). Its docstring says `analyse.py` imports it, but it does not: `analyse.py` has its own copies of `METHODOLOGY_ALERTS` and `ZERO_VARIANCE_SYSTEM_METRICS`, and the tests import those from `analyse`. Change both files together.
- Adding a system touches: the `case` in `run_one.sh`, `compose_file_for` and `teardown_all_suts` in `run_batch.sh`, a compose file, `parse_results.sh`, the `pumba/*.sh` scripts, `SYSTEMS` in `conftest.py`, and `start_*`/`PLACEMENT` in `deploy/lib.sh` plus the copy of `PLACEMENT` in `deploy/faults.sh`.
- VM mode wiring: `setup_cluster.sh` rsyncs `thesis-final/` to `loadgen:/root/thesis-final/` and copies `faults.sh` and `inventory.env` into `/root/thesis-final/deploy/`. That is the only place `$ROOT/deploy/faults.sh` resolves; locally `thesis-final/deploy/` does not exist. `run_campaign.sh` just runs `DEPLOY_MODE=vm ./scripts/run_batch.sh` on the loadgen over SSH.
- In that automated path, `bring_up` still starts the systems with `docker compose` on the loadgen. The `start_*` functions (`--network host`, spread over node1–4 per `PLACEMENT`) are only called by `run_manual.sh`. `faults.sh`, however, SSHes to the `PLACEMENT` nodes to kill containers or apply `tc`. Check this mismatch before trusting VM-mode fault results.
- Header comments in `run_batch.sh` are stale (they describe v3: 4 systems, 6 sessions, ~21 h). The code and `docs/01_guide.md` are current.
- Upstream HotStuff (asonnino/hotstuff) and Paxi (ailidani/paxi) builds clone `main`/`master` and sometimes break; pin a commit in the Dockerfile (see `docs/01_guide.md` §10).

## Key docs

- `thesis-final/docs/01_guide.md`: run-book (setup, smoke test, sessions, analysis, common problems, cheat sheet).
- `thesis-final/docs/algorithms/*.md`: one doc per protocol, plus `scalability.md` and `comparison_matrix.md`.
- `visualizer/README.md`: API endpoints. To add an algorithm animation, add a JSON file to `visualizer/backend/src/main/resources/algorithms/` matching `AlgorithmSpec` in `frontend/src/api.ts`; no frontend change is needed.
