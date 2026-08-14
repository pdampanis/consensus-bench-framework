package gr.thesis.bench.campaign;

import gr.thesis.bench.core.EventLog;
import gr.thesis.bench.core.HarnessMetrics;
import gr.thesis.bench.core.LatencyRecorder;
import gr.thesis.bench.core.Scenario;
import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.core.WorkloadEngine;
import gr.thesis.bench.driver.CometBftDriver;
import gr.thesis.bench.driver.ConsensusDriver;
import gr.thesis.bench.driver.EtcdDriver;
import gr.thesis.bench.driver.HotStuffLogAnalyzer;
import gr.thesis.bench.driver.KafkaDriver;
import gr.thesis.bench.driver.PaxiDriver;
import gr.thesis.bench.results.CsvResultsWriter;
import gr.thesis.bench.results.PrometheusExporter;
import gr.thesis.bench.topology.ClusterProvider.NodeHandle;
import gr.thesis.bench.topology.LocalDockerProvider;
import gr.thesis.bench.topology.RemoteSshProvider;
import gr.thesis.bench.topology.SshExecutor;
import gr.thesis.bench.topology.SshFaultInjector;
import gr.thesis.bench.topology.SshjExecutor;
import gr.thesis.bench.validity.ValidityChecker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The campaign session mode (M3.3 core): ONE (system, scenario, size, load)
 * cell against the real VMs, driven end-to-end from the loadgen —
 * inventory-typed topology, golden-tested provider + injector, per-system
 * production driver, fault mark + failover events, environment=hetzner
 * results. The full-matrix runner (randomized order, n=5, resume) loops
 * THIS; it is deliberately a thin future layer, because everything with
 * failure modes lives behind tests here.
 *
 * Fault flow (scenario != BASELINE): a companion thread sleeps until
 * {@code faultAtSecs} into the run, resolves the target (the DETECTED
 * leader at injection time — F13/F19; deterministic replica 0 only for
 * the two systems with no stable leader BY DESIGN, EPaxos and CometBFT),
 * applies through {@link SshFaultInjector}, and stamps the {@link EventLog}
 * mark. heal() runs in finally, always. An injection failure is rethrown
 * after the run — a "fault run" whose fault never fired must fail loudly,
 * never masquerade as data (the v6 reclassification lesson).
 *
 * HOTSTUFF is the documented exception: no ConsensusDriver — the upstream
 * client (the SUT's own load generator) runs on the LOADGEN VM, its logs
 * plus the four node logs are collected whole (RemoteLogs), and the
 * logs.py-port analyzer produces the canonical SUMMARY. BASELINE only for
 * now; hotstuff fault scenarios are preregistered in PENDING_TASKS.
 */
public final class RemoteRunner {

    private static final Logger log = LoggerFactory.getLogger(RemoteRunner.class);
    private static final int SSH_PORT = 22;
    /** Cluster formation on real VMs: image runs + quorum gates across a
     *  real network — generous, and every gate inside fails closed sooner
     *  with a named node. */
    private static final Duration HEALTH_DEADLINE = Duration.ofMinutes(3);
    /** How long the fault thread waits for the engine to start the event log
     *  (F47's alignment). A LEAK-STOPPER, not a timing gate: connect() is
     *  seconds on a formed cluster, so this can only expire when
     *  {@code engine.run()} died inside connect() and start() will never
     *  come — deliberately generous so it can never cut a slow-but-healthy
     *  connect short. */
    private static final Duration ENGINE_START_WAIT = Duration.ofMinutes(2);
    /** How long the runner waits for the fault thread after the load ends. */
    private static final long FAULT_JOIN_MILLIS = 30_000;

    /** Everything one cell needs. Constructed by Main from CLI args. */
    /**
     * @param leaderlessTargetIndex which replica a leader-sensitive fault
     *        hits on the systems that HAVE no leader to detect (EPaxos is
     *        leaderless by design; CometBFT's proposer rotates every height).
     *        D15.5: 0 for the n=5 fault cells so each cell is reproducible
     *        from its config_hash, and rotated across replicas for the >=30
     *        failover trials so the distribution TESTS the symmetry
     *        assumption rather than asserting it. Ignored for every system
     *        that reports a real leader — those always target the DETECTED
     *        one (F13/F19).
     */
    public record Spec(SystemUnderTest system, Scenario scenario, int clusterSize,
                       long ratePerSec, int durationSecs, int warmupSecs, int window,
                       int valueSizeBytes, double conflictRatio, int faultAtSecs,
                       int packetLossPercent, int leaderlessTargetIndex, Path out, String runId,
                       Path inventoryFile, String sshUser) {
        public Spec {
            // Fault scenarios only (F48): BASELINE never starts a fault
            // thread, and Main DEFAULTS fault-at to warmup+60 — rejecting a
            // short baseline over an input it never reads is fail-closed on
            // the wrong thing.
            if (scenario != Scenario.BASELINE && faultAtSecs >= durationSecs) {
                throw new IllegalArgumentException("faultAt (" + faultAtSecs
                        + "s) must fall inside the run (duration " + durationSecs + "s)");
            }
            // D14/F53: severity is required exactly where it means something.
            // Before this, `--loss` defaulted to 30 for EVERY scenario, so a
            // leader_kill spec silently carried a packet-loss percentage it
            // never used — harmless until severity became part of cell
            // identity, at which point it would have put a `loss30` segment
            // on runs that never lost a packet.
            if (scenario == Scenario.PACKET_LOSS) {
                if (packetLossPercent < 1 || packetLossPercent > 100) {
                    throw new IllegalArgumentException(
                            "PACKET_LOSS needs --loss in [1,100], got " + packetLossPercent
                                    + " (D14 sweeps 5 and 30; there is no safe default)");
                }
            } else if (packetLossPercent != 0) {
                throw new IllegalArgumentException(
                        scenario + " takes no --loss, got " + packetLossPercent);
            }
            if (leaderlessTargetIndex < 0 || leaderlessTargetIndex >= clusterSize) {
                throw new IllegalArgumentException("leaderlessTargetIndex "
                        + leaderlessTargetIndex + " is outside the cluster (size "
                        + clusterSize + ")");
            }
        }
    }

    private RemoteRunner() { }

    public static void run(Spec spec) throws Exception {
        Inventory inv = Inventory.parse(spec.inventoryFile());
        log.info("remote-run {} {} size={} on nodes {} (loadgen {})", spec.system(),
                spec.scenario(), spec.clusterSize(), inv.nodePrivateIps(), inv.loadgenPrivateIp());
        try (SshjExecutor ssh = new SshjExecutor(spec.sshUser(), inv.sshKey());
             RemoteSshProvider provider =
                     new RemoteSshProvider(ssh, inv.nodePrivateIps(), HEALTH_DEADLINE)) {
            if (spec.system() == SystemUnderTest.HOTSTUFF) {
                runHotStuff(spec, inv, ssh, provider);
            } else {
                runDriverSystem(spec, inv, ssh, provider);
            }
        }
    }

    // ---------------------------------------------------------------
    // The six driver-based systems: engine-measured, CSV-contracted.
    // ---------------------------------------------------------------

    private static void runDriverSystem(Spec spec, Inventory inv, SshExecutor ssh,
                                        RemoteSshProvider provider) throws Exception {
        List<NodeHandle> nodes = provider.start(spec.system(), spec.clusterSize());
        var cfg = new WorkloadEngine.Config(spec.durationSecs(), spec.warmupSecs(),
                spec.ratePerSec(), spec.window(), spec.valueSizeBytes(), spec.conflictRatio());
        var id = new CsvResultsWriter.RunIdentity(spec.system(), spec.scenario(),
                spec.clusterSize(), spec.conflictRatio(), spec.packetLossPercent(),
                spec.runId());
        boolean faultRun = spec.scenario() != Scenario.BASELINE;
        EventLog events = faultRun ? new EventLog(eventCapacity(spec)) : null;

        try (ConsensusDriver driver = driverFor(spec.system(), provider.clientEndpoints())) {
            var recorder = new LatencyRecorder();
            var engine = events == null
                    ? new WorkloadEngine(driver, cfg, recorder)
                    : new WorkloadEngine(driver, cfg, recorder, events);
            var injector = new SshFaultInjector(ssh, nodes, slowNodeSeconds(spec));
            AtomicReference<Exception> injectionFailure = new AtomicReference<>();
            Thread faultThread = faultRun
                    ? faultThread(spec, driver, injector, nodes, events, injectionFailure)
                    : null;

            Instant started = Instant.now();
            WorkloadEngine.Result result;
            // M5.3: the harness's own :9400 endpoint, live for exactly the
            // measured window. Opened per cell and closed with it, so the
            // next cell's registry cannot collide on the port.
            try (var selfMetrics = HarnessMetrics.start(engine, spec.window())) {
                if (faultThread != null) faultThread.start();
                result = engine.run();
            } finally {
                if (faultThread != null) {
                    faultThread.join(FAULT_JOIN_MILLIS);
                    if (faultThread.isAlive()) {
                        // F50c: the join's own timeout used to be DISCARDED, so
                        // a stalled injector produced a silent, unflagged
                        // "fault run" — the same void cell as a thrown
                        // injection, minus the exception that would have named
                        // it. compareAndSet so a real cause already recorded by
                        // the thread always wins over this generic one.
                        injectionFailure.compareAndSet(null, new IllegalStateException(
                                "fault thread still running " + FAULT_JOIN_MILLIS
                                        + " ms after the load ended — the injection did not"
                                        + " complete inside the measured run"));
                    }
                }
                injector.heal(); // always — a surviving netem/iptables rule poisons the next run
            }
            Instant ended = Instant.now();

            exportMetrics(inv, id.dir(spec.out()), started, ended);
            writeResultsAndCheck(spec.out(), id, result, cfg, "hetzner",
                    imageFor(spec.system()), started, ended);
            if (faultRun) {
                // Fault forensics (P4.5) — SUT nodes AND any auxiliary
                // containers. KAFKA_ZK colocates a ZooKeeper ensemble beside
                // each broker (D10), and omitting it left a ZAB fault run's
                // evidence missing precisely the coordination half the
                // F6 comparison is about (F58).
                collectSutLogs(ssh, nodes, id.dir(spec.out()));
                collectAuxLogs(ssh, provider.auxContainerHandles(), id.dir(spec.out()));
            }
            // The events audit is collected for EVERY run, not just fault
            // runs: gate 4 asks whether an UNEXPECTED restart happened, and a
            // baseline is exactly where an unexpected one would hide.
            collectDockerEvents(ssh, nodes, id.dir(spec.out()), started, ended);
            if (injectionFailure.get() != null) {
                // The run completed and its CSVs exist for forensics, but a
                // fault run whose fault never fired is NOT a fault result.
                throw new IllegalStateException(
                        "fault injection FAILED — this run is not a valid " + spec.scenario()
                                + " cell; see the cause", injectionFailure.get());
            }
            log.info("remote-run complete: {} committed after warmup, {} errors -> {}",
                    result.latencies().countAfterWarmup(), result.errors(), id.dir(spec.out()));
        }
    }

    /**
     * Mark semantics (F47, documented deliberately): the fault delay counts
     * from MEASUREMENT start (the thread waits for the engine to start the
     * event log — connect() time no longer shifts the fault into the run),
     * and the mark is stamped AFTER apply() returns, i.e. when the
     * injection COMPLETED. For single-command faults (kill) that is ~an SSH
     * round-trip after the fault bit; for multi-command faults (partition:
     * four iptables rules) the fault may start biting mid-apply, so the
     * reported failover_ms is a LOWER bound on fault-effect→recovery —
     * stated in methodology §4.3 rather than "fixed" with a fabricated
     * earlier mark, which would let a pre-fault commit fake a ~0 failover.
     */
    private static Thread faultThread(Spec spec, ConsensusDriver driver,
                                      SshFaultInjector injector, List<NodeHandle> nodes,
                                      EventLog events, AtomicReference<Exception> failure) {
        Thread t = new Thread(() -> {
            try {
                awaitEngineStart(events, ENGINE_START_WAIT);
                Thread.sleep(spec.faultAtSecs() * 1000L);
                int target = faultTargetIndex(spec.system(), driver,
                        spec.leaderlessTargetIndex());
                log.info("phase: fault inject — {} on node index {} ({})",
                        spec.scenario(), target, nodes.get(target).containerName());
                injector.apply(spec.scenario(), nodes, target, spec.packetLossPercent());
                long mark = events.faultInjectedNow();
                log.info("phase: fault marked at t={} ms", mark);
            } catch (Exception e) {
                failure.set(e);
                log.error("fault injection failed: {}", e.toString());
            }
        }, "bench-fault-injector");
        t.setDaemon(true);
        return t;
    }

    /**
     * Wait until the engine has started the event log, bounded (F50c). The
     * fault delay must count from MEASUREMENT start, not thread start (F47),
     * which means waiting — but the wait was UNBOUNDED: when {@code
     * engine.run()} fails inside {@code driver.connect()}, {@code start()}
     * never comes, so the loop spun for the life of the campaign JVM, leaking
     * one daemon thread per failed fault cell and costing a 30 s join timeout
     * each time. Expiring throws rather than returning, because returning
     * would let the thread inject against a run that is not measuring.
     *
     * <p>Package-private so the bound itself is testable without a 2-minute
     * test; production always passes {@link #ENGINE_START_WAIT}.
     */
    static void awaitEngineStart(EventLog events, Duration bound) throws InterruptedException {
        long deadline = System.nanoTime() + bound.toNanos();
        while (!events.isStarted()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "engine never started the event log within " + bound
                                + " — connect() almost certainly failed; refusing to spin");
            }
            Thread.sleep(50);
        }
    }

    /**
     * F13/F19 targeting: leader-sensitive faults hit the leader DETECTED at
     * injection time. EPaxos (leaderless by design) and CometBFT (proposer
     * rotates every height) have no stable leader to detect — replica 0 is
     * the DOCUMENTED deterministic choice there, equivalent by symmetry,
     * never a guess. Every other system must name its leader or the
     * injection fails loudly (an election in progress is not a license to
     * kill an arbitrary node — the v6 class).
     */
    static int faultTargetIndex(SystemUnderTest system, ConsensusDriver driver) throws Exception {
        return faultTargetIndex(system, driver, 0);
    }

    /** @param leaderlessTargetIndex used ONLY where no leader exists to detect
     *  (D15.5); every other system must still name its DETECTED leader. */
    static int faultTargetIndex(SystemUnderTest system, ConsensusDriver driver,
                                int leaderlessTargetIndex) throws Exception {
        if (system == SystemUnderTest.EPAXOS || system == SystemUnderTest.TENDERMINT) {
            return leaderlessTargetIndex;
        }
        return driver.currentLeaderIndex().orElseThrow(() -> new IllegalStateException(
                system + " reports no leader at injection time (election in progress?)"
                        + " — refusing to target a guess"));
    }

    static ConsensusDriver driverFor(SystemUnderTest system, List<String> endpoints) {
        return switch (system) {
            case ETCD -> new EtcdDriver(endpoints);
            case KRAFT, KAFKA_ZK -> new KafkaDriver(system, endpoints);
            case TENDERMINT -> new CometBftDriver(endpoints);
            case PAXOS, EPAXOS -> new PaxiDriver(system, endpoints);
            case HOTSTUFF -> throw new IllegalArgumentException(
                    "HOTSTUFF has no ConsensusDriver — its own client is the load generator");
        };
    }

    static String imageFor(SystemUnderTest system) {
        return switch (system) {
            case ETCD -> LocalDockerProvider.ETCD_IMAGE;
            case KRAFT, KAFKA_ZK -> LocalDockerProvider.KAFKA_IMAGE;
            case TENDERMINT -> LocalDockerProvider.COMETBFT_IMAGE;
            case PAXOS, EPAXOS -> LocalDockerProvider.PAXI_IMAGE;
            case HOTSTUFF -> LocalDockerProvider.HOTSTUFF_IMAGE;
        };
    }

    /**
     * Event capacity: every completion is one long. Rate-bound runs size from
     * the schedule (2x slack for retries/drain); saturation runs have no rate
     * to derive from and take the fixed roof.
     *
     * <p>That roof is load-bearing, so it is pinned by test rather than left
     * as a bare constant (F70): at the campaign shape the derived size hits
     * 4,000,000 at ~4,167 ops/s, and a run sustaining more than
     * {@code 4,000,000 / faultAtSecs} commits fills the buffer BEFORE the
     * fault mark — after which {@link EventLog#failoverMillis()} finds no
     * qualifying commit and the run reads as "the fault fired and nothing
     * ever recovered", which is also what a genuine wedge looks like.
     * Overflow is counted, never silent (EventLog contract), and
     * {@link gr.thesis.bench.results.CsvResultsWriter} refuses to call such a
     * run complete. Package-private so the sizing contract is testable.
     */
    static int eventCapacity(Spec spec) {
        long expected = spec.ratePerSec() > 0
                ? spec.ratePerSec() * spec.durationSecs() * 2 + spec.window()
                : 4_000_000;
        return (int) Math.min(4_000_000, Math.max(100_000, expected));
    }

    /**
     * M5.4 — archive this run's Prometheus window into {@code metrics/}
     * BEFORE the validity check, because the metric gates read exactly those
     * files; exporting afterwards would leave every one of them SKIPping on
     * the run that just produced the data.
     *
     * <p>No obs VM (the canary shape) means no export, and therefore no
     * {@code metrics/} dir — which the checker reports as a loud SKIP rather
     * than a pass. That is the honest degradation: the gates say they could
     * not run, instead of a run failing over infrastructure it never had.
     */
    private static void exportMetrics(Inventory inv, Path runDir, Instant started, Instant ended) {
        if (inv.obsPrivateIp().isEmpty()) {
            log.info("no PRIVATE_OBS in the inventory — skipping the metrics export;"
                    + " the metric validity gates will SKIP, not pass");
            return;
        }
        String base = "http://" + inv.obsPrivateIp().get() + ":9090";
        try {
            var queries = PrometheusExporter.parseQueries(EXPORT_QUERIES);
            PrometheusExporter.export(runDir, queries, started, ended,
                    PrometheusExporter.http(base));
        } catch (Exception e) {
            // The measurement is already made; losing its explanation layer
            // must not lose the run. The gates will fail closed on the empty
            // or missing series, which is the correct outcome.
            log.error("metrics export from {} failed — the run's own numbers are safe, its"
                    + " Prometheus window is not: {}", base, e.toString());
        }
    }

    /** Shipped beside the jar on the loadgen (collect_block.sh stages the
     *  repo there); the runbook §5 set the exporter executes verbatim. */
    static final Path EXPORT_QUERIES = Path.of("observability/export_queries.txt");

    /**
     * Write the run's numbers, then JUDGE them (S3.2). Until this existed,
     * {@link ValidityChecker} was a library that nothing called — neither
     * this runner nor {@link MatrixRunner} invoked {@code check()}, so no
     * campaign run had ever produced a {@code validity.json}. Ten gates that
     * never run are not gates, and methodology §4's "a run is valid only
     * if…" was unenforced for the entire campaign path.
     *
     * <p>The verdict is a judgement OF the measurement, never part of it, so
     * a checker failure must not cost the run: the CSVs and manifest are
     * already on disk when the check runs, and a thrown check is logged and
     * swallowed. Losing a measured cell because we could not grade it would
     * be the worst available trade — this is {@code checkTree}'s
     * record-and-continue rule applied one level down. A cell that could not
     * be graded simply has no validity.json, which analyse.py treats as
     * ungraded rather than as valid.
     */
    static void writeResultsAndCheck(Path out, CsvResultsWriter.RunIdentity id,
                                     WorkloadEngine.Result result, WorkloadEngine.Config cfg,
                                     String environment, String imageRef,
                                     Instant started, Instant ended) throws java.io.IOException {
        new CsvResultsWriter().write(out, id, result, cfg, environment, imageRef,
                started, ended);
        Path dir = id.dir(out);
        try {
            ValidityChecker.Report report = ValidityChecker.check(dir);
            log.info("validity {}: {} ({} pass, {} fail, {} skip)", dir,
                    report.valid() ? "VALID" : "INVALID",
                    report.gates().stream().filter(g -> g.state() == ValidityChecker.State.PASS).count(),
                    report.gates().stream().filter(g -> g.state() == ValidityChecker.State.FAIL).count(),
                    report.gates().stream().filter(g -> g.state() == ValidityChecker.State.SKIP).count());
            report.gates().stream()
                    .filter(g -> g.state() == ValidityChecker.State.FAIL)
                    .forEach(g -> log.warn("validity FAIL {} — {}", g.gate(), g.detail()));
        } catch (Exception e) {
            log.error("could not evaluate validity for {} — the MEASUREMENT is safe on disk,"
                    + " the verdict is not: {}", dir, e.toString());
        }
    }

    /**
     * How long slow_node's CPU load runs (D15.3): the rest of the measured
     * run after the fault, plus slack for the drain. This makes slow_node's
     * fault duration match every other scenario's — the others persist until
     * heal(), which the runner calls once the load has ended — so the F5
     * recovery figure compares scenarios that were faulted for the same
     * share of their window. Package-private: pinned by test.
     */
    static int slowNodeSeconds(Spec spec) {
        return Math.max(1, spec.durationSecs() - spec.faultAtSecs()) + SLOW_NODE_SLACK_SECS;
    }

    /** Covers the engine's drain (bounded by the 5 s per-op contract) plus
     *  teardown, so the load does not lift while the last ops are landing. */
    static final int SLOW_NODE_SLACK_SECS = 30;

    /** Fault forensics (P4.5): full SUT logs beside the CSVs, collected via
     *  the chunked path so size can never truncate them. */
    private static void collectSutLogs(SshExecutor ssh, List<NodeHandle> nodes, Path runDir)
            throws Exception {
        Path logs = runDir.resolve("logs");
        Files.createDirectories(logs);
        for (NodeHandle n : nodes) {
            try {
                Files.writeString(logs.resolve(n.containerName() + ".log"),
                        RemoteLogs.dockerLogs(ssh, n.host(), n.containerName()));
            } catch (Exception e) {
                // A killed container still has logs; a REMOVED one does not.
                // Say so and keep collecting the rest — forensics is
                // best-effort, the measurement is already on disk.
                log.warn("could not collect logs of {} on {}: {}",
                        n.containerName(), n.host(), e.toString());
            }
        }
    }

    /**
     * P4.5's other half — the docker-events audit per node, into
     * {@code events/<container-host>.txt}. Best-effort like the logs: the
     * measurement is already on disk, and an unreachable node costs the
     * audit, never the run.
     */
    private static void collectDockerEvents(SshExecutor ssh, List<NodeHandle> nodes, Path runDir,
                                            Instant started, Instant ended) {
        try {
            Path events = runDir.resolve("events");
            Files.createDirectories(events);
            for (NodeHandle n : nodes) {
                try {
                    Files.writeString(events.resolve(n.host() + ".txt"),
                            RemoteLogs.dockerEvents(ssh, n.host(), started, ended));
                } catch (Exception e) {
                    log.warn("could not collect docker events from {}: {}", n.host(), e.toString());
                }
            }
        } catch (Exception e) {
            log.warn("could not create the events audit dir for {}: {}", runDir, e.toString());
        }
    }

    /** Auxiliary containers (KAFKA_ZK's colocated ZK ensemble) alongside the
     *  SUT logs — same best-effort rule: a missing log is reported and the
     *  rest still collected, because the measurement is already on disk. */
    private static void collectAuxLogs(SshExecutor ssh, List<String[]> aux, Path runDir)
            throws Exception {
        if (aux.isEmpty()) {
            return;
        }
        Path logs = runDir.resolve("logs");
        Files.createDirectories(logs);
        for (String[] a : aux) {
            try {
                Files.writeString(logs.resolve(a[1] + ".log"),
                        RemoteLogs.dockerLogs(ssh, a[0], a[1]));
            } catch (Exception e) {
                log.warn("could not collect logs of aux container {} on {}: {}",
                        a[1], a[0], e.toString());
            }
        }
    }

    // ---------------------------------------------------------------
    // HotStuff: the upstream client IS the load generator (boundary).
    // ---------------------------------------------------------------

    static final String HS_CLIENT_CONTAINER = "thesis-hs-client";

    /** The exact upstream-client invocation (CLI contract probed 2026-07-17):
     *  target = node0's transactions address; --nodes = every address, so
     *  the client blocks until the whole committee accepts connections (the
     *  cluster-up gate); --timeout in ms. Package-private: pinned by test. */
    static String clientRunCommand(List<String> endpoints, long rate, int valSize) {
        return "docker run -d --name " + HS_CLIENT_CONTAINER + " --network host "
                + LocalDockerProvider.HOTSTUFF_IMAGE
                + " client " + endpoints.get(0)
                + " --timeout 5000 --size " + valSize + " --rate " + rate
                + " --nodes " + String.join(" ", endpoints);
    }

    private static void runHotStuff(Spec spec, Inventory inv, SshExecutor ssh,
                                    RemoteSshProvider provider) throws Exception {
        if (spec.scenario() != Scenario.BASELINE) {
            throw new UnsupportedOperationException(
                    "HOTSTUFF remote-run serves BASELINE only for now — fault scenarios are"
                            + " preregistered in PENDING_TASKS (target semantics + faults count"
                            + " in the SUMMARY need a decision, not a default)");
        }
        if (spec.ratePerSec() <= 0) {
            throw new IllegalArgumentException(
                    "HOTSTUFF needs an explicit --rate: its client is fixed-rate"
                            + " (no saturation mode) — sweep rates per the runbook");
        }
        String loadgen = inv.loadgenPrivateIp();
        List<NodeHandle> nodes = provider.start(spec.system(), spec.clusterSize());
        List<String> endpoints = provider.clientEndpoints();

        // Pre-clean OUR container on the loadgen (the provider sweeps only
        // consensus nodes); only "No such container" is a benign failure.
        SshExecutor.ExecResult rm = ssh.exec(loadgen, SSH_PORT,
                "docker rm -f " + HS_CLIENT_CONTAINER);
        if (rm.exitCode() != 0 && !rm.stderr().contains("No such container")) {
            throw new IllegalStateException("could not pre-clean " + HS_CLIENT_CONTAINER
                    + " on " + loadgen + ": " + rm.stderr());
        }

        Instant started = Instant.now();
        log.info("phase: hotstuff client start — {} tx/s for {}s", spec.ratePerSec(),
                spec.durationSecs());
        ssh.execOrThrow(loadgen, SSH_PORT,
                clientRunCommand(endpoints, spec.ratePerSec(), spec.valueSizeBytes()));
        try {
            Thread.sleep(spec.durationSecs() * 1000L);
        } finally {
            // Stop load BEFORE reading logs: a log snapshot taken mid-load
            // would undercount exactly the last batches.
            ssh.exec(loadgen, SSH_PORT, "docker stop " + HS_CLIENT_CONTAINER);
        }
        String clientLog = RemoteLogs.dockerLogs(ssh, loadgen, HS_CLIENT_CONTAINER);
        ssh.execOrThrow(loadgen, SSH_PORT, "docker rm -f " + HS_CLIENT_CONTAINER);
        // Node logs ordered node0-first: node0 is the client's target, the
        // only node that assembles its sample batches (the analyzer's zip
        // pairing contract).
        List<String> nodeLogs = new java.util.ArrayList<>(nodes.size());
        for (NodeHandle n : nodes) {
            nodeLogs.add(RemoteLogs.dockerLogs(ssh, n.host(), n.containerName()));
        }
        Instant ended = Instant.now();

        // Discard warmup like every other system (NEXT-4b): the analyzer
        // applies logs.py's formulas to the post-warmup window. The raw logs
        // are saved below, so the full-run number stays recomputable.
        String summary = HotStuffLogAnalyzer.summarize(
                List.of(clientLog), nodeLogs, 0, spec.warmupSecs());
        writeHotStuffResults(spec, summary, clientLog, nodeLogs, nodes, started, ended);
    }

    /**
     * HotStuff's honest output contract: the SUMMARY + the raw logs that
     * produced it (its logs ARE its metrics — no per-second series, no
     * histogram; every figure that uses these numbers carries that caveat,
     * methodology §2). The manifest mirrors CsvResultsWriter's fields where
     * they exist and stays silent where HotStuff cannot honestly fill them.
     */
    private static void writeHotStuffResults(Spec spec, String summary, String clientLog,
                                             List<String> nodeLogs, List<NodeHandle> nodes,
                                             Instant started, Instant ended) throws Exception {
        var id = new CsvResultsWriter.RunIdentity(spec.system(), spec.scenario(),
                spec.clusterSize(), spec.conflictRatio(), spec.packetLossPercent(),
                spec.runId());
        Path dir = id.dir(spec.out());
        Files.createDirectories(dir.resolve("logs"));
        Files.writeString(dir.resolve("summary.txt"), summary);
        Files.writeString(dir.resolve("logs").resolve("client.log"), clientLog);
        for (int i = 0; i < nodeLogs.size(); i++) {
            Files.writeString(dir.resolve("logs").resolve(nodes.get(i).containerName() + ".log"),
                    nodeLogs.get(i));
        }
        String manifest = """
                {
                  "system": "hotstuff",
                  "scenario": "%s",
                  "cluster_size": %d,
                  "run_id": "%s",
                  "environment": "hetzner",
                  "image": "%s",
                  "started_at": "%s",
                  "ended_at": "%s",
                  "duration_secs": %d,
                  "warmup_secs": %d,
                  "rate_ops_s": %d,
                  "value_size_bytes": %d,
                  "metrics_source": "summary.txt (logs.py-port over the post-warmup window, NEXT-4b; logs/ are the raw evidence — recompute full-run from them if needed)",
                  "harness_version": "%s",
                  "config_hash": "%s",
                  "status": "complete",
                  "harness": "consensus-bench-java"
                }
                """.formatted(spec.scenario().name().toLowerCase(), spec.clusterSize(),
                spec.runId(), LocalDockerProvider.HOTSTUFF_IMAGE, started, ended,
                spec.durationSecs(), spec.warmupSecs(), spec.ratePerSec(), spec.valueSizeBytes(),
                // F58: HotStuff cells were the ONLY ones with no version and no
                // config_hash, so they alone could not be shown individually
                // reproducible (methodology 1). status stays "complete", which is
                // safe TODAY only because runHotStuff refuses every non-BASELINE
                // scenario above -- it becomes load-bearing at NEXT-4 and must be
                // revisited there rather than inherited.
                CsvResultsWriter.harnessVersion(),
                CsvResultsWriter.configHash(id, new WorkloadEngine.Config(
                        spec.durationSecs(), spec.warmupSecs(), spec.ratePerSec(),
                        spec.window(), spec.valueSizeBytes(), spec.conflictRatio()),
                        LocalDockerProvider.HOTSTUFF_IMAGE));
        Files.writeString(dir.resolve("manifest.json"), manifest);
        // S3.2 applies here too: HotStuff's gates are mostly honest SKIPs
        // (no throughput.csv BY DESIGN — F42), but "mostly SKIP" is a
        // verdict the run should carry rather than one a reader has to
        // reconstruct.
        try {
            ValidityChecker.check(dir);
        } catch (Exception e) {
            log.error("could not evaluate validity for {} — the SUMMARY and logs are safe"
                    + " on disk, the verdict is not: {}", dir, e.toString());
        }
        log.info("hotstuff results -> {}", dir);
    }
}
