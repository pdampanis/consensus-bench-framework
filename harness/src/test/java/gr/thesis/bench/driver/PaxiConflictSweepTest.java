package gr.thesis.bench.driver;

import gr.thesis.bench.core.LatencyRecorder;
import gr.thesis.bench.core.Scenario;
import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.core.WorkloadEngine;
import gr.thesis.bench.results.CsvResultsWriter;
import gr.thesis.bench.topology.LocalDockerProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2.4c acceptance (integration — Docker, paxi:6823d0b): EPaxos exercised
 * for real, and the D7 conflict knob end-to-end.
 *
 * The retired probe drove EPaxos through ONE endpoint with globally-unique
 * keys — the leaderless fast path was never exercised and c-sweeps were
 * unfireable. Here the SAME engine drives round-robin writes over all
 * three replicas (every entry is a command leader), and the c=10% run
 * carries its identity through the whole chain: engine keyFor(c) → real
 * EPaxos commits → `c10` path segment + `conflict_ratio` manifest field.
 *
 * Laptop numbers are FUNCTIONAL evidence only (environment=local): these
 * tests pin that the sweep RUNS clean, not what it measures — the
 * fast-vs-slow-path performance question belongs to the campaign cluster.
 */
class PaxiConflictSweepTest {

    @TempDir
    Path out;

    private static WorkloadEngine.Result run(java.util.List<String> eps, double conflict,
                                             LatencyRecorder rec) throws Exception {
        try (var driver = new PaxiDriver(SystemUnderTest.EPAXOS, eps)) {
            driver.connect();
            // 4 s @ 150 ops/s, window 64: ~600 ops, ~200 per replica entry —
            // a dead or non-committing entry would surface as ~1/3 errors.
            var cfg = new WorkloadEngine.Config(4, 1, 150, 64, 64, conflict);
            return new WorkloadEngine(driver, cfg, rec).run();
        }
    }

    @Test
    void epaxosCommitsUnderTheEngineThroughEveryEntry() throws Exception {
        try (var provider = new LocalDockerProvider()) {
            provider.start(SystemUnderTest.EPAXOS, 3);
            var rec = new LatencyRecorder();
            var r = run(provider.clientEndpoints(), 0.0, rec);

            long attempts = rec.countAfterWarmup() + r.errors();
            assertTrue(r.errors() <= attempts / 100,
                    "EPaxos round-robin must commit through every entry; "
                            + r.errors() + "/" + attempts + " failed — first cause: "
                            + r.firstError());
            assertTrue(rec.countAfterWarmup() >= 300,
                    "expected ~450 post-warmup commits at 150 ops/s, got "
                            + rec.countAfterWarmup());
        }
    }

    @Test
    void conflictSweepRunsEndToEndWithTheC10Identity() throws Exception {
        try (var provider = new LocalDockerProvider()) {
            provider.start(SystemUnderTest.EPAXOS, 3);
            var rec = new LatencyRecorder();
            Instant started = Instant.now();
            var r = run(provider.clientEndpoints(), 0.10, rec);
            Instant ended = Instant.now();

            long attempts = rec.countAfterWarmup() + r.errors();
            assertTrue(r.errors() <= attempts / 100,
                    "c=10% interference must still commit (in-memory LAN cluster); "
                            + r.errors() + "/" + attempts + " failed — first cause: "
                            + r.firstError());

            // The D7 identity chain: the same Config's ratio lands in the
            // path AND the manifest of a run against the REAL system.
            var id = new CsvResultsWriter.RunIdentity(
                    SystemUnderTest.EPAXOS, Scenario.BASELINE, 3, 0.10, "c10e2e");
            new CsvResultsWriter().write(out, id, r,
                    new WorkloadEngine.Config(4, 1, 150, 64, 64, 0.10),
                    "local", LocalDockerProvider.PAXI_IMAGE, started, ended);

            Path dir = id.dir(out);
            assertTrue(dir.toString().endsWith("epaxos/baseline/size3/c10/c10e2e"),
                    "conflict cell must be path-separated: " + dir);
            String manifest = Files.readString(dir.resolve("manifest.json"));
            assertTrue(manifest.contains("\"conflict_ratio\": 0.10"), manifest);
            assertTrue(manifest.contains("\"status\": \"complete\""), manifest);
        }
    }
}
