package gr.thesis.bench;

import gr.thesis.bench.core.LatencyRecorder;
import gr.thesis.bench.core.Scenario;
import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.core.WorkloadEngine;
import gr.thesis.bench.driver.ConsensusDriver;
import gr.thesis.bench.driver.EtcdDriver;
import gr.thesis.bench.driver.EtcdHttpDriver;
import gr.thesis.bench.results.CsvResultsWriter;
import gr.thesis.bench.topology.LocalDockerProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal CLI for smoke runs. M1.3 replaces this with picocli.
 *
 * Three commands:
 *   (default)   run against an ALREADY-RUNNING etcd endpoint (M0 behavior)
 *   local-run   clean -> deploy fresh Dockerized etcd -> run -> teardown,
 *               one command, teardown guaranteed (P0.3)
 *   remote-run  ONE campaign cell against the real VMs (M3.3 session mode):
 *               inventory-typed topology, golden-tested provider/injector,
 *               per-system driver, environment=hetzner results. Runs ON the
 *               loadgen VM. See docs/PER_ALGORITHM_TEST_GUIDE.md.
 *   campaign-run  ONE SYSTEM BLOCK (M3.3-full): every cell of
 *               scenarios x rates x conflicts x reps, seeded shuffle,
 *               manifest-resume, failure-continues. --dry-run previews the
 *               ordered cell list — the operator's preflight.
 */
public final class Main {

    // Deliberately NO static Logger here: slf4j-simple freezes its
    // configuration on the first LoggerFactory call, and the -v decision
    // must be made before that happens (see main()).

    public static void main(String[] args) throws Exception {
        boolean local = args.length > 0 && "local-run".equals(args[0]);
        boolean remote = args.length > 0 && "remote-run".equals(args[0]);
        boolean campaign = args.length > 0 && "campaign-run".equals(args[0]);
        Map<String, String> a = parse(local || remote || campaign
                ? Arrays.copyOfRange(args, 1, args.length) : args);

        // -v/--verbose raises the level to DEBUG: every phase boundary and
        // the per-second reporter become visible. Default INFO stays quiet —
        // including the container libraries, whose INFO chatter would drown
        // the four summary lines.
        if (a.containsKey("verbose")) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
            System.setProperty("org.slf4j.simpleLogger.log.org.testcontainers", "info");
            System.setProperty("org.slf4j.simpleLogger.log.tc", "info");
            System.setProperty("org.slf4j.simpleLogger.log.com.github.dockerjava", "info");
        } else {
            System.setProperty("org.slf4j.simpleLogger.log.org.testcontainers", "warn");
            System.setProperty("org.slf4j.simpleLogger.log.tc", "warn");
            System.setProperty("org.slf4j.simpleLogger.log.com.github.dockerjava", "warn");
        }
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS");
        Logger log = LoggerFactory.getLogger(Main.class);

        if (campaign) {
            campaignRun(a);
        } else if (remote) {
            remoteRun(a);
        } else if (local) {
            localRun(a, log);
        } else {
            endpointRun(a, log);
        }
    }

    /** M3.3-full: one SYSTEM BLOCK — the campaign's execution unit. Sweep
     *  rates are operator inputs (25/50/75% of the measured saturation from
     *  a prior sat block — the runbook's procedure); failover distributions
     *  are their own block (--scenarios leader_kill --reps 30). */
    private static void campaignRun(Map<String, String> a) throws Exception {
        requireKnownKeys(a, java.util.Set.of("inventory", "system", "size", "scenarios",
                "rates", "conflicts", "reps", "seed", "out", "ssh-user", "dry-run", "verbose"));
        String systemArg = a.get("system");
        if (systemArg == null) {
            throw new IllegalArgumentException("--system is required (one block per system)");
        }
        var system = SystemUnderTest.valueOf(systemArg.toUpperCase(java.util.Locale.ROOT));
        var scenarios = Arrays.stream(a.getOrDefault("scenarios", "baseline").split(","))
                .map(s -> Scenario.valueOf(s.strip().toUpperCase(java.util.Locale.ROOT)))
                .toList();
        var rates = Arrays.stream(a.getOrDefault("rates", "0").split(","))
                .map(String::strip).map(Long::parseLong).toList();
        var conflicts = Arrays.stream(a.getOrDefault("conflicts", "0").split(","))
                .map(String::strip).map(Double::parseDouble).toList();
        var block = gr.thesis.bench.campaign.MatrixRunner.block(
                system,
                Integer.parseInt(a.getOrDefault("size",
                        String.valueOf(system.defaultClusterSize()))),
                scenarios, rates, conflicts,
                Integer.parseInt(a.getOrDefault("reps", "5")),
                Long.parseLong(a.getOrDefault("seed", "20260718")),
                Path.of(a.getOrDefault("out", "results")),
                Path.of(a.getOrDefault("inventory", "deploy/inventory.env")),
                a.getOrDefault("ssh-user", "root"));
        gr.thesis.bench.campaign.MatrixRunner.run(block,
                gr.thesis.bench.campaign.RemoteRunner::run, a.containsKey("dry-run"));
    }

    /** M3.3 session mode: one campaign cell on the real VMs. Everything
     *  here is arg plumbing — the orchestration (and its tests) live in
     *  campaign.RemoteRunner. Campaign defaults: valsize 1024 (the
     *  cross-system contract, F21), duration 480 = 180 warmup + 300
     *  measurement (runbook §3), fault at warmup+60. */
    private static void remoteRun(Map<String, String> a) throws Exception {
        requireKnownKeys(a, java.util.Set.of("inventory", "system", "scenario", "size",
                "rate", "duration", "warmup", "window", "valsize", "out", "run",
                "conflict", "fault-at", "loss", "ssh-user", "verbose"));
        String systemArg = a.get("system");
        if (systemArg == null) {
            throw new IllegalArgumentException("--system is required (one of: "
                    + Arrays.toString(gr.thesis.bench.core.SystemUnderTest.values()) + ")");
        }
        var system = SystemUnderTest.valueOf(systemArg.toUpperCase(java.util.Locale.ROOT));
        var scenario = Scenario.valueOf(
                a.getOrDefault("scenario", "baseline").toUpperCase(java.util.Locale.ROOT));
        int duration = Integer.parseInt(a.getOrDefault("duration", "480"));
        int warmup   = Integer.parseInt(a.getOrDefault("warmup", "180"));
        String runId = a.getOrDefault("run", "r001");
        if (!runId.matches("[a-z0-9]+")) {
            // F21: the manifest JSON does not escape runId — constrain it
            // here rather than emit broken JSON.
            throw new IllegalArgumentException("--run must be [a-z0-9]+, got: " + runId);
        }
        requireDurationExceedsWarmup(duration, warmup);
        var spec = new gr.thesis.bench.campaign.RemoteRunner.Spec(
                system, scenario,
                Integer.parseInt(a.getOrDefault("size",
                        String.valueOf(system.defaultClusterSize()))),
                Long.parseLong(a.getOrDefault("rate", "0")),
                duration, warmup,
                Integer.parseInt(a.getOrDefault("window", "200")),
                Integer.parseInt(a.getOrDefault("valsize", "1024")),
                Double.parseDouble(a.getOrDefault("conflict", "0")),
                Integer.parseInt(a.getOrDefault("fault-at", String.valueOf(warmup + 60))),
                Integer.parseInt(a.getOrDefault("loss", "30")),
                Path.of(a.getOrDefault("out", "results")),
                runId,
                Path.of(a.getOrDefault("inventory", "deploy/inventory.env")),
                a.getOrDefault("ssh-user", "root"));
        gr.thesis.bench.campaign.RemoteRunner.run(spec);
    }

    /** M0 behavior: measure an etcd that someone else already runs. */
    private static void endpointRun(Map<String, String> a, Logger log) throws Exception {
        requireKnownKeys(a, java.util.Set.of("endpoint", "duration", "warmup", "rate",
                "window", "valsize", "out", "run", "conflict", "verbose"));
        String endpoint = a.getOrDefault("endpoint", "http://127.0.0.1:2379");
        int duration    = Integer.parseInt(a.getOrDefault("duration", "20"));
        int warmup      = Integer.parseInt(a.getOrDefault("warmup", "5"));
        long rate       = Long.parseLong(a.getOrDefault("rate", "0")); // 0 = saturation
        int window      = Integer.parseInt(a.getOrDefault("window", "64"));
        int valSize     = Integer.parseInt(a.getOrDefault("valsize", "256"));
        Path out        = Path.of(a.getOrDefault("out", "results"));
        String runId    = a.getOrDefault("run", "run001");
        double conflict = Double.parseDouble(a.getOrDefault("conflict", "0"));
        requireDurationExceedsWarmup(duration, warmup);

        log.info("run={} system=etcd mode={} duration={}s warmup={}s window={} valsize={} conflict={}",
                runId, rate > 0 ? rate + " ops/s open-loop" : "saturation",
                duration, warmup, window, valSize, conflict);
        var cfg = new WorkloadEngine.Config(duration, warmup, rate, window, valSize, conflict);
        var id = new CsvResultsWriter.RunIdentity(SystemUnderTest.ETCD, Scenario.BASELINE, 1, conflict, runId);
        try (ConsensusDriver driver = new EtcdHttpDriver(endpoint)) {
            // image=null: we did not start this cluster, so we do not claim
            // to know what it runs (honest unknown, not a guess).
            runAndReport(driver, cfg, out, id, null, log);
        }
    }

    /** P0.3: clean -> deploy fresh Dockerized etcd -> run -> teardown. */
    private static void localRun(Map<String, String> a, Logger log) throws Exception {
        requireKnownKeys(a, java.util.Set.of("size", "duration", "warmup", "rate",
                "window", "valsize", "out", "run", "conflict", "verbose"));
        int size     = Integer.parseInt(a.getOrDefault("size", "1"));
        int duration = Integer.parseInt(a.getOrDefault("duration", "10"));
        int warmup   = Integer.parseInt(a.getOrDefault("warmup", "2"));
        long rate    = Long.parseLong(a.getOrDefault("rate", "200"));
        int window   = Integer.parseInt(a.getOrDefault("window", "64"));
        int valSize  = Integer.parseInt(a.getOrDefault("valsize", "256"));
        Path out     = Path.of(a.getOrDefault("out", "results-local"));
        String runId = a.getOrDefault("run", "local001");
        double conflict = Double.parseDouble(a.getOrDefault("conflict", "0"));
        if (size != 1 && size != 3) {
            throw new IllegalArgumentException("--size must be 1 or 3 (etcd quorum shapes), got " + size);
        }
        requireDurationExceedsWarmup(duration, warmup);

        // Idempotent pre-clean: a crashed earlier run must not block this one.
        int removed = LocalDockerProvider.removeLeftovers();
        if (removed > 0) {
            log.info("pre-clean: removed {} leftover thesis-* container(s)", removed);
        }

        log.info("local-run run={} system=etcd size={} mode={} duration={}s warmup={}s window={} valsize={} conflict={}",
                runId, size, rate > 0 ? rate + " ops/s open-loop" : "saturation",
                duration, warmup, window, valSize, conflict);
        var cfg = new WorkloadEngine.Config(duration, warmup, rate, window, valSize, conflict);
        var id = new CsvResultsWriter.RunIdentity(SystemUnderTest.ETCD, Scenario.BASELINE, size, conflict, runId);
        // try-with-resources IS the guaranteed teardown: provider.close() ->
        // stop() runs on success, failure, or engine exception alike.
        try (LocalDockerProvider provider = new LocalDockerProvider()) {
            provider.start(SystemUnderTest.ETCD, size);
            // Production driver (jetcd/gRPC, P2.1). EtcdHttpDriver remains
            // the endpoint-run fallback and G3's independent cross-check.
            try (ConsensusDriver driver = new EtcdDriver(provider.clientEndpoints())) {
                runAndReport(driver, cfg, out, id, LocalDockerProvider.ETCD_IMAGE, log);
            }
        }
        log.info("teardown complete — zero thesis-* containers remain");
    }

    /** Shared measure-then-persist path for both commands. Everything Main
     *  runs is environment=local — the "hetzner" tag belongs exclusively to
     *  the campaign runner on the loadgen VM (M3.3/M6). */
    private static void runAndReport(ConsensusDriver driver, WorkloadEngine.Config cfg,
                                     Path out, CsvResultsWriter.RunIdentity id,
                                     String imageRef, Logger log) throws Exception {
        var recorder = new LatencyRecorder();
        var engine = new WorkloadEngine(driver, cfg, recorder);

        Instant started = Instant.now();
        WorkloadEngine.Result r = engine.run();
        Instant ended = Instant.now();

        new CsvResultsWriter().write(out, id, r, cfg, "local", imageRef, started, ended);

        long ops = r.latencies().countAfterWarmup();
        log.info("committed(after warmup)={} errors={} throughput={} ops/s",
                ops, r.errors(),
                String.format("%.1f", ops / (double) (cfg.durationSecs() - cfg.warmupSecs())));
        log.info("latency us: p50={} p95={} p99={} p99.9={} max={}",
                r.latencies().percentileMicros(50), r.latencies().percentileMicros(95),
                r.latencies().percentileMicros(99), r.latencies().percentileMicros(99.9),
                r.latencies().percentileMicros(100));
        log.info("results -> {}", id.dir(out));
    }

    /** Fail closed on a typo'd argument name (F32): `--ratee 300` parsing
     *  fine and the run proceeding at the DEFAULT rate is a fail-open in an
     *  otherwise fail-closed CLI — the manifest would record the truth, but
     *  only after a wasted (or worse, trusted) run. */
    static void requireKnownKeys(Map<String, String> args, java.util.Set<String> known) {
        for (String k : args.keySet()) {
            if (!known.contains(k)) {
                throw new IllegalArgumentException(
                        "unknown argument --" + k + " (valid: " + new java.util.TreeSet<>(known) + ")");
            }
        }
    }

    /** Fail closed before any work: an empty measurement window would make
     *  every derived number (throughput = ops/(duration-warmup)) meaningless. */
    static void requireDurationExceedsWarmup(int duration, int warmup) {
        if (duration <= warmup) {
            throw new IllegalArgumentException(
                    "duration (" + duration + "s) must exceed warmup (" + warmup + "s)");
        }
    }

    static Map<String, String> parse(String[] args) { // package-private for the unit test
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-v") || args[i].equals("--verbose")) {
                m.put("verbose", "true");           // boolean flag: consumes no value
            } else if (args[i].equals("--dry-run")) {
                m.put("dry-run", "true");           // boolean flag (campaign-run preflight)
            } else if (i + 1 < args.length) {
                m.put(args[i].replaceFirst("^--", ""), args[++i]);
            } else {
                // Fail closed: a key with no value would silently vanish otherwise.
                throw new IllegalArgumentException("missing value for argument: " + args[i]);
            }
        }
        return m;
    }
}
