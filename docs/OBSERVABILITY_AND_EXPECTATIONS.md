# Observability & Expectations — what each algorithm SHOULD show, and how to tell real results from artifacts

The methodology's preregistration rule (MASTER_PLAN, methodology §1/§6) made
operational: for every algorithm, the expected behavior is written down HERE,
BEFORE the campaign, with its corpus anchor — so at analysis time every
deviation is classified (measurement artifact / implementation property /
environment / genuine protocol behavior) instead of rationalized after the
fact. This file is also the novice-facing "what am I looking at" guide for
the dashboards and the run phases.

Ground rules that make the data honest:
1. **The harness CSVs are the results; Grafana/Prometheus explain and
   validate.** No thesis number is ever read off a dashboard.
2. **Corpus anchors are DIRECTION and ORDER-OF-MAGNITUDE only** — the papers
   ran different hardware, cluster sizes, and payloads. Matching a paper's
   absolute number is neither expected nor claimed; contradicting its
   *direction* is a red flag to investigate.
3. **Laptop (Docker) runs are functional evidence only** — they prove wiring,
   never performance. `environment=local` is filtered from every figure.
4. An empty metric series FAILS the gate that needs it (runbook §5).

## 0. The observation workflow

**Docker phase (laptop — every test in PER_ALGORITHM_TEST_GUIDE):**
watch `docker ps` + `docker logs -f thesis-*` in one terminal, the test
output in another. There is no Prometheus here; the tests' assertions ARE
the observability. What each phase means: *pre-clean* (any WARN about
removed leftovers = a previous run crashed — fine once, suspicious every
time) → *deploy* (containers appear; a hang here is image/network, not
consensus) → *wait-healthy* (the per-system gate: etcd /health needs
QUORUM, paxi needs a COMMITTED write, Kafka needs ALL brokers joined,
CometBFT needs height ≥ 1, HotStuff needs boot lines) → *measurement*
(the -v per-second counter should sit near the target rate) → *teardown*
(`docker ps` must be empty of thesis-* afterwards — always).

**VM phase (campaign):** open `cb-campaign` (Campaign Overview) first —
its embedded text panel is the checklist; then the algorithm's own
dashboard. Correlate ONLY via the run's `manifest.json` timestamps
(`started_at`/`ended_at`/`fault_injected_at_ms`); pad ±15 s (scrape
alignment, runbook §5).

## 1. etcd (Raft) — the CFT reference point

**Preregistered baseline** (anchors in `corpus/`: Ongaro & Ousterhout —
Raft; Howard et al. 2015 "Raft Refloated"; Howard & Mortier 2020 "Paxos vs
Raft" — protocol-equivalent performance, leader-centric): stable single
leader, zero elections in a healthy window; commit latency = one RTT
(~0.2–0.3 ms LAN) + majority WAL fsync (low ms) → **single-digit-ms p50**
class on ccx13; throughput CPU- or fsync-bound with the LEADER's CPU line
on top (it fans out AppendEntries).
**Fault expectation:** leader_kill → election within the election-timeout
class (**sub-second failover**, the contrast anchor for Paxi's wedge);
follower_kill → no client-visible gap at all. DOUBLE_KILL (2 of 3) →
writes FAIL until the run ends (no quorum — correct, not broken).
**Watch:** `cb-etcd`. Baseline: has_leader=1 flat, leader-changes flat,
proposals-committed ≈ offered rate, fsync p99 low-ms.
**False positives:** fsync p99 ×10 spikes = neighbor disk contention
(environment — gate 4, rerun); a "leader_kill" with no counter step = a
follower was killed (targeting bug — gate 3 reclassifies, and the harness
would have had to misdetect the leader: check the run log's
`fault inject` line).

## 2. KRaft vs Kafka+ZooKeeper — the F6 evolution panel

**Preregistered baseline** (anchor: same-binary comparison is OUR design —
D10 holds hardware/colocation constant; Kafka literature + the G1 parity
evidence say acks=all/min-ISR-2 commits at majority): both modes should
be **statistically close at the data path** (produce path is identical
code); differences concentrate in metadata/failover behavior, not steady
throughput. Leader (partition-0) NIC-tx ≈ 2× followers at RF=3.
**Fault expectation:** broker kill → ISR shrink + URP > 0 during failover,
then recovery — KRaft's controller re-election is expected to be at least
as fast as the ZK path (that comparison IS the F6 story; do not assume
the direction, measure it).
**Watch:** `cb-kafka`. NOTE: the kafka_* JMX panels are UNVERIFIED until
P4.3 pins the exporter names — treat them as absent until then; node
panels + ZK znode_count are trustworthy now.
**False positives:** URP > 0 at baseline = the cluster formed degraded
(the provider's api-versions gate exists to prevent this — if you see it,
something changed; stop); a QUIET ZooKeeper during baseline is CORRECT
(Kafka touches ZK only for metadata) — do not read it as "ZK broken";
window-bound throughput (Little's Law) is the CLIENT's ceiling, not the
broker's.

## 3. CometBFT (Tendermint) — the BFT baseline

**Preregistered baseline** (anchors: Buchman et al. 2018; Cason et al.
2021 — block cadence ~1 s, thousands of tx/s with enough concurrency;
Alqahtani & Demirbas PaxiBFT — Tendermint ~1.7k tx/s class at 4 nodes
with 90 clients): height +1/s on every validator; **client p50 ≈ block
interval** (structural — stated in every figure); throughput scales with
in-flight window until the mempool/block-size knee, NOT with thread
count (the retired probe's 6 tx/s ceiling is the cautionary tale, G1
flaw-A).
**Fault expectation:** 1 kill (f=1 of n=4) → liveness preserved, brief
round-number activity; DOUBLE_KILL → **height flatlines** (2 > f,
intentional liveness-loss demo — the flatline is the result).
**Watch:** `cb-cometbft`.
**False positives:** rounds ≫ height at fixed load = proposer timeouts —
check CPU steal and NIC before calling it protocol behavior; harness
errors with height still climbing = tx-format rejections (nonce/'='
contract), a client bug class, not consensus.

## 4. Paxos / EPaxos (Paxi) — the conflict-knob pair

**Preregistered baseline** (anchors: Ailijiang et al. SIGMOD'19 — Paxi
itself, K=1000 keyspace, in-memory store; Moraru et al. 2013 EPaxos;
Tollman et al. 2021 "EPaxos Revisited"; Charapko et al. 2021): PAXOS =
stable leader (node1 after our probe-write gate), leader CPU/NIC on top;
EPAXOS at c=0 ≈ Paxos throughput class but SYMMETRIC lines (leaderless);
as c rises (2%→10%), EPaxos pays dependency-tracking/slow-path costs —
**the c-sweep separating the two IS the D7 result**. NO disk-write
signal on any Paxi node (in-memory, D6 — the durability asymmetry is
documented, not equalized).
**Fault expectation (F26, LOCKED):** Paxi leader_kill **WEDGES** — writes
fail at the 5 s bound until the run ends, `failover_ms: null`, no
recovery. This is the honest "stock paxi ships no failure detector"
result (implementation property, not a Paxos property) and the deliberate
contrast with etcd. **Do not "fix" the wedge.** Follower kill → commits
continue on 2/3 (verified locally, P2.4b).
**Watch:** `cb-paxi-hs`.
**False positives:** EPaxos asymmetry at c=0 = round-robin not reaching
all replicas (endpoint wiring, F24 class); ANY disk-write surge on a Paxi
node = something else writing on that VM (gate 4 stationarity violation).

## 5. HotStuff — the modern BFT endpoint

**Preregistered baseline** (anchors: Yin et al. 2019 — O(n) authenticator
complexity vs Tendermint's O(n²), throughput edge expected to GROW with
n; Malkhi & Nayak HotStuff-2; Jalalzai Fast-HotStuff for context;
published runs used c5.4xlarge — our D9 upsizing narrows but does not
close that gap, so within-family direction only): symmetric node
CPU/NIC (rotating leadership); client's target node slightly busier;
latency in the sub-second class at moderate rates (three-phase pipeline,
no fixed block-interval wait — EXPECT it below CometBFT's p50, that
directional comparison is preregistered).
**Measurement (NEXT-4b, resolved):** HotStuff now discards warmup like
every other system — the logs.py-port analyzer applies logs.py's own
formulas to only the post-warmup window, so `summary.txt` is the
steady-state number that enters cross-system figures. The full-run
(logs.py-exact, paper-comparable) number is still recomputable from the
saved `logs/`. Two caveats REMAIN and carry into the thesis: the D9
hardware seam (HotStuff on a larger node class) and the log-derived,
no-server-metrics nature — so HotStuff-vs-other-system throughput/latency
stays directional, never a bare ratio.
**Fault expectation:** BASELINE only in this harness today (NEXT-4
preregisters faults before implementing them).
**Watch:** `cb-paxi-hs` + the run's `logs/` — its logs ARE its metrics.
`summary.txt` must parse (it is validated at generation); `rate too high`
in client.log = client-side ceiling, lower the rate, the cell is not
system evidence.
**False positives:** a node at zero CPU while others commit = never
joined (committee mismatch); analyzer failing on a missing config line
at the first VM run = live-log format drift vs logs.py's regexes at
dc01ac8 — fix the regex WITH a fixture, never loosen to defaults.

## 6. Cross-system corpus cross-check (run at analysis time, M6.4)

For every figure, check DIRECTION against these before writing a word:
CFT (etcd/KRaft/Paxos) latency ≪ BFT latency at equal rate (message
complexity + block cadence); CometBFT p50 ≈ block interval; HotStuff
p50 < CometBFT p50 (pipeline vs interval); EPaxos-vs-Paxos gap appears
ONLY under conflict (D7 — if it appears at c=0, suspect the harness);
Raft failover sub-second, Paxi leader_kill = no failover point at all
(documented absence in F4); scalability 3→5→7 (D8): throughput per-op
cost grows with quorum size — modest decline, latency near-flat on LAN
(Charapko '21: "scalable but wasteful" — the wasted work grows).
A deviation from any of these is a FINDING to attribute, not a failure —
but it must be attributed before it is reported.

## 7. Cleaning up (nothing may leak between runs — or into the repo)

**Laptop/Docker after any test or local-run:**
```bash
docker ps -aq --filter name=thesis- | xargs -r docker rm -f   # containers (tests do this themselves)
docker network prune -f                                        # test networks (Ryuk usually handles)
rm -rf harness/results-local                                   # local-run output when done reading it
```
Tests tear down in `finally` + Ryuk; a `thesis-*` survivor after a GREEN
suite is a bug — report it. Images are KEPT on purpose (pinned digests +
the two local builds — re-pulling/rebuilding wastes time, costs nothing).
Maven artifacts: `target/` (gitignored) — `mvn21 clean` removes.

**VMs during a campaign:** the provider pre-cleans `thesis-*` on every
node at every start (F29) and removes containers at stop; config files
under `/root/thesis-*` are overwritten per run and die with `terraform
destroy`; `RemoteLogs` deletes its snapshot files after transfer. The
only durable VM state you must collect BEFORE destroy is the results
tree on the loadgen and the obs Prometheus snapshot — `scripts/
collect_block.sh` does both (see §8). After destroy:
`hcloud server list` MUST be empty (billing stops only by absence).

## 8. Getting everything onto the laptop, viewable offline

Per system block: `scripts/collect_block.sh <loadgen_public_ip>
<obs_public_ip> <dest_dir>` rsyncs the loadgen's `results/` tree
(CSVs + hlog + manifests + per-run `logs/`) and takes + downloads a
Prometheus TSDB snapshot from obs, into one dated directory. Offline
viewing: `observability/offline/docker-compose.yml` mounts that snapshot
into a local Prometheus (read-only) + the SAME provisioned Grafana
dashboards — what you saw live is what you replay offline. Sample of
what the collected tree looks like: `docs/examples/`.

## 9. How this feeds the analysis

`analysis/analyse.py` (F15's in-repo successor, foundation) walks the
collected tree: per-cell summaries (median/IQR, mean + bootstrap CI),
pooled `.hlog` histograms (never percentile-averaging), validity-aware
filtering (only `status: complete` + `environment` filter today; the six
gates join when ValidityChecker lands), and per-figure CSV exports. Every
number in a thesis figure must be regenerable from the collected
directory alone — that is the M6.4 definition of done.
