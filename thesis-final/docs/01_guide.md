# Thesis Experiment Guide — v5.1

Authoritative step-by-step guide for running the thesis experiments on an **i7 machine with 32 GB RAM** or on **5 Hetzner CPX21 VMs** via the cloud deployment scripts in `../deploy/`. Supersedes earlier iterations.

## 1. What v5.1 changes from v5

**Statistical power improvement.** Baseline scenarios now run **n=10** per cell (up from 5). With n₁=n₂=10, Mann-Whitney U can detect Vargha-Delaney A ≥ 0.78 at α=0.05 (vs A ≥ 0.90 with n=5). Fault scenarios remain n=5 since the thesis's primary claims (pairwise comparisons, bootstrap CIs) are built on baseline data.

**Network baseline calibration.** `run_batch.sh` now measures inter-container latency before the campaign and writes `results/environment.txt` with host details (CPU, kernel, Docker version) and ping-based round-trip times between all container hostnames.

**Methodology output.** `analyse.py` now generates `analysis/out/methodology.md` — a ready-to-drop-into-thesis section documenting all known methodology limitations (HotStuff mean-only latency, etcd summary-only throughput, Paxi spot-sample correctness check). Pairwise test output includes `vargha_delaney_a` effect sizes and `reliable` flags for zero-variance comparisons.

**Cloud deployment.** The `../deploy/` directory contains complete scripts for running the campaign on 5 Hetzner VMs. See `../deploy/lib.sh` for the container placement map. Total cost: ~€8 for 60 hours.

**Unit tests.** `pytest analysis/test_pipeline.py` — 20 tests covering data loading, aggregation, Mann-Whitney U, and data integrity. Passing tests = verified pipeline correctness.

## 2. The test matrix

**Seven systems**: kraft, zk, etcd, tendermint, paxos, epaxos, hotstuff.
**Six scenarios**: baseline, leader_kill, packet_loss, network_partition, double_kill, slow_node.
**Cluster sizes**: 3 (kraft, zk, etcd, paxos, epaxos), 4 (tendermint, hotstuff), plus 5 and 7 for kraft and etcd in scalability.

**Statistical design**: baseline cells run n=10; fault cells run n=5; scalability cells run n=3; leader-election runs 200 trials per system. Mann-Whitney U (not paired Wilcoxon — runs are independent) with Vargha-Delaney A effect size. Bootstrap 95% CI (seeded, 2000 resamples). One-sided Mann-Whitney for directed hypotheses (KRaft > ZK), two-sided for undirected comparisons.

Breakdown by session:

| Session | Content | Cells | Duration |
|---|---|---|---|
| 1 | kraft + zk, 4 scenarios, 10 baseline + 5 fault runs each | 50 | ~7.8h |
| 2 | etcd, 4 scenarios, 10 baseline + 5 fault runs | 25 | ~4.0h |
| 3 | tendermint, 4 scenarios, 10 baseline + 5 fault runs | 25 | ~4.0h |
| 7 | paxos + epaxos, 4 scenarios, 10 baseline + 5 fault runs | 50 | ~7.8h |
| 8 | hotstuff, 4 scenarios, 10 baseline + 5 fault runs | 25 | ~4.0h |
| 4 | Scalability: kraft/etcd sizes 5 and 7, baseline only, 3 runs | 12 | ~1.6h |
| 5 | Deep faults: 7 systems × 2 scenarios × 5 runs | 70 | ~9.3h |
| 6 | Leader-election CDFs: 200 trials × 7 systems | 1400 trials | ~9.8h |
| **Total** | | 257 main cells + 1400 LE trials | **~48h** |

Run as eight unattended overnight sessions. HotStuff image build takes an extra 5–10 min on first run.

## 3. Machine preparation (one-time, 30 minutes)

As in v4. The only new dependency for v5 is enough disk to store the Rust build cache inside the HotStuff image during `docker build`:

```bash
sudo apt update && sudo apt install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update && sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker $USER
# log out, log back in
docker compose version

sudo apt install -y jq python3-pip python3-venv tuned
sudo systemctl enable --now tuned
sudo tuned-adm profile latency-performance

python3 -m venv .venv
source .venv/bin/activate
pip install pandas numpy scipy matplotlib tabulate
```

## 4. Build images (one-time, 15 minutes)

```bash
cd /path/to/thesis-final
docker network create thesis-net

# Pull images we don't build
for f in compose/kafka-kraft.yml compose/kafka-kraft-5.yml compose/kafka-kraft-7.yml \
         compose/kafka-zk.yml compose/etcd.yml compose/etcd-5.yml compose/etcd-7.yml \
         compose/tendermint.yml compose/observability.yml; do
  docker compose -f "$f" pull
done

# Build our three custom images
docker build -t le-probe:latest       scripts/probes/
docker build -t thesis-paxi:latest    docker/paxi/
docker build -t thesis-hotstuff:latest docker/hotstuff/    # new in v5, ~5–10 min

chmod +x scripts/*.sh scripts/pumba/*.sh
```

Verify:

```bash
docker images | grep -E "kafka|etcd|cometbft|prom|grafana|cadvisor|le-probe|thesis-paxi|thesis-hotstuff"
```

You should see `thesis-hotstuff:latest`. The build clones github.com/asonnino/hotstuff main branch and compiles the `node` and `client` binaries into the image. The Rust dependency compile is slow the first time (cargo builds rocksdb, tokio, dalek); subsequent docker builds with layer caching are near-instant.

## 5. Smoke test (2 hours, attended)

```bash
docker compose -f compose/observability.yml up -d
./scripts/wait_healthy.sh observability
```

Run one cell from each system family:

```bash
./scripts/run_batch.sh kraft baseline      # ~45 min — Kafka path
./scripts/run_batch.sh paxos baseline      # ~45 min — Paxi path
./scripts/run_batch.sh hotstuff baseline   # ~45 min — HotStuff path
```

Inspect the output of each. For HotStuff specifically, also check:

```bash
cat results/hotstuff/baseline/run001/fab.log | grep -A 15 "SUMMARY:"
```

You should see the asonnino SUMMARY block with a consensus TPS in the ~500–1000 range and latency around 2–20 ms. If TPS is zero or SUMMARY is missing, see §10.

## 6. Full campaign (eight unattended sessions)

Individual sessions:

```bash
nohup ./scripts/run_batch.sh session1 > results/batch-s1.log 2>&1 &   # kraft + zk main
nohup ./scripts/run_batch.sh session2 > results/batch-s2.log 2>&1 &   # etcd main
nohup ./scripts/run_batch.sh session3 > results/batch-s3.log 2>&1 &   # tendermint main
nohup ./scripts/run_batch.sh session7 > results/batch-s7.log 2>&1 &   # paxos + epaxos main
nohup ./scripts/run_batch.sh session8 > results/batch-s8.log 2>&1 &   # hotstuff main (NEW)
nohup ./scripts/run_batch.sh session4 > results/batch-s4.log 2>&1 &   # scalability
nohup ./scripts/run_batch.sh session5 > results/batch-s5.log 2>&1 &   # deep faults (incl. hotstuff)
nohup ./scripts/run_batch.sh session6 > results/batch-s6.log 2>&1 &   # leader-election (incl. hotstuff)
```

Or everything:

```bash
nohup ./scripts/run_batch.sh all > results/batch-all.log 2>&1 &
```

All sessions are idempotent. Interrupted runs resume where they left off.

## 7. Analysis

```bash
source .venv/bin/activate
python analysis/analyse.py --results-dir ./results --out ./analysis/out
```

Output in `analysis/out/`:

```
summary.csv              per (system, scenario, metric): mean, median, std, 95% CI, n, zero_variance flag
comparison.csv           wide-format table
pairwise_tests.csv       Mann-Whitney U + Vargha-Delaney A effect size per pair, reliable flag
leader_election.csv      per-trial failover time
methodology.md           auto-generated methodology section for thesis (lists all known limitations)
plots/
  fig01_throughput.png        all 7 systems
  fig02_latency_cdf.png       (HotStuff labeled "mean-only")
  fig03_leader_election.png   all 7 systems
  fig04_under_fault.png
  fig05_kraft_vs_zk.png
  fig06_scalability.png
  appendix_XX_*.png
report.md
```

Re-run `analyse.py` after every session for incremental intermediate results.

## 8. Mapping results to thesis chapters

| Thesis chapter | Hero figure | Primary claim source |
|---|---|---|
| Steady-state performance | `fig01_throughput.png` | summary.csv baseline throughput |
| Latency characteristics | `fig02_latency_cdf.png` | summary.csv lat_p99_us (note HotStuff caveat) |
| Failover behavior | `fig03_leader_election.png` | leader_election.csv groupby |
| Fault tolerance | `fig04_under_fault.png` | comparison.csv across scenarios |
| Scalability | `fig06_scalability.png` | summary.csv by cluster_size |
| Evolution (KRaft from ZAB) | `fig05_kraft_vs_zk.png` | pairwise_tests.csv |
| BFT comparison (HotStuff vs Tendermint) | (compare from fig01, fig02, fig03) | pairwise_tests.csv rows (hotstuff, tendermint) |

Read the per-algorithm doc in `docs/algorithms/` before writing each chapter. For HotStuff specifically, `hotstuff.md` documents the methodology trade-offs (mean-only latency, single-container validator layout, process-level fault injection) that you should also document in the thesis's methodology chapter.

## 9. Running the visualizer

Between benchmark sessions only:

```bash
for f in kafka-kraft kafka-kraft-5 kafka-kraft-7 kafka-zk etcd etcd-5 etcd-7 \
         tendermint paxi-paxos paxi-epaxos hotstuff observability; do
  docker compose -f "compose/$f.yml" down -v 2>/dev/null
done

cd visualizer
RESULTS_DIR=$(pwd)/../results docker compose up -d
```

## 10. Common problems

**HotStuff image build fails**: the asonnino/hotstuff main branch occasionally has build breakage due to Rust dependency updates. If `docker build -t thesis-hotstuff docker/hotstuff/` fails, pin to a known-working commit by editing `docker/hotstuff/Dockerfile`:

```dockerfile
RUN git clone https://github.com/asonnino/hotstuff.git . && \
    git checkout <known-good-sha>
```

**HotStuff SUMMARY missing from fab.log**: `fab local` spawns 4 node processes and a client process in a tmux session. If they crash during startup, fab exits without producing a SUMMARY. Check `cat results/hotstuff/baseline/run001/fab.log` for errors. Common causes:
- Out of memory (increase container mem_limit in compose/hotstuff.yml)
- Port conflict (HotStuff uses 9000-9003 inside the container)
- Rate too high for the host CPU (lower HS_RATE in run_one.sh)

**HotStuff LE trials time out**: each trial spins up and tears down an independent 4-validator testbed, which takes ~10-15 seconds just for bring-up. 200 trials × 25 seconds each = ~1.4h minimum wall time. If trials take noticeably longer, the HotStuff container may be struggling to fully tear down between trials. Restart the container: `docker compose -f compose/hotstuff.yml restart`.

**CometBFT testnet init fails after `down -v`**: expected — the init container regenerates keys. Check `docker volume ls | grep cmt-testnet` is empty before `up`.

**Larger clusters slow to become healthy**: KRaft at 7 nodes needs ~2 minutes; batch timeout is 240s.

**Paxi build fails**: the ailidani/paxi master occasionally has breakage. Pin the checkout in `docker/paxi/Dockerfile` similarly to HotStuff.

## 11. Command cheat sheet

Setup (once):
```bash
sudo tuned-adm profile latency-performance
docker network create thesis-net
docker compose -f compose/observability.yml up -d
docker build -t le-probe:latest scripts/probes/
docker build -t thesis-paxi:latest docker/paxi/
docker build -t thesis-hotstuff:latest docker/hotstuff/
```

Smoke test (135 min):
```bash
./scripts/run_batch.sh kraft baseline
./scripts/run_batch.sh paxos baseline
./scripts/run_batch.sh hotstuff baseline
```

Full campaign (~39h across eight overnight sessions):
```bash
nohup ./scripts/run_batch.sh all > results/batch-all.log 2>&1 &
```

Analysis:
```bash
source .venv/bin/activate
python analysis/analyse.py --results-dir ./results --out ./analysis/out
```

## 12. Thesis summary compliance

| Named algorithm (from summary) | Theoretical | Experimental |
|---|---|---|
| Paxos | ✓ `paxos.md` | ✓ Paxi |
| Raft | ✓ `raft.md` | ✓ etcd + KRaft |
| EPaxos | ✓ `epaxos.md` | ✓ Paxi |
| Tendermint | ✓ `tendermint.md` | ✓ CometBFT |
| **HotStuff** | ✓ `hotstuff.md` | **✓ asonnino/hotstuff** |

All 5 summary-named algorithms have both theoretical analysis and experimental measurement. This is the strongest possible coverage of the thesis summary's commitments.

The thesis scoping paragraph for the experimental chapter:

> "Experimental evaluation covers seven systems spanning the evolution of consensus protocols: Paxos and EPaxos via the Paxi framework (Ailidani et al. 2019), Raft via etcd (CoreOS/CNCF) and Kafka KRaft (KIP-500), ZAB via Kafka in ZooKeeper mode, the Byzantine-tolerant Tendermint via CometBFT (v0.38.12), and HotStuff via Alberto Sonnino's reference implementation (asonnino/hotstuff, 2-chain Diem variant). All five algorithms named in the thesis summary (Paxos, Raft, EPaxos, HotStuff, Tendermint) are both analyzed theoretically and measured experimentally on a single-host Docker testbed. Evaluation criteria are those specified in the thesis title: consensus latency, throughput, fault tolerance, and scalability."
