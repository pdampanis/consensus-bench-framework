# Consensus Protocol Thesis — Experimental Bundle (v5)

Experimental materials for the diploma thesis *Evolution and Comparison of Consensus Protocols in Distributed Systems*. Version 5 completes experimental coverage of **all five algorithms named in the thesis summary** (Paxos, Raft, EPaxos, HotStuff, Tendermint), adds HotStuff via Alberto Sonnino's reference Rust implementation, and preserves everything that was working in v4.

## Where to start

Read `docs/01_guide.md` first. Complete run-book: preparation, setup, smoke test, eight overnight sessions, analysis, thesis writing.

Per-algorithm knowledge docs in `docs/algorithms/`:

- `paxos.md` — classical Multi-Paxos
- `raft.md` — foundational Raft
- `zab.md` — ZAB and ZK-mode Kafka
- `kraft.md` — Kafka's Raft variant
- `etcd.md` — canonical production Raft
- `epaxos.md` — leaderless Paxos
- `tendermint.md` — CometBFT (BFT experimental)
- **`hotstuff.md`** — asonnino/hotstuff experimental (new in v5)
- `scalability.md` — methodology for the scalability sub-matrix
- `comparison_matrix.md` — unified analytical table

## What v5 adds over v4

**HotStuff measured experimentally.** Via asonnino/hotstuff (github.com/asonnino/hotstuff, main branch, 2-chain Diem variant). This is the academic reference Rust implementation with published reference numbers citable directly. HotStuff integration lives entirely in `docker/hotstuff/` and `compose/hotstuff.yml`; the v4 paths for the other 6 systems are unmodified.

HotStuff uses a single-container pattern (4 validator processes orchestrated by asonnino's `fab local`), so fault injection happens inside the container via `docker/hotstuff/hs-inject.sh` rather than Pumba. Leader-election trials run an in-container probe (`hs-le-probe.sh`) that spawns and tears down 4-validator testbeds per trial.

**Experimental coverage**: 5 of 5 summary-named algorithms (Paxos, Raft, EPaxos, Tendermint, **HotStuff**). Plus ZAB. The thesis summary's "επιλεγμένων υλοποιήσεων" and "κάποιων από τα παραπάνω" phrasings are satisfied comprehensively.

## Directory layout (new files marked with ←)

```
.
├── README.md                  this file
├── docs/
│   ├── 01_guide.md
│   └── algorithms/
│       ├── paxos.md / raft.md / zab.md / kraft.md / etcd.md
│       ├── epaxos.md / tendermint.md
│       ├── hotstuff.md                              ← rewritten for v5
│       ├── scalability.md / comparison_matrix.md    ← matrix updated
├── compose/
│   ├── kafka-kraft.yml / -5.yml / -7.yml
│   ├── kafka-zk.yml / etcd.yml / -5.yml / -7.yml
│   ├── tendermint.yml
│   ├── paxi-paxos.yml / paxi-epaxos.yml
│   ├── hotstuff.yml                                 ← new in v5
│   └── observability.yml
├── docker/
│   ├── paxi/
│   │   ├── Dockerfile / config.json
│   └── hotstuff/                                    ← new in v5
│       ├── Dockerfile         builds from asonnino/hotstuff main branch
│       ├── hs-run.sh          in-container benchmark runner
│       ├── hs-inject.sh       in-container fault injector
│       └── hs-le-probe.sh     in-container LE failover probe
├── scripts/
│   ├── run_batch.sh           now dispatches 8 sessions
│   ├── run_one.sh             7-system dispatcher (kraft/zk/etcd/tm/paxos/epaxos/hotstuff)
│   ├── parse_results.sh
│   ├── run_le_trial.sh        hotstuff branch delegates to hs-le-probe.sh
│   ├── wait_healthy.sh
│   ├── export_metrics.sh
│   ├── pumba/                 5 fault scripts, all 7 systems supported
│   └── probes/
│       ├── Dockerfile
│       ├── le_probe.py
│       ├── cmt_bench.py
│       └── paxi_bench.py
├── analysis/
├── visualizer/
└── results/
```

## Quick start

```bash
# Setup (once)
sudo tuned-adm profile latency-performance
sudo apt install -y jq docker-ce docker-compose-plugin
docker network create thesis-net

docker compose -f compose/observability.yml up -d

# Build our three custom images (HotStuff build takes ~5-10 min first time)
docker build -t le-probe:latest       scripts/probes/
docker build -t thesis-paxi:latest    docker/paxi/
docker build -t thesis-hotstuff:latest docker/hotstuff/

# Smoke test (~135 min)
./scripts/run_batch.sh kraft baseline
./scripts/run_batch.sh paxos baseline
./scripts/run_batch.sh hotstuff baseline

# Full campaign (~39h across 8 overnight sessions)
nohup ./scripts/run_batch.sh all > results/batch-all.log 2>&1 &

# Analysis
python3 -m venv .venv && source .venv/bin/activate
pip install pandas numpy scipy matplotlib tabulate
python analysis/analyse.py --results-dir ./results --out ./analysis/out

# Visualizer (between sessions)
cd visualizer
RESULTS_DIR=$(pwd)/../results docker compose up -d
```

## Campaign summary

| Sessions | Content | Wall time |
|---|---|---|
| Setup | Manual tests, tuning | 2h attended |
| Smoke test | kraft + paxos + hotstuff baselines | 3h attended |
| Session 1 | kraft + zk, 4 main scenarios, 10 baseline + 5 fault runs | 7.8h unattended |
| Session 2 | etcd, 4 main scenarios, 10 baseline + 5 fault runs | 4.0h unattended |
| Session 3 | tendermint, 4 main scenarios, 10 baseline + 5 fault runs | 4.0h unattended |
| Session 7 | paxos + epaxos, 4 main scenarios, 10 baseline + 5 fault runs | 7.8h unattended |
| Session 8 | hotstuff, 4 main scenarios, 10 baseline + 5 fault runs | 4.0h unattended |
| Session 4 | scalability: kraft + etcd at sizes 5 and 7, 3 baseline runs | 1.6h unattended |
| Session 5 | deep faults × 7 systems × 5 runs | 9.3h unattended |
| Session 6 | leader-election CDFs, 200 trials × 7 systems | 9.8h unattended |
| **Total** | | **~48h unattended, ~5h attended** |

**Statistical power**: Baseline scenarios run n=10 per cell (up from 5). With n₁=n₂=10, Mann-Whitney U can detect effect sizes of Vargha-Delaney A ≈ 0.78 at α=0.05, a substantial improvement over n=5 (A ≈ 0.90). The increased campaign time (~48h vs ~39h) is the trade-off for publishable statistical confidence.

Target machine: **i7, 32 GB RAM**. Worst single footprint: 7-node KRaft at ~14 GB committed. HotStuff runs well under 2 GB.

## Thesis title & summary compliance

| Named algorithm | Theoretical chapter | Experimental |
|---|---|---|
| Paxos | ✓ paxos.md | ✓ Paxi |
| Raft | ✓ raft.md | ✓ etcd + KRaft |
| EPaxos | ✓ epaxos.md | ✓ Paxi |
| Tendermint | ✓ tendermint.md | ✓ CometBFT |
| **HotStuff** | ✓ hotstuff.md | **✓ asonnino/hotstuff** |

| Evaluation criterion | Coverage |
|---|---|
| Consensus latency | `latency.csv` per run, fig02 hero (HotStuff: mean-only, documented) |
| Throughput | `throughput.csv` per run, fig01 + fig04 heroes |
| Fault tolerance | 6 scenarios, correctness check per run |
| Scalability | 3/5/7 for kraft and etcd, fig06 hero |

## What to cite

- **Paxos**: Lamport 1998/2001, Ailidani 2019 (`3299869_3319893.pdf` in project bundle)
- **Raft**: Ongaro & Ousterhout 2014 (`atc14paperongaro.pdf`)
- **ZAB**: Junqueira et al. 2010 (`279227_279229.pdf`)
- **Kafka / KRaft**: Wang et al. 2015 (`2023397.pdf`), KIP-500
- **EPaxos**: Moraru et al. 2013, Ailidani 2019
- **Tendermint**: Buchman 2016 (`Buchman_Ethan_201606_Msater_thesis.pdf`)
- **HotStuff**: Yin et al. 2019 (`HotStuff_BFT_Consensus_with_Linearity_and_Responsi.pdf`, `1803_05069v6.pdf`)
- **HotStuff implementation**: Sonnino, *asonnino/hotstuff*, github.com/asonnino/hotstuff, Apache 2.0
- **BFT landscape**: `Accountable_Tendermint___DSN_2022.pdf`, `2103_04234v4.pdf`, `2010_11454v10.pdf`, `2309_17245v1.pdf`
