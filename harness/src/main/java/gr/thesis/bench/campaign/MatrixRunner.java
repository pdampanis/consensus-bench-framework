package gr.thesis.bench.campaign;

import gr.thesis.bench.core.Scenario;
import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.results.CsvResultsWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /** One system block. Durations default to the campaign shape
     *  (180 s warmup + 300 s measurement) via {@link #block}. */
    public record Block(SystemUnderTest system, int clusterSize, List<Scenario> scenarios,
                        List<Long> rates, List<Double> conflicts, int repetitions, long seed,
                        int durationSecs, int warmupSecs, int window, int valueSizeBytes,
                        Path out, Path inventoryFile, String sshUser) {
        public Block {
            if (scenarios.isEmpty() || rates.isEmpty() || conflicts.isEmpty()
                    || repetitions < 1) {
                throw new IllegalArgumentException(
                        "a block needs >=1 scenario, rate, conflict and repetition");
            }
        }
    }

    /** Campaign-default block (runbook §3 durations, F21 valsize 1024). */
    public static Block block(SystemUnderTest system, int clusterSize,
                              List<Scenario> scenarios, List<Long> rates,
                              List<Double> conflicts, int repetitions, long seed,
                              Path out, Path inventoryFile, String sshUser) {
        return new Block(system, clusterSize, scenarios, rates, conflicts, repetitions,
                seed, 480, 180, 200, 1024, out, inventoryFile, sshUser);
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
                    for (int rep = 1; rep <= b.repetitions(); rep++) {
                        String runId = (rate > 0 ? "rate" + rate : "sat")
                                + String.format("r%02d", rep);
                        out.add(new RemoteRunner.Spec(b.system(), scenario, b.clusterSize(),
                                rate, b.durationSecs(), b.warmupSecs(), b.window(),
                                b.valueSizeBytes(), conflict, b.warmupSecs() + 60, 30,
                                b.out(), runId, b.inventoryFile(), b.sshUser()));
                    }
                }
            }
        }
        Collections.shuffle(out, new Random(b.seed()));
        return out;
    }

    /** A cell is complete when its manifest says so — the resume check. */
    static boolean alreadyComplete(RemoteRunner.Spec spec) {
        Path manifest = new CsvResultsWriter.RunIdentity(spec.system(), spec.scenario(),
                spec.clusterSize(), spec.conflictRatio(), spec.runId())
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

    public static Summary run(Block b, CellRunner runner, boolean dryRun) throws Exception {
        List<RemoteRunner.Spec> specs = specs(b);
        log.info("block {} size={}: {} cells, seed={} (order is reproducible){}",
                b.system(), b.clusterSize(), specs.size(), b.seed(),
                dryRun ? " — DRY RUN, executing nothing" : "");
        int ran = 0, skipped = 0, failed = 0;
        for (RemoteRunner.Spec spec : specs) {
            String cell = spec.system() + "/" + spec.scenario() + "/size" + spec.clusterSize()
                    + (spec.conflictRatio() > 0 ? "/c" + Math.round(spec.conflictRatio() * 100) : "")
                    + "/" + spec.runId();
            if (dryRun) {
                log.info("dry-run: {}", cell);
                continue;
            }
            if (alreadyComplete(spec)) {
                log.info("resume: {} already complete — skipping", cell);
                skipped++;
                continue;
            }
            try {
                runner.run(spec);
                ran++;
            } catch (Exception e) {
                failed++;
                log.error("cell {} FAILED — recorded, continuing: {}", cell, e.toString());
                appendFailure(b.out(), cell, e);
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
