# Thesis Strategy & Coverage

## 1. How this bundle covers every thesis-summary claim

| Thesis summary phrase | Where it lives | File(s) |
|---|---|---|
| *"Evolution of consensus protocols will be studied"* | 6-stage evolution narrative: Paxos→ZAB→Raft→EPaxos→Tendermint→HotStuff. Each step documents what problem the next solved. | `docs/algorithms/comparison_matrix.md` |
| *"Most important analyzed theoretically and architecturally"* | 10 knowledge docs covering protocol theory, commit paths, message complexity, fault models, leader models, and production deployments. | `docs/algorithms/paxos.md`, `raft.md`, `zab.md`, `kraft.md`, `etcd.md`, `epaxos.md`, `tendermint.md`, `hotstuff.md`, `scalability.md` |
| *"EPaxos, HotStuff, Tendermint"* (explicitly named) | All three measured experimentally, plus Paxos and Raft (total: 5 of 5 named). | `compose/paxi-epaxos.yml`, `compose/hotstuff.yml`, `compose/tendermint.yml` |
| *"Throughput"* | 3 hero figures + per-run CSVs. Mann-Whitney U + Vargha-Delaney A effect sizes. | `analysis/analyse.py` → `fig01`, `fig04`, `fig05` |
| *"Latency"* | p99 CDF across systems. HotStuff mean-only limitation auto-documented. | `analysis/analyse.py` → `fig02` |
| *"Fault tolerance"* | 6 scenarios: baseline, leader_kill, packet_loss, network_partition, double_kill, slow_node. VM-aware injection via SSH. | `deploy/faults.sh`, `scripts/pumba/*.sh` |
| *"Scalability"* | KRaft + etcd at cluster sizes 3, 5, 7. Sub-linear curve per Raft theory. | `compose/kafka-kraft-5.yml`, `kafka-kraft-7.yml`, `etcd-5.yml`, `etcd-7.yml` |
| *"Experimental environment (Docker or VM cluster)"* | Both: 12 Docker Compose files (single-host) + 7 cloud deployment scripts (Hetzner, 5 VMs). | `compose/*.yml`, `deploy/*.sh` |
| *"Implementations of some of the above"* | Over-delivered: all 5 named + ZAB (7 systems total). | 7 compose files, 7 `start_*` functions in `deploy/lib.sh` |
| *"Representative scenarios"* | 257 main cells + 1400 leader-election trials. n=10 baseline for statistical power. | `scripts/run_batch.sh` |

**No claim in the thesis summary is unaddressed.** The weakest phrasing ("some of the above") is the strongest coverage (all five).

---

## 2. Strategy: Deploy on VMs

### 2.1 Prerequisites

- [ ] Hetzner Cloud account with API token (https://console.hetzner.cloud → Security → API Tokens)
- [ ] `hcloud` CLI installed: `curl -sL https://github.com/hetznercloud/cli/releases/latest/download/hcloud-linux-amd64.tar.gz | tar xz && sudo mv hcloud /usr/local/bin/`
- [ ] SSH key: `ssh-keygen -t ed25519 -f ~/.ssh/hetzner_thesis -N ""`

### 2.2 Create 5 VMs

```bash
# 1. Login
hcloud context create thesis
# Paste API token when prompted

# 2. Create private network
hcloud network create --name thesis-net --ip-range 10.0.0.0/16
hcloud network add-subnet thesis-net --type cloud --network-zone eu-central --ip-range 10.0.0.0/24

# 3. Upload SSH key
hcloud ssh-key create --name thesis-key --public-key-from-file ~/.ssh/hetzner_thesis.pub

# 4. Create 5 CPX21 servers (3 vCPU, 4 GB, ~€0.01/h each)
for name in node1 node2 node3 node4 loadgen; do
  hcloud server create \
    --name "thesis-$name" \
    --type cpx21 \
    --image ubuntu-24.04 \
    --location fsn1 \
    --network thesis-net \
    --ssh-key thesis-key \
    --user-data-from-file deploy/cloud-init.yaml
done

# 5. Get private IPs
for name in node1 node2 node3 node4 loadgen; do
  echo "$name: $(hcloud server describe "thesis-$name" -o json | jq -r '.private_net[0].ip')"
done
```

### 2.3 Configure inventory

```bash
# 6. Edit deploy/inventory.env
cp deploy/inventory.env.template deploy/inventory.env
# Fill in the 5 private IPs from step 5
```

### 2.4 Setup the cluster (15 min)

```bash
# 7. Make scripts executable
chmod +x deploy/*.sh

# 8. Run setup (waits for cloud-init, configures /etc/hosts,
#    uploads thesis bundle, builds custom images, distributes,
#    verifies inter-node latency)
./deploy/setup_cluster.sh
```

### 2.5 Manual vetting (optional, 30 min)

```bash
# 9. Start one system interactively, explore, tear down
./deploy/run_manual.sh kraft       # KRaft
./deploy/run_manual.sh etcd        # etcd
./deploy/run_manual.sh tendermint  # CometBFT
./deploy/run_manual.sh paxos       # Paxos
./deploy/run_manual.sh epaxos      # EPaxos
./deploy/run_manual.sh hotstuff    # HotStuff
# Press Enter to tear down each.
```

### 2.6 Run full campaign (~48h unattended)

```bash
# 10. Start full campaign (8 sessions, runs in background via nohup)
nohup ./deploy/run_campaign.sh > campaign.log 2>&1 &

# Or run one session at a time:
#   ./deploy/run_campaign.sh session1   # kraft + zk (~7.8h)
#   ./deploy/run_campaign.sh session2   # etcd (~4h)
#   ./deploy/run_campaign.sh session3   # tendermint (~4h)
#   ./deploy/run_campaign.sh session7   # paxos + epaxos (~7.8h)
#   ./deploy/run_campaign.sh session8   # hotstuff (~4h)
#   ./deploy/run_campaign.sh session4   # scalability (~1.6h)
#   ./deploy/run_campaign.sh session5   # deep faults (~9.3h)
#   ./deploy/run_campaign.sh session6   # leader-election (~9.8h)
```

### 2.7 Check progress

```bash
# 11. From your local machine, at any time:
./deploy/check_progress.sh
```

---

## 3. Strategy: Gather results

### 3.1 Download from loadgen

```bash
# 12. Download results (timestamped directory)
./deploy/download_results.sh
# Output: results-hetzner-YYYYMMDD_HHMMSS/
```

### 3.2 What each run produces

Every cell at `results/<system>/<scenario>/<run_id>/` contains:

| File | Content |
|---|---|
| `manifest.json` | system, scenario, run_id, cluster_size, timestamps, correctness result, status, kernel, docker version |
| `throughput.csv` | `t_off,ops_per_sec` — per-second (or per-5s) throughput samples during measurement window |
| `latency.csv` | `metric,value_us` — avg, p50, p95, p99, p99.9, max in microseconds |
| `correctness.txt` | PASS/FAIL/NO_DURABILITY_API/SKIP with detail |
| `run.log` | Full stdout/stderr from the benchmark run |
| `perf-raw.txt` | Original benchmark tool output (for debugging) |

Leader-election trials add `results/<system>/leader_election/<run_id>/trial.txt` with `system,run_id,failover_seconds`.

### 3.3 Network baseline

`results/environment.txt` contains host kernel, CPU, memory, Docker version, and inter-node ping latency — captured before the campaign starts.

---

## 4. Strategy: Analyze results

### 4.1 Run the analysis pipeline

```bash
# 13. Setup Python venv (once)
python3 -m venv .venv && source .venv/bin/activate
pip install pandas numpy scipy matplotlib tabulate

# 14. Run analysis on downloaded results
python3 analysis/analyse.py --results-dir results-hetzner-YYYYMMDD_HHMMSS --out analysis/out
```

### 4.2 Output files

| File | Use in thesis |
|---|---|
| `summary.csv` | Per-(system,scenario,metric): n, mean, median, std, 95% CI. `zero_variance=True` flags synthetic data. Stats table in experimental chapter. |
| `comparison.csv` | Wide-format table: systems as columns, scenarios/metrics as rows. Directly pasteable into thesis. |
| `pairwise_tests.csv` | Mann-Whitney U + Vargha-Delaney A per system pair. `reliable=False` marks zero-variance comparisons. Significance claims in evaluation chapter. |
| `leader_election.csv` | Per-trial failover seconds × 200 trials × 7 systems. CDF in fig03. |
| `methodology.md` | Auto-generated methodology section listing every known limitation. Drop into thesis methods chapter. |
| `report.md` | Markdown campaign summary with stats tables and methodology alerts. |
| `plots/fig01-06.png` | 6 hero figures + appendix box plots. Drop into thesis. |

### 4.3 Run unit tests (verify pipeline correctness)

```bash
# 15. Python tests
pytest analysis/test_pipeline.py -v

# 16. Bash tests (requires: sudo apt install bats)
bats analysis/test_bash.bats
```

---

## 5. Strategy: Visualize results

### 5.1 Start the visualizer

```bash
# 17. Point the visualizer at your results and start
cd visualizer
RESULTS_DIR=$(pwd)/../results-hetzner-YYYYMMDD_HHMMSS docker compose up -d

# 18. Open browser
# http://localhost:8090
```

### 5.2 Three views

| Tab | What it shows |
|---|---|
| **Results** | Pick a system + scenario → aggregated stats (mean, median, 95% CI, std), per-run charts, correctness results |
| **Compare** | Side-by-side across all 7 systems for one scenario. Headline cross-system comparison for thesis body. |
| **Algorithms** | Animated network topology per protocol (nodes, edges, message-passing). Scripted scenarios: normal operation, leader election, fault handling. |

### 5.3 Demo without real results

```bash
# 19. Create minimal stub data to see the UI
mkdir -p /tmp/demo-results/kraft/baseline/run001
echo '{"system":"kraft","scenario":"baseline","run_id":"run001","cluster_size":3,"status":"complete","correctness":"PASS"}' > /tmp/demo-results/kraft/baseline/run001/manifest.json
echo '180,10000\n185,10200' > /tmp/demo-results/kraft/baseline/run001/throughput.csv
echo 'metric,value_us\navg,5000\np50,4800\np95,12000\np99,15000\np99_9,20000\nmax,30000' > /tmp/demo-results/kraft/baseline/run001/latency.csv
cd visualizer && RESULTS_DIR=/tmp/demo-results docker compose up -d
```

---

## 6. Strategy: Tear down

```bash
# 20. Delete all Hetzner servers (billing stops immediately)
for name in node1 node2 node3 node4 loadgen; do
  hcloud server delete "thesis-$name"
done
hcloud network delete thesis-net

# 21. Stop visualizer
cd visualizer && docker compose down -v
```

---

## 7. Quick reference: from zero to thesis-ready data

```bash
# 1. Create VMs (Phase 2.2, steps 1-5)
# 2. Edit inventory.env (Phase 2.3, step 6)
# 3. ./deploy/setup_cluster.sh                     (step 8)
# 4. nohup ./deploy/run_campaign.sh > camp.log &   (step 10)
# 5. ./deploy/check_progress.sh                     (step 11, repeat)
# 6. ./deploy/download_results.sh                   (step 12)
# 7. python3 analysis/analyse.py --results-dir results-hetzner-* --out analysis/out  (step 14)
# 8. cd visualizer && RESULTS_DIR=../results-hetzner-* docker compose up -d  (step 17)
# 9. Open http://localhost:8090                      (step 18)
# 10. Delete VMs when done                           (step 20)
```

---

## 8. Statistical design summary

| Parameter | Value | Rationale |
|---|---|---|
| Baseline runs per cell | **n=10** | Mann-Whitney U with n₁=n₂=10 detects Vargha-Delaney A ≥ 0.78 at α=0.05 |
| Fault runs per cell | **n=5** | Primary claims (pairwise comparisons, CIs) use baseline data |
| Scalability runs | **n=3** | Three data points (3,5,7) distinguish linear from non-linear trends |
| Leader-election trials | **200** per system | Produces smooth CDF with ~5% margin of error |
| Warmup | **180s** before measurement | Ensures leader election, log replay, and steady state |
| Measurement window | **300s** after warmup | Captures steady-state performance; long enough for fault effects |
| Statistical test | **Mann-Whitney U** | Correct for independent samples (not paired Wilcoxon) |
| Effect size | **Vargha-Delaney A** | Sample-size-independent; A>0.56 small, A>0.64 medium, A>0.71 large |
| Confidence intervals | **Bootstrap 95% CI** | Non-parametric; 2000 resamples; deterministic seed (42) |

---

## 9. Methodology limitations (documented, not hidden)

| Limitation | System(s) | How the pipeline handles it |
|---|---|---|
| Mean-only latency (no percentiles) | HotStuff | All percentile fields = mean. Marked `zero_variance=True` in summary.csv. Pairwise latency tests marked `reliable=False`. |
| Summary-only throughput (no per-second) | HotStuff, etcd (partial) | Single progress line. Between-run variance is genuine; within-run variance unavailable. |
| No durability verification | HotStuff | Reports `NO_DURABILITY_API` with benchmark execution status. |
| Spot-sample correctness (18 keys) | Paxos, EPaxos | Paxi has no scan API. 18-key spot-check documented in methodology.md. |
| No Byzantine fault injection | Tendermint, HotStuff | Crash faults test BFT liveness bounds. Byzantine safety (equivocation, invalid signing) not tested. Scope limitation. |
| Co-located containers (Docker Compose) | All (local mode) | Not used for thesis performance claims. VM deployment avoids this. |
| 4 BFT validators (minimum) | Tendermint, HotStuff | 3f+1 with f=1. Entry point of the curve, not full characterization. |
| Kafka metrics require JMX exporter jar | KRaft, ZK | Prometheus targets exist but need agent added to containers. etcd + CometBFT metrics collected. |
