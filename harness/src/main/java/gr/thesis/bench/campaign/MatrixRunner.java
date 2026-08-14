package gr.thesis.bench.campaign;

import gr.thesis.bench.core.Scenario;
import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.results.CsvResultsWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * M3.3-full: the matrix executor for ONE system block — the campaign's
 * execution unit (EXECUTION_AND_COST_MODEL: one system under test at a
 * time, serial blocks on shared per-phase infra; a "phase" is just the
 * operator running these blocks in sequence). Generates every cell spec
 * (scenarios x rates x conflicts x n repetitions), shuffles WITHIN the
 * block with a SEEDED Random (methodology §1 randomization — the seed is
 * logged so the order itself is reproducible), skips cells whose
 * manifest already says {@code "status": "complete"} (resume = rerun the
 * same command; path identity IS run identity), and on a cell failure
 * records it to {@code campaign-log.jsonl} and CONTINUES — an invalid
 * cell is rerun by hand, never silently hidden and never allowed to
 * abort the night's remaining cells.
 *
 * Run-id convention (from the M0 evidence tree, where rate lives in the
 * runId because it is NOT a path segment): {@code rate<R>r<NN>} for
 * fixed-rate cells, {@code satr<NN>} for saturation cells — [a-z0-9]+
 * as the open F21 requires.
 *
 * Sweep rates are INPUTS here, not derived: the runbook's 25/50/75%
 * points come from the measured saturation of a prior sat block — the
 * operator (or a later wrapper) feeds them in. Failover distributions:
 * pass scenarios=[LEADER_KILL] reps>=30 as its own block (runbook §3).
 */
public final class MatrixRunner {

    private static final Logger log = LoggerFactory.getLogger(MatrixRunner.class);

    /**
     * One system block. Durations default to the campaign shape
     * (180 s warmup + 300 s measurement) via {@link #block}.
     *
     * @param lossPercents packet-loss severities to sweep (D14: 5% and 30%).
     *        Applied ONLY to PACKET_LOSS cells — expanding every scenario
     *        over a factor it does not have would silently duplicate the
     *        entire block.
     */
    public record Block(SystemUnderTest system, int clusterSize, List<Scenario> scenarios,
                        List<Long> rates, List<Double> conflicts, List<Integer> lossPercents,
                        int repetitions, long seed,
                        int durationSecs, int warmupSecs, int faultAtSecs, int window,
                        int valueSizeBytes, boolean rotateLeaderlessTarget,
                        Path out, Path inventoryFile, String sshUser) {
        public Block {
            if (scenarios.isEmpty() || rates.isEmpty() || conflicts.isEmpty()
                    || repetitions < 1) {
                throw new IllegalArgumentException(
                        "a block needs >=1 scenario, rate, conflict and repetition");
            }
            if (scenarios.contains(Scenario.PACKET_LOSS) && lossPercents.isEmpty()) {
                throw new IllegalArgumentException(
                        "a PACKET_LOSS block needs at least one severity (D14 sweeps 5 and 30)");
            }
        }
    }

    /** D14's preregistered severity sweep: 5% tests the "modest degradation,
     *  continued availability" prediction; 30% probes where degradation turns
     *  qualitative. Both directions are preregistered in
     *  METRICS_AND_SOURCES before any run — changing them after seeing data
     *  would void the preregistration. */
    public static final List<Integer> DEFAULT_LOSS_PERCENTS = List.of(5, 30);

    /** Campaign-default block: 180 s warmup + 300 s measurement, fault 60 s
     *  into the measurement window (runbook §3, F21 valsize 1024). */
    public static Block block(SystemUnderTest system, int clusterSize,
                              List<Scenario> scenarios, List<Long> rates,
                              List<Double> conflicts, int repetitions, long seed,
                              Path out, Path inventoryFile, String sshUser) {
        return new Block(system, clusterSize, scenarios, rates, conflicts,
                DEFAULT_LOSS_PERCENTS, repetitions,
                seed, 480, 180, 240, 200, 1024, false, out, inventoryFile, sshUser);
    }

    /**
     * The failover-distribution block (D15.4, runbook §3): 180 s warmup +
     * 180 s measurement with the fault 60 s in, repeated >=30 times, because
     * a DISTRIBUTION rather than a mean is the object of interest for F4.
     *
     * <p>Both shapes inject at the same absolute instant (t = 240 s);
     * {@code faultAtSecs} counts from RUN start, warmup included. What
     * differs is post-fault observation — 120 s here against the standard
     * block's 240 s — and 120 s is twice the ±60 s recovery window
     * methodology §4.3 asks for, ample for sub-second Raft re-election and
     * equally conclusive for the preregistered paxi wedge. At ~2 min saved
     * per trial that is ~7 h of cluster time across the campaign.
     *
     * <p>Leaderless targeting ROTATES here and only here (D15.5): the n=5
     * fault cells keep replica 0 so each cell is reproducible from its
     * config_hash, while these trials sweep every replica so the
     * distribution TESTS the leaderless-symmetry assumption instead of
     * asserting it.
     */
    public static Block failoverBlock(SystemUnderTest system, int clusterSize, long rate,
                                      int repetitions, long seed,
                                      Path out, Path inventoryFile, String sshUser) {
        return new Block(system, clusterSize, List.of(Scenario.LEADER_KILL),
                List.of(rate), List.of(0.0), DEFAULT_LOSS_PERCENTS, repetitions,
                seed, 360, 180, 240, 200, 1024, true, out, inventoryFile, sshUser);
    }

    /** The seam the tests drive; production passes RemoteRunner::run. */
    @FunctionalInterface
    public interface CellRunner {
        void run(RemoteRunner.Spec spec) throws Exception;
    }

    public record Summary(int ran, int skipped, int failed) { }

    /** Every cell of the block, in seeded-shuffled execution order. */
    public static List<RemoteRunner.Spec> specs(Block b) {
        List<RemoteRunner.Spec> out = new ArrayList<>();
        for (Scenario scenario : b.scenarios()) {
            for (long rate : b.rates()) {
                for (double conflict : b.conflicts()) {
                    // Severity is a factor of PACKET_LOSS alone (D14). Every
                    // other scenario expands over the singleton 0, so the
                    // block size is unchanged for them.
                    List<Integer> losses = scenario == Scenario.PACKET_LOSS
                            ? b.lossPercents() : List.of(0);
                    for (int loss : losses) {
                        for (int rep = 1; rep <= b.repetitions(); rep++) {
                            String runId = (rate > 0 ? "rate" + rate : "sat")
                                    + String.format("r%02d", rep);
                            // D15.5: plain modulo rotation, not a seeded
                            // random draw. Both are reproducible, but at 30
                            // trials over 3 replicas this gives EXACTLY 10
                            // each while a random draw gives something like
                            // 15/8/7 — and the whole point of rotating is to
                            // test the symmetry assumption, which an
                            // unbalanced sample tests worse.
                            int leaderless = b.rotateLeaderlessTarget()
                                    ? (rep - 1) % b.clusterSize() : 0;
                            out.add(new RemoteRunner.Spec(b.system(), scenario, b.clusterSize(),
                                    rate, b.durationSecs(), b.warmupSecs(), b.window(),
                                    b.valueSizeBytes(), conflict, b.faultAtSecs(), loss,
                                    leaderless, b.out(), runId, b.inventoryFile(), b.sshUser()));
                        }
                    }
                }
            }
        }
        Collections.shuffle(out, new Random(b.seed()));
        return out;
    }

    /**
     * Serialize the resolved simulation to {@code <out>/<system>/simulation.json}
     * (D12's publishable half). Local paths, the inventory file and the SSH
     * user are deliberately EXCLUDED: a simulation is the EXPERIMENT, not the
     * machine that ran it, and a spec carrying someone's home directory is
     * neither citable nor comparable across reruns.
     *
     * <p>Fails CLOSED when a DIFFERENT spec already sits there. That is what
     * links a cell to its spec without threading a hash through every
     * manifest: the link is structural (cells live under the spec's
     * directory), and it is only trustworthy if the file cannot be quietly
     * replaced beneath results it does not describe. Re-running the SAME
     * block writes identical bytes, so resume is unaffected — which is the
     * behaviour to preserve, because resume is a rerun of the same command.
     */
    public static void writeSimulationSpec(Block b) throws IOException {
        Path dir = b.out().resolve(b.system().name().toLowerCase());
        Files.createDirectories(dir);
        Path file = dir.resolve("simulation.json");
        String json = simulationJson(b);
        if (Files.exists(file)) {
            String existing = Files.readString(file);
            if (!existing.equals(json)) {
                throw new IllegalStateException(file + " already describes a DIFFERENT"
                        + " simulation. The cells under it were produced by that one, so"
                        + " overwriting it would leave them describing an experiment that"
                        + " never ran. Use a fresh --out, or delete the tree deliberately."
                        + "\n--- on disk ---\n" + existing + "\n--- would write ---\n" + json);
            }
            return;
        }
        Files.writeString(file, json);
        log.info("simulation spec -> {}", file);
    }

    /** Insertion-ordered by hand rather than reflected, so the published
     *  artifact's field order is a decision and not a refactor away from
     *  changing. */
    static String simulationJson(Block b) {
        return """
                {
                  "system": "%s",
                  "cluster_size": %d,
                  "scenarios": [%s],
                  "rates_ops_s": [%s],
                  "conflict_ratios": [%s],
                  "loss_percents": [%s],
                  "repetitions": %d,
                  "seed": %d,
                  "duration_secs": %d,
                  "warmup_secs": %d,
                  "fault_at_secs": %d,
                  "window": %d,
                  "value_size_bytes": %d,
                  "rotate_leaderless_target": %b,
                  "harness_version": "%s"
                }
                """.formatted(
                b.system().name().toLowerCase(), b.clusterSize(),
                b.scenarios().stream().map(x -> "\"" + x.name().toLowerCase() + "\"")
                        .collect(java.util.stream.Collectors.joining(", ")),
                b.rates().stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(", ")),
                b.conflicts().stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(", ")),
                b.lossPercents().stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(", ")),
                b.repetitions(), b.seed(), b.durationSecs(), b.warmupSecs(),
                b.faultAtSecs(), b.window(), b.valueSizeBytes(),
                b.rotateLeaderlessTarget(), CsvResultsWriter.harnessVersion());
    }

    /** A cell is complete when its manifest says so — the resume check. */
    static boolean alreadyComplete(RemoteRunner.Spec spec) {
        Path manifest = new CsvResultsWriter.RunIdentity(spec.system(), spec.scenario(),
                spec.clusterSize(), spec.conflictRatio(), spec.packetLossPercent(),
                spec.runId())
                .dir(spec.out()).resolve("manifest.json");
        try {
            return Files.exists(manifest)
                    && Files.readString(manifest).contains("\"status\": \"complete\"");
        } catch (Exception e) {
            // An unreadable manifest is not evidence of completion.
            log.warn("could not read {} — treating the cell as NOT complete: {}",
                    manifest, e.toString());
            return false;
        }
    }

    /**
     * S4.1 — the per-simulation JOURNAL, shaped after Chaos Toolkit's run
     * journal (status, start/end/duration, per-activity results, rollbacks,
     * and a `deviated` flag). Until now the block recorded ONLY failures, in
     * campaign-log.jsonl, which meant a reader could not tell "skipped on
     * resume" from "never attempted" — and after an 11-hour block that is
     * exactly the question they have.
     *
     * <p>One line per cell, appended as it finishes, so a block killed
     * halfway still leaves an accurate record of what it did. JSONL rather
     * than one JSON document for the same reason: an interrupted write costs
     * the last line, not the file.
     */
    private static void journal(Path out, String cell, String outcome, Instant started,
                                String detail) {
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("journal.jsonl"),
                    "{\"t\":\"" + Instant.now() + "\",\"cell\":\"" + cell
                            + "\",\"outcome\":\"" + outcome
                            + "\",\"duration_s\":"
                            + java.time.Duration.between(started, Instant.now()).toSeconds()
                            + ",\"detail\":\"" + escape(detail) + "\"}\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception io) {
            log.error("could not append to journal.jsonl: {}", io.toString());
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    public static Summary run(Block b, CellRunner runner, boolean dryRun) throws Exception {
        List<RemoteRunner.Spec> specs = specs(b);
        if (!dryRun) {
            // Before any cell runs: the spec that produced them, on disk.
            writeSimulationSpec(b);
        }
        log.info("block {} size={}: {} cells, seed={} (order is reproducible){}",
                b.system(), b.clusterSize(), specs.size(), b.seed(),
                dryRun ? " — DRY RUN, executing nothing" : "");
        int ran = 0, skipped = 0, failed = 0;
        for (RemoteRunner.Spec spec : specs) {
            // Mirrors RunIdentity.dir()'s segments, severity included (D14) —
            // otherwise two severities log and fail under the same cell name.
            String cell = spec.system() + "/" + spec.scenario() + "/size" + spec.clusterSize()
                    + (spec.conflictRatio() > 0 ? "/c" + Math.round(spec.conflictRatio() * 100) : "")
                    + (spec.packetLossPercent() > 0 ? "/loss" + spec.packetLossPercent() : "")
                    + "/" + spec.runId();
            if (dryRun) {
                log.info("dry-run: {}", cell);
                continue;
            }
            Instant cellStart = Instant.now();
            if (alreadyComplete(spec)) {
                log.info("resume: {} already complete — skipping", cell);
                skipped++;
                journal(b.out(), cell, "skipped", cellStart, "already complete (resume)");
                continue;
            }
            try {
                runner.run(spec);
                ran++;
                journal(b.out(), cell, "ran", cellStart, "");
            } catch (Exception e) {
                failed++;
                log.error("cell {} FAILED — recorded, continuing: {}", cell, e.toString());
                appendFailure(b.out(), cell, e);
                journal(b.out(), cell, "failed", cellStart, e.toString());
            }
        }
        log.info("block {} done: {} ran, {} skipped (resume), {} failed{}",
                b.system(), ran, skipped, failed,
                failed > 0 ? " — see campaign-log.jsonl, rerun those cells" : "");
        return new Summary(ran, skipped, failed);
    }

    /** One JSON line per failed cell — greppable, appendable, never lost. */
    private static void appendFailure(Path out, String cell, Exception e) {
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("campaign-log.jsonl"),
                    "{\"t\":\"" + Instant.now() + "\",\"cell\":\"" + cell
                            + "\",\"error\":\"" + e.toString().replace("\\", "\\\\")
                            .replace("\"", "\\\"") + "\"}\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception io) {
            log.error("could not append to campaign-log.jsonl: {}", io.toString());
        }
    }
}
