# Master Plan — Multi-VM Benchmark Campaign with Unified Harness & Observability

This document records the decisions, the architecture, and the concrete
workstream actions. The companion document DATA_ANALYSIS_METHODOLOGY.md
defines how the produced data becomes reliable results and defensible
conclusions.

---

## 1. Decisions (with rationale)

**D1 — Multi-VM topology on Hetzner, confirmed.** One consensus node per VM,
real NIC-to-NIC latency (~0.2–0.3 ms), dedicated resources per node. This is
the environment class every experimental paper in the bundle uses (Paxi:
m5.large ×9; PaxiBFT: m5a.large ×4–20; HotStuff: c5.4xlarge, one replica per
VM).

**D2 — Docker remains the packaging, not the topology.** "Not Docker" means
rejecting the single-host container *cluster*, which we do. On each VM we
still run the system as one container with `--network host` and a pinned
image digest: host networking bypasses the bridge/NAT layer (measurement
overhead ≈ nil), and images are the only reproducible way to deploy Kafka,
CometBFT, Paxi, and the HotStuff build. Bare-metal installs would buy nothing
measurable and cost days of build fragility.

**D3 — VM types: dedicated vCPU for consensus nodes.** Shared-vCPU instances
(CPX) expose the experiment to noisy neighbors — CPU steal is a validity
threat for a benchmark. Consensus nodes n1–n4 run CCX13 (2 dedicated vCPU,
8 GB); loadgen and obs run CPX21 (3 shared vCPU — the harness and Prometheus
tolerate shared CPU; we monitor steal anyway). ≈ €0.15/h for the six VMs;
a full campaign inside €15.

**D4 — The Java harness is the load generator; Paxi is a system under test
and a validation instrument, not the harness.** Verified against the paper:
Paxi's REST client is designed so external tools can benchmark *Paxi*; its
own benchmarker drove etcd only via the REST-compatible raftexample. Driving
Kafka/CometBFT/HotStuff would require proxy shims that put an extra hop
inside every latency sample. What we take from Paxi instead: (a) it stays the
Paxos/EPaxos implementation under test; (b) its benchmarker becomes our
**cross-validation oracle** — the harness's PaxiDriver numbers must agree
with Paxi's own benchmark on the same cluster before any result is trusted;
(c) its workload model (Table 3: K=1000 keys, tunable write ratio,
uniform/zipfian distributions, concurrency-until-saturation) is the template
the WorkloadEngine implements, citable as such.

**D5 — Two-layer measurement: harness primary, Prometheus/Grafana secondary.**
Client-observed metrics (HdrHistogram latency, per-second committed
throughput, failover time) are the *results*. Prometheus is the *explanation
and validity layer*: per-node resources, protocol internals, fault ground
truth. Grafana is the exploration surface and the live campaign monitor —
final thesis figures render from exported CSVs (matplotlib) for consistency,
with Grafana panels available for the appendix and the defense demo.

**D6 — Durability asymmetry is documented, not hidden.** Paxi commits to an
in-memory store; Kafka and etcd fsync. The Paxi authors handled this by
*disabling etcd's persistence* for their comparison — we will not weaken the
production systems; we will document that Paxi/PaxiBFT-style implementations
pay no disk cost, and interpret accordingly (construct-validity threat,
§7 of the methodology doc).

**D7 — Command-conflict ratio is a first-class workload factor for the Paxi
pair (0/2/10%).** EPaxos's whole contribution is the leaderless fast path,
which only shows up under contention; every precedent that studies it sweeps a
conflict knob — Paxi (SIGMOD '19) parameterises `c`, Charapko '21 goes to 10%,
EPaxos-Revisited '21 controls conflict rate directly. Without it EPaxos measures
identically to Paxos and the most interesting result disappears. This requires
the harness to reuse a bounded K=1000 keyspace with a controllable conflict
fraction (the current `keyFor()` emits globally-unique keys → 0% conflict
always; that is a bug to fix, not a design).

**D8 — Raft scalability {3,5,7} is a real result; provision 7 consensus VMs for
those cells only.** etcd and KRaft are both Raft, so size-scaling isolates the
implementation from the protocol (F7). Node count is a Terraform variable
(`consensus_node_count`): 3 for the CFT phase, 7 for the scalability session
only, destroyed after — keeping cost bounded (CAMPAIGN_RUNBOOK §2).

**D9 — BFT nodes are upsized toward the published class; the resulting
hardware seam is an accepted, documented threat.** CFT/Paxos nodes stay CCX13
(2 dedicated vCPU — matching Paxi m5.large and Charapko m5a.large exactly);
CometBFT and HotStuff run on a larger class (≈CCX23/33) because HotStuff was
evaluated on 16-vCPU c5.4xlarge and 2 vCPU would floor it far below anything
comparable. **Consequence:** BFT-vs-CFT absolute throughput is now confounded by
hardware as well as protocol, so cross-family comparisons are directional-only
(argued through the papers); within-family comparisons (etcd/KRaft/Paxos,
CometBFT/HotStuff) remain clean. This roughly doubles compute cost — still
under ~€70 for a full campaign.

**D10 — Kafka+ZooKeeper runs ZK colocated on the three broker nodes.** The
ensemble (zk1–3) and the brokers (broker1–3) are separate containers on the
same three VMs, mirroring how KRaft runs its controller+broker combined on
those same VMs. That makes F6 — the ZAB→Raft evolution panel, the thesis's
signature figure — a comparison where hardware and colocation are held
constant and only the coordination machinery differs. Consequence: brokers
and ZK contend for the same 2 vCPUs, exactly as KRaft's controller shares
with its broker — the contention is symmetric by design, and it is said in
the figure caption. (Alternative rejected: ZK on separate VMs would give
Kafka+ZK more total hardware than KRaft and un-mirror the comparison.)
ZK 3.6+ exposes native Prometheus metrics on :7000 (scrape job added).

**D11 — The load generator gets dedicated vCPUs (CCX13).** The loadgen is
the measuring instrument: every latency sample is stamped by its clock and
scheduled by its CPU. Running it on shared vCPUs while calling CPU steal a
validity threat on consensus nodes was inconsistent — steal on the loadgen
corrupts tails undetectably. Cost delta ≈ €0.05/h (noise). A loadgen
CPU-steal check joins the validity gates (methodology §4.1).

**D12 — A simulation is a typed Java record, published as JSON** *(decided
2026-08-14)*. One experiment definition = a named constant in the harness,
not a parsed config file. The rebuild's founding lesson is that typed Java
makes the v6 string-surgery bug class inexpressible at compile time; a YAML
simulation file would reintroduce exactly that, and buy a parser, a schema,
and a new failure mode for a benefit ("rerun without rebuilding") nobody has
needed. Instead the runner **serializes the fully resolved spec** to
`simulation.json` in the results tree and folds its hash into every manifest
— so the thesis appendix still gets a publishable, citable artifact, and a
mistyped scenario is still a compile error. Same discipline as the goldens:
the written text is the reviewable spec. Rejected: file-driven specs
(OpenMessaging/YCSB model) — reconsider only if the pilot needs threshold or
shape changes faster than a ~30 s rebuild allows. Detail:
`SIMULATION_AND_RULES_ANALYSIS.md` §4.

**D13 — Result confidence is an ORDINAL grade mechanizing methodology §6,
never a numeric score** *(decided 2026-08-14)*. Each cell carries a grade
derived from the existing four claim gates (effect estimated with a CI
excluding the null; mechanistically consistent; directionally consistent
with published results; survives its caveats) **plus validity-gate
coverage**: A = every applicable gate evaluated and PASS; B = as A with ≥1
SKIP, which the grade NAMES; C = a gate FAILed or n incomplete → reportable
as an observation, never as a conclusion; VOID = the run cannot evidence
what it claims (the F50/F70 class). The grade is shorthand for *which
criteria were met* and always ships with that list. A 0–100 score was
rejected: it invites false precision and cannot be defended in a viva.
Load-bearing constraint: **the grade may not ship before the gates actually
evaluate** — measured 2026-08-14 at 1 of 10.

**D14 — Packet-loss severity is a workload factor, swept at 5% and 30%**
*(decided 2026-08-14, resolving F53)*. The percentage was preregistered at
5% in `METRICS_AND_SOURCES.md` and hardcoded at 30% in `MatrixRunner` and
the golden. Rather than pick one, severity becomes a first-class factor like
D7's conflict ratio: 5% tests the preregistered "modest degradation,
continued availability" prediction, 30% probes where degradation becomes
qualitative. Both points must be preregistered with their expected direction
BEFORE the campaign runs. Cost: +5 runs per system (~+50 min, ≈ +€0.25),
which is noise against the ~€61 campaign. **Consequence that must be
implemented with it:** loss percentage becomes part of run identity — two
`packet_loss` cells at different severities currently resolve to the SAME
results path and the same `config_hash`, which is the v6 path-collision
class (see `SIMULATION_AND_RULES_ANALYSIS.md` §6, S1.1).

---

## 2. Target architecture

```
                        Hetzner private network 10.0.0.0/24
  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐
  │  node1  │  │  node2  │  │  node3  │  │  node4  │     CCX13 (dedicated)
  │ CCX13   │  │ CCX13   │  │ CCX13   │  │ CCX13   │     one consensus
  │ sut ctr │  │ sut ctr │  │ sut ctr │  │ sut ctr │     container each,
  │ node-exp│  │ node-exp│  │ node-exp│  │ node-exp│     --network host
  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘
       │            │            │            │
  ┌────┴────────────┴────────────┴────────────┴────┐
  │                                                 │
┌─┴───────────────┐                       ┌─────────┴───────┐
│    loadgen      │                       │      obs        │
│ CPX21           │                       │ CPX21           │
│ Java harness    │──── /metrics :9400 ──▶│ Prometheus      │
│ (RemoteSsh      │                       │ Grafana         │
│  orchestration, │                       │ (provisioned    │
│  fault inject,  │                       │  dashboards)    │
│  results CSVs)  │                       └─────────────────┘
└─────────────────┘
```

SSH: laptop → all VMs via public IPs (admin only). Loadgen → nodes via
private IPs using a **cluster keypair generated on loadgen during setup and
distributed to node authorized_keys** (fixes v6 C1–C3 by design). The
inventory file carries public and private IPs as separate fields.

Prometheus scrape targets: node_exporter on all six VMs; Kafka via JMX
exporter javaagent (KAFKA_OPTS, agent jar baked into image or mounted); etcd
via `--listen-metrics-urls http://0.0.0.0:2381`; CometBFT via
`instrumentation.prometheus = true` (:26660); the harness via micrometer
(:9400). Paxi and asonnino-HotStuff expose no native metrics — for those
systems the server-side story is node_exporter plus harness/log data, stated
plainly in the methodology.

---

## 3. Workstreams and tangible actions

### WS1 — Harness completion (builds on the compiled skeleton)
1. Convert skeleton to a Maven project; dependencies: kafka-clients, jetcd,
   HdrHistogram, sshj, micrometer-registry-prometheus, jackson, junit5.
2. Swap the pure-JDK LatencyRecorder internals for HdrHistogram (API stays);
   store true mean alongside percentiles (removes the documented placeholder).
3. Implement KafkaDriver (producer, acks=all, callback-timed commit ack),
   EtcdDriver (jetcd, natively async), CometBftDriver (pooled
   HttpClient.sendAsync on broadcast_tx_commit, in-flight window ≥200 —
   un-caps the 6 tx/s ceiling), finish PaxiDriver leader detection via the
   `Ballot` response header (2026-07-16 correction, F22: paxi exposes no
   /state endpoint; committed paxos writes return the leader's ballot).
   HotStuff stays the documented docker-exec boundary.
4. WorkloadEngine additions: stepped **rate sweep** mode (for
   throughput–latency curves, the papers' canonical figure) and saturation
   search (raise in-flight until throughput plateaus — Paxi's method).
5. RemoteSshProvider (sshj) + FaultInjector on typed NodeHandles: docker
   kill; netem with the interface resolved via
   `ip -o route get <peer_private_ip>`; stress-ng; iptables partition with
   guaranteed heal.
6. ValidityChecker (per-run checks, §5 of methodology) writing
   validity.json; PrometheusExporter running query_range for a fixed PromQL
   set over [run_start, run_end] into `metrics/*.csv` inside each run
   directory — every run self-contained, Grafana reproducible offline.
7. Campaign runner: full matrix, per-run cluster recycle for
   `Scenario.mutatesCluster()`, size-aware result paths, randomized scenario
   order within each system block, systemd unit on loadgen for
   fire-and-forget with journald logging.
8. Tests: unit tests for engine/recorder; the **SSH dry-run harness** —
   record every remote command against a stub and assert container names,
   interfaces, and paths before anything touches a billed VM (the check v6
   never had).

### WS2 — Infrastructure
1. Revised cloud-init: add stress-ng, chrony, xxd; node_exporter as a host-
   network container on boot.
2. Provisioning script (hcloud CLI): 4×CCX13 + 2×CPX21, private network,
   dual-IP inventory emitted automatically; cluster-keypair generation and
   distribution step.
3. obs VM stack: docker compose with Prometheus (+ config templated from the
   inventory) and Grafana with **provisioned** datasource and dashboards
   (JSON in the repo — dashboards-as-code, reproducible).
4. Kafka image variant with the JMX exporter agent; CometBFT config with
   instrumentation on; etcd flags for the metrics listener.

### WS3 — Calibration and pilot (the reliability gate)
1. Manual bring-up of each system through the harness CLI; 60 s smoke per
   system; confirm Prometheus sees every target.
2. **Cross-validation runs**: KafkaDriver vs kafka-producer-perf-test;
   EtcdDriver vs etcd's benchmark tool; PaxiDriver vs Paxi's own benchmarker
   — same cluster, same duration. Acceptance: agreement within ~10% on
   throughput and the same latency order; investigate anything larger.
   Document the deltas in the thesis — this is the strongest available
   answer to "are the tests reliable."
3. Pilot: baseline-only, 2 runs per system → measure inter-run variance →
   confirm n=5 gives acceptable CI widths (or adjust), confirm 180 s warmup
   suffices (convergence check), fix validity thresholds from observed data.

### WS4 — Campaign
Full matrix unattended on loadgen; live Grafana campaign dashboard;
check_progress from laptop; download = results tree (CSVs + metrics/ +
validity.json per run) + Grafana dashboard JSONs; **nothing deleted from
servers by any download step**; servers destroyed only after local analysis
confirms completeness.

### WS5 — Analysis and reporting
analyse.py v2 per the methodology document (histogram pooling,
Holm-corrected pairwise tests, ECDFs, validity-aware exclusion), figure
generation for the eight planned figures, threats-to-validity section,
conclusions written strictly through the claim framework (§6 of the
methodology).

Sizing, honestly (no deadlines, but true costs): WS1 ≈ 4–5 focused days
(step 5 and 8 are the hard 40%); WS2 ≈ 1.5 days; WS3 ≈ 1 day plus cluster
time; WS4 ≈ 2 overnights; WS5 ≈ 2–3 days of analysis work before writing.

---

## 4. Honest review of this plan

Verified this session: Paxi's client-direction claim and the etcd/raftexample
mechanism (paper text), PaxiBFT's 90-client saturation setup, the in-memory
vs fsync asymmetry (the paper disabling etcd persistence), and the skeleton
the plan builds on compiles. Not verified: Hetzner CCX13 stock availability
in fsn1 (check before provisioning; nbg1 is the fallback), the JMX exporter
overhead on 2-vCPU brokers (small, but measure it in WS3 — run one
calibration pass with the agent off), and my WS1 estimate, which history
says to treat as a floor. Known residual weaknesses this plan accepts rather
than solves: HotStuff remains the least-instrumented system (no Prometheus,
mean-heavy client output — mitigated by the log-parsing path, flagged in
every figure it appears in); Paxi's durability asymmetry is documented, not
equalized; and scalability beyond 4 consensus VMs means provisioning extra
nodes for those cells only (a deliberate, costed decision left open until
WS3 confirms the base matrix is healthy). The single largest risk is
unchanged from the v6 post-mortem: WS1 step 5 is SSH orchestration, where
type safety does not catch semantic mistakes — which is why step 8's dry-run
harness and WS3's calibration gate are non-negotiable before the campaign
spends a euro on measurement.
