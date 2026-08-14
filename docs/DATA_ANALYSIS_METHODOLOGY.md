# Data Analysis Methodology — From Raw Measurements to Defensible Conclusions

This is the methodology for making the benchmark data *reliable* and the
conclusions *defensible*. It is written so that large parts can transfer
into the thesis methodology chapter with light editing.

---

## 1. Experimental design

The experiment is a factorial matrix over six factors: **system** (KRaft,
Kafka+ZK, etcd, Tendermint/CometBFT, Paxos, EPaxos, HotStuff), **scenario**
(baseline, leader_kill, double_kill, packet_loss, network_partition,
slow_node), **fault severity** where the fault has a magnitude — currently
packet_loss, swept at **5% and 30%** (D14): 5% tests the preregistered
"modest degradation, continued availability" prediction and 30% probes where
degradation turns qualitative; severity is part of run identity, so the two
points are distinct cells with distinct configuration hashes, never merged —
**load mode** (saturation search; fixed-rate sweep at roughly
25/50/75% of the measured saturation point), **cluster size** (default
3 CFT / 4 BFT; a scalability subset {3,5,7} for the Raft implementations,
run on 7 provisioned nodes), and **command-conflict ratio** (0/2/10%, applied
to the Paxi pair Paxos/EPaxos — the knob EPaxos's fast path depends on, swept
by Paxi, Charapko '21, and EPaxos-Revisited). Implementation detail that
matters for interpretation: fraction *c* of operations is routed to one
designated conflict key that is *excluded* from the uniform range (uniform
traffic draws over the remaining 999 keys), so the realized conflict
fraction equals *c* by construction rather than *c* + (1−*c*)/K — at the
smallest sweep point (2%) that built-in bias would otherwise be a tenth of
the effect being measured. The manifest records the configured *c* per run. Every cell is repeated n = 5
times; failover trials (leader_kill recovery time) are repeated ≥ 30 times
because a distribution, not a mean, is the object of interest there. The value
payload is fixed at 1024 B (etcd/Kafka default; HotStuff's p1024 point) over a
reused K = 1000 keyspace (Paxi Table 3). Consensus nodes use 2 dedicated vCPUs
(matching Paxi's m5.large and Charapko's m5a.large); the BFT systems run on a
larger node class, so BFT and CFT results are compared within-family, never as
a bare cross-family throughput ratio (see §7). Kafka+ZooKeeper runs its ZK
ensemble colocated on the same three broker nodes, mirroring KRaft's combined
controller+broker mode, so the ZAB→Raft comparison (F6) holds hardware and
colocation constant and only the coordination machinery differs (D10); the
symmetric contention is stated in the figure caption. The load generator
itself runs on dedicated vCPUs (D11) — it is the instrument, and instrument
jitter is a validity threat like any other.

Three design controls protect against confounds. First, run order is
randomized within each system block, so time-correlated environmental drift
does not systematically favor one scenario. Second, every scenario that
mutates the cluster gets a fresh cluster per repetition — enforced in the
harness type system, not by discipline. Third, each run's manifest pins the
image digests, harness version, and configuration hash, making any cell
individually reproducible.

Warmup is 180 s discarded, measurement 300 s, justified empirically in the
pilot: the last minute of warmup must agree with the first minute of
measurement within a threshold fixed from pilot variance (a convergence
check, not an assumption). JVM systems (Kafka) and the JVM-based harness
itself are the reason the discard is generous.

The load model is uniform across systems: one open-loop generator with a
bounded in-flight window drives every driver, with latency recorded against
the *intended* departure time. This corrects coordinated omission — the
classic error where a blocked closed-loop client stops sampling exactly
while the system stalls, hiding the tail behavior a failover study exists to
measure. Saturation is found the way Paxi does it (raise offered concurrency
until throughput plateaus or latency climbs), and the fixed-rate sweep below
saturation produces the throughput–latency curves that the HotStuff paper
treats as the primary comparison artifact.

## 2. Metric definitions and their sources

Primary metrics come from the harness because only the client observes what
users observe. **Committed throughput** is completed-with-commit operations
per one-second bucket (real per-second series for every system). **Commit
latency** is intended-send to commit-acknowledgment, held in HdrHistogram;
reported as p50/p95/p99/p99.9 plus mean and max. **Failover time** is the
gap from injected kill to first subsequent successful commit, over ≥ 30
trials. **Recovery profile** is the throughput time series in a ±60 s window
around the fault, aligned on the injection timestamp.

Secondary metrics come from Prometheus and exist to explain and to verify,
never to headline: node CPU/memory/disk/network and CPU-steal
(node_exporter, all six VMs); protocol internals where the system exposes
them — etcd's proposals committed/pending/failed, leader_changes, WAL fsync
duration; Kafka's request queue times, ISR shrink/expand,
under-replicated partitions via JMX; CometBFT's height, rounds, block
interval, txs per block. Paxi and asonnino-HotStuff expose no server
metrics; for them the server-side account is node_exporter plus parsed logs,
and every figure that touches them says so.

One definitional caveat carries through all reporting: CometBFT latency
structurally includes block-interval wait, so its latency is compared in
absolute terms only alongside that explanation; and Paxi commits to an
in-memory store while Kafka and etcd fsync — a durability asymmetry the Paxi
authors resolved by disabling etcd's persistence, which we refuse to do; we
document it instead.

## 3. Statistical treatment

Estimation is primary; hypothesis testing is secondary. With n = 5 the
Mann–Whitney U test's smallest attainable p-value is ~0.008, so the analysis
leans on effect sizes with uncertainty rather than significance stars. Per
cell we report the median with IQR and the mean with a 95% bootstrap CI
(10,000 resamples, fixed seed). Pairwise system comparisons report the
relative difference of means with a bootstrap CI and Cliff's delta;
Mann–Whitney U p-values are reported alongside with **Holm–Bonferroni
correction** across the comparison family — an upgrade over the current
analyse.py, which corrects for nothing.

Latency requires special handling that most benchmark reports get wrong:
percentiles are never averaged across runs. Per-run HdrHistograms are
*merged* into a pooled histogram for the headline distribution, and per-run
p99 values are shown separately as a spread to expose inter-run variability.
Distributions are presented whole — CDFs and HDR-style percentile plots —
not as isolated point percentiles.

Throughput series get a within-run stability check (coefficient of
variation over the measurement window; a drifting series fails validity
rather than being averaged into respectability). Failover results are
reported as full ECDFs with median and p95 annotated.

The outlier policy is: no silent removal, ever. A run is excluded only by
failing an automated validity check (§4), and every exclusion is listed with
its reason in the results appendix.

The anchors for this section, citable in the thesis: Hoefler & Belli's SC'15
rules for scientific benchmarking of parallel systems (report distributions,
never bare means; state everything needed to reproduce), Kalibera & Jones on
rigorous benchmarking with repetition-level variance, Jain's classic text
for experimental design, Tene on coordinated omission (the HdrHistogram
rationale), and the Paxi/PaxiBFT/HotStuff papers for the domain-specific
methodology this design mirrors.

## 4. Validity checks — automated, per run

A run is *valid* only if all of the following hold, evaluated by the
harness's ValidityChecker into `validity.json` beside the run's CSVs. One
meta-rule governs all six: **an empty metric series fails the gate that
needs it** — an empty result means the retrieval path is broken (wrong
label, dead target), which is exactly when a gate must not pass by default.

1. **Client-not-bottleneck**: loadgen CPU below 70% and loadgen CPU-steal
   ≈ 0 (dedicated vCPU, D11) for the whole window; for fixed-rate runs,
   achieved rate ≥ 99% of target; the in-flight window not pinned at its
   ceiling.
2. **Durability**: the per-system correctness probe passes (Kafka end
   offsets vs sent count; etcd key-scan; CometBFT height/tx audit) — a
   consensus benchmark that lost acknowledged data is void, not slow.
3. **Fault ground truth**: for injected faults, Prometheus corroborates —
   per-system witnesses (etcd leader-change counter; CometBFT round
   numbers; Kafka under-replicated partitions > 0), plus node_up as a
   generic extra (it only moves for VM-level faults — a `docker kill` of
   the SUT container never silences the host's node_exporter). Paxi and
   HotStuff expose no server metrics (§2): their gate is an explicit SKIP
   until the docker-events audit (P4.5) provides a kill witness. A
   "leader_kill" whose witness did not move is reclassified, not averaged
   in. Mark semantics (F47): the manifest's `fault_injected_at_ms` is
   stamped when the injection COMPLETED — for multi-command faults
   (partition = four iptables rules) the fault may bite mid-apply, so
   `failover_ms` is a lower bound on fault-effect→recovery; an earlier
   mark would let a pre-fault commit fake a ~0 failover.
4. **Environment stationarity**: CPU-steal below 1% on consensus nodes
   (dedicated vCPUs should show ~0; a violation means a platform problem);
   no unexpected container restarts (`docker events` audit).
5. **Convergence**: warmup-tail vs measurement-head throughput agreement
   within the pilot-derived threshold.
6. **Clock discipline**: chrony offsets under 5 ms on all VMs. Latency is
   measured on a single clock (the client's), so skew cannot corrupt it;
   skew only affects cross-node event alignment, and 5 ms is ample for a
   ±60 s fault window.

## 5. Presentation plan — each figure answers one question

| # | Figure | Question it answers | Source |
|---|--------|--------------------|--------|
| F1 | Throughput–latency curves per system (rate sweep) | How does each protocol trade latency for load? | harness |
| F2 | Saturation throughput, bar + bootstrap CI | Who commits most, at what confidence? | harness |
| F3 | Latency CDFs at a common sub-saturation rate | Fair tail-latency comparison | pooled histograms |
| F4 | Failover ECDF (≥30 trials) | How fast does each recover from leader loss? | harness |
| F5 | Throughput timeline ±60 s around fault, leader-change markers overlaid | What does failure *look like*? | harness + Prometheus |
| F6 | KRaft vs Kafka+ZK paired panels | The ZAB→Raft evolution story | harness |
| F7 | Scalability (cluster-size subset) | Quorum-size cost for Raft implementations | harness |
| F8 | Resource utilization at saturation, per node | *Why* the numbers are what they are | Prometheus |

Grafana serves live monitoring during the campaign and appendix
screenshots/defense demo; every numbered thesis figure renders from exported
CSVs so it is versioned and regenerable.

## 6. Drawing conclusions — the claim framework

A comparative claim enters the conclusions chapter only if it clears four
gates. It must be **estimated**, not just significant: the effect size CI
excludes the null (ratio CI excludes 1.0). It must be **mechanistically
consistent**: explainable by protocol structure — message complexity
(HotStuff's O(n) vs Tendermint's O(n²) authenticators), critical-path length
(PaxiBFT Table I), quorum sizes, batching design — and corroborated by the
Prometheus explanation layer (e.g., a leader-CPU ceiling for single-leader
protocols, which the Paxi paper predicts and F8 can show). It must be
**directionally consistent with published results** where any exist
(Paxi/PaxiBFT/HotStuff figures serve as sanity anchors for direction and
order of magnitude, never absolute comparison — environments differ). And it
must **survive its caveats**: any claim touching CometBFT latency, Paxi
durability, or HotStuff instrumentation carries its caveat in the same
sentence, not in a footnote.

Claims that clear fewer gates are reported as observations, not conclusions.

## 7. Threats to validity (drafted for the thesis)

**Internal**: residual cloud variability even on dedicated vCPUs (mitigated
by steal monitoring, randomized order, n=5); fault injection via docker
kill is process death, not hardware failure (standard practice, but named);
the harness JVM shares the loadgen with nothing else, yet its own GC can
perturb tails (mitigated: HdrHistogram allocation-free recording, generous
heap, GC logging checked in validity). **Hardware seam (deliberate, D9):** the
BFT systems run on a larger instance class than the CFT/Paxos systems (to
approach HotStuff's published 16-vCPU setup rather than floor it), so any
BFT-vs-CFT absolute comparison is confounded by hardware as well as protocol;
we therefore make only within-family absolute comparisons and treat
cross-family differences as directional, argued through the published results.

**Fault-model seam (Paxi, preregistered — F26, source-verified 2026-07-16):**
stock Paxi ships **no failure detector** — a follower forwards client
requests to the last-known leader indefinitely, so a hard leader kill
(`docker kill`, the same fault the CFT/BFT systems get) produces a
LIVENESS WEDGE, not a failover. We run Paxi in its default adaptive mode
and report this honestly: the *expected* observation for Paxi `leader_kill`
is that writes wedge (fail at the client's 5 s bound) with **no recovery**,
which is an implementation property of this research framework, not a
property of the Paxos protocol — the contrast with etcd/KRaft's sub-second
Raft re-election is itself a result. Consequence for reporting: Paxi
contributes no point to the failover ECDF (F4) — a documented absence,
never a fabricated recovery time — and its recovery-profile timeline (F5)
shows the wedge. (Rejected alternatives, recorded: `-ephemeral_leader`
would give a comparable failover but changes the *baseline* semantics too,
so every Paxi cell would run non-default; Paxi's own `/crash?t=` primitive
is a socket pause, not process death, a different fault class from the
other systems' `docker kill`.)

**Construct**: client-observed commit is the measured object — protocol-
internal consensus latency is inferred, not measured, except where server
metrics expose it; Paxi's in-memory commit vs fsync-backed systems; CometBFT
block-interval inclusion; HotStuff mean-dominated latency when log parsing
falls back.

**External**: one datacenter, one VM class, 3–4 node clusters — results
generalize to LAN deployments of small clusters, and the thesis says exactly
that; WAN behavior is discussed via the papers (PaxiBFT's WAN results), not
claimed from our data.

---

## Honest review of this methodology

Its strongest elements are the ones borrowed from people who got burned
before us: coordinated-omission correction, histogram pooling instead of
percentile averaging, automated validity gating with corroborated fault
ground truth, and estimation-first statistics at small n. Its known
weaknesses: n = 5 keeps the hypothesis tests nearly decorative even with
Holm correction (the CIs carry the load — if the pilot shows high variance,
the honest responses are more runs or wider claims, not smaller p-values);
the saturation-search procedure has a stopping-rule subjectivity that the
pilot must pin down numerically before the campaign; the fault-ground-truth
check depends on scrape intervals (5 s), so failover *validation* is coarse
even though failover *measurement* (client-side, 20 ms probe resolution) is
fine — the two roles must not be conflated when writing; and F8's
explanatory story is correlational — the thesis may say "consistent with a
leader CPU bottleneck," not "caused by," unless a targeted follow-up run
(e.g., leader pinned to a smaller instance) is added. Finally, this document
inherits the plan's honest dependency: none of it produces reliable data
until the WS3 calibration gate — harness vs established tools on the same
cluster — has been passed and its deltas written down.
