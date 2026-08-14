package gr.thesis.bench.campaign;

import gr.thesis.bench.core.Scenario;
import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.results.CsvResultsWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3.3-full: the campaign matrix executor, pinned BEFORE it ever loops a
 * billed cell. Generation counts, the M0-tree runId convention, seeded
 * reproducible shuffle, manifest-resume, and failure-continues — the
 * runner must never hide an invalid cell and never let one abort the
 * night's remaining cells.
 */
class MatrixRunnerTest {

    private static MatrixRunner.Block block(Path out, long seed, int reps) {
        return MatrixRunner.block(SystemUnderTest.ETCD, 3,
                List.of(Scenario.BASELINE, Scenario.LEADER_KILL),
                List.of(300L, 0L), List.of(0.0), reps, seed,
                out, Path.of("deploy/inventory.env"), "root");
    }

    @Test
    void generatesEveryCellWithTheM0RunIdConvention(@TempDir Path out) {
        List<RemoteRunner.Spec> specs = MatrixRunner.specs(block(out, 42, 2));
        // 2 scenarios x 2 rates x 1 conflict x 2 reps = 8 cells.
        assertEquals(8, specs.size());
        // Rate lives in the runId because it is NOT a path segment (the M0
        // evidence tree: .../size1/rate300/) — identity stays collision-free.
        Set<String> ids = specs.stream()
                .map(s -> s.scenario() + "/" + s.runId())
                .collect(Collectors.toSet());
        assertEquals(8, ids.size(), "every cell must land in its own directory");
        assertTrue(specs.stream().anyMatch(s -> s.runId().equals("rate300r02")));
        assertTrue(specs.stream().anyMatch(s -> s.runId().equals("satr01")));
        // Campaign defaults ride every spec (F21 valsize, runbook durations).
        assertTrue(specs.stream().allMatch(s ->
                s.valueSizeBytes() == 1024 && s.durationSecs() == 480
                        && s.warmupSecs() == 180 && s.faultAtSecs() == 240));
    }

    @Test
    void shuffleIsSeededAndReproducible(@TempDir Path out) {
        assertEquals(orderOf(out, 42), orderOf(out, 42),
                "same seed -> same order: the randomization itself is reproducible");
        assertNotEquals(orderOf(out, 42), orderOf(out, 43),
                "different seed -> different order (methodology §1 randomization)");
        // Scenarios are interleaved by the shuffle, not run in declaration
        // blocks — time-correlated drift must not favor one scenario.
        List<RemoteRunner.Spec> specs = MatrixRunner.specs(block(out, 42, 5));
        String first = specs.get(0).scenario().name();
        assertTrue(specs.stream().limit(10)
                        .anyMatch(s -> !s.scenario().name().equals(first)),
                "the first ten cells must not all be one scenario");
    }

    private static List<String> orderOf(Path out, long seed) {
        return MatrixRunner.specs(block(out, seed, 3)).stream()
                .map(s -> s.scenario() + "/" + s.runId()).toList();
    }

    @Test
    void resumeSkipsCellsWhoseManifestSaysComplete(@TempDir Path out) throws Exception {
        MatrixRunner.Block b = block(out, 42, 2);
        RemoteRunner.Spec done = MatrixRunner.specs(b).get(0);
        Path dir = new CsvResultsWriter.RunIdentity(done.system(), done.scenario(),
                done.clusterSize(), done.conflictRatio(), done.runId()).dir(out);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), "{\n  \"status\": \"complete\"\n}");

        List<String> executed = new ArrayList<>();
        MatrixRunner.Summary s = MatrixRunner.run(b,
                spec -> executed.add(spec.scenario() + "/" + spec.runId()), false);

        assertEquals(1, s.skipped(), "the complete cell is skipped — rerun = resume");
        assertEquals(7, s.ran());
        assertTrue(!executed.contains(done.scenario() + "/" + done.runId()),
                "the completed cell must not run again");
    }

    @Test
    void aFailedCellIsRecordedAndTheBlockContinues(@TempDir Path out) throws Exception {
        MatrixRunner.Block b = block(out, 42, 1); // 4 cells
        String failing = MatrixRunner.specs(b).get(1).runId();
        String failingScenario = MatrixRunner.specs(b).get(1).scenario().name();

        MatrixRunner.Summary s = MatrixRunner.run(b, spec -> {
            if (spec.runId().equals(failing)
                    && spec.scenario().name().equals(failingScenario)) {
                throw new IllegalStateException("cluster never formed (simulated)");
            }
        }, false);

        assertEquals(1, s.failed());
        assertEquals(3, s.ran(), "one failure must not abort the remaining cells");
        String jsonl = Files.readString(out.resolve("campaign-log.jsonl"));
        assertTrue(jsonl.contains(failing) && jsonl.contains("cluster never formed"),
                "the failed cell and its cause must be greppable: " + jsonl);
    }

    @Test
    void dryRunExecutesNothing(@TempDir Path out) throws Exception {
        MatrixRunner.Summary s = MatrixRunner.run(block(out, 42, 2),
                spec -> { throw new AssertionError("dry-run must never execute a cell"); },
                true);
        assertEquals(0, s.ran() + s.skipped() + s.failed());
    }

    // ---- D14/F53: severity is a swept factor, and only for packet_loss ----

    @Test
    void packetLossExpandsOverSeveritiesAndOtherScenariosDoNot(@TempDir Path out) {
        var b = MatrixRunner.block(SystemUnderTest.ETCD, 3,
                List.of(Scenario.BASELINE, Scenario.PACKET_LOSS),
                List.of(300L), List.of(0.0), 1, 42,
                out, Path.of("deploy/inventory.env"), "root");
        List<RemoteRunner.Spec> specs = MatrixRunner.specs(b);

        // baseline x1 + packet_loss x2 severities = 3, not 4: sweeping a
        // severity a scenario does not have would duplicate every cell.
        assertEquals(3, specs.size(), specs.toString());
        var losses = specs.stream()
                .filter(s -> s.scenario() == Scenario.PACKET_LOSS)
                .map(RemoteRunner.Spec::packetLossPercent).sorted().toList();
        assertEquals(List.of(5, 30), losses, "D14 sweeps 5% and 30%");
        assertEquals(0, specs.stream().filter(s -> s.scenario() == Scenario.BASELINE)
                .findFirst().orElseThrow().packetLossPercent(),
                "a baseline cell has no severity to carry");
    }

    @Test
    void thePacketLossSeveritiesLandInDifferentDirectories(@TempDir Path out) {
        var b = MatrixRunner.block(SystemUnderTest.ETCD, 3,
                List.of(Scenario.PACKET_LOSS), List.of(300L), List.of(0.0), 1, 42,
                out, Path.of("deploy/inventory.env"), "root");
        var dirs = MatrixRunner.specs(b).stream()
                .map(s -> new gr.thesis.bench.results.CsvResultsWriter.RunIdentity(
                        s.system(), s.scenario(), s.clusterSize(), s.conflictRatio(),
                        s.packetLossPercent(), s.runId()).dir(out))
                .distinct().toList();
        assertEquals(2, dirs.size(),
                "the two severities must not share a directory — that is the D14"
                        + " overwrite this increment exists to prevent: " + dirs);
    }
}
