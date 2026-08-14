package gr.thesis.bench.campaign;

import gr.thesis.bench.core.EventLog;
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
import gr.thesis.bench.topology.ClusterProvider.NodeHandle;
import gr.thesis.bench.topology.LocalDockerProvider;
import gr.thesis.bench.topology.RemoteSshProvider;
import gr.thesis.bench.topology.SshExecutor;
import gr.thesis.bench.topology.SshFaultInjector;
import gr.thesis.bench.topology.SshjExecutor;

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
    public record Spec(SystemUnderTest system, Scenario scenario, int clusterSize,
                       long ratePerSec, int durationSecs, int warmupSecs, int window,
                       int valueSizeBytes, double conflictRatio, int faultAtSecs,
                       int packetLossPercent, Path out, String runId,
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
                spec.clusterSize(), spec.conflictRatio(), spec.runId());
        boolean faultRun = spec.scenario() != Scenario.BASELINE;
        EventLog events = faultRun ? new EventLog(eventCapacity(spec)) : null;

        try (ConsensusDriver driver = driverFor(spec.system(), provider.clientEndpoints())) {
            var recorder = new LatencyRecorder();
            var engine = events == null
                    ? new WorkloadEngine(driver, cfg, recorder)
                    : new WorkloadEngine(driver, cfg, recorder, events);
            var injector = new SshFaultInjector(ssh, nodes);
            AtomicReference<Exception> injectionFailure = new AtomicReference<>();
            Thread faultThread = faultRun
                    ? faultThread(spec, driver, injector, nodes, events, injectionFailure)
                    : null;

            Instant started = Instant.now();
            WorkloadEngine.Result result;
            try {
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

            new CsvResultsWriter().write(spec.out(), id, result, cfg, "hetzner",
                    imageFor(spec.system()), started, ended);
            if (faultRun) {
                collectSutLogs(ssh, nodes, id.dir(spec.out())); // fault forensics (P4.5)
            }
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
                int target = faultTargetIndex(spec.system(), driver);
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
        if (system == SystemUnderTest.EPAXOS || system == SystemUnderTest.TENDERMINT) {
            return 0;
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

    /** Event capacity: every completion is one long. Rate-bound runs size
     *  from the schedule (2x slack for retries/drain); saturation runs get
     *  a generous fixed roof. Overflow drops loudly (EventLog contract). */
    private static int eventCapacity(Spec spec) {
        long expected = spec.ratePerSec() > 0
                ? spec.ratePerSec() * spec.durationSecs() * 2 + spec.window()
                : 4_000_000;
        return (int) Math.min(4_000_000, Math.max(100_000, expected));
    }

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
                spec.clusterSize(), spec.conflictRatio(), spec.runId());
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
                  "status": "complete",
                  "harness": "consensus-bench-java"
                }
                """.formatted(spec.scenario().name().toLowerCase(), spec.clusterSize(),
                spec.runId(), LocalDockerProvider.HOTSTUFF_IMAGE, started, ended,
                spec.durationSecs(), spec.warmupSecs(), spec.ratePerSec(), spec.valueSizeBytes());
        Files.writeString(dir.resolve("manifest.json"), manifest);
        log.info("hotstuff results -> {}", dir);
    }
}
