package gr.thesis.bench.validity;

import gr.thesis.bench.validity.ValidityChecker.GateResult;
import gr.thesis.bench.validity.ValidityChecker.Report;
import gr.thesis.bench.validity.ValidityChecker.State;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5.5 — the six §4 validity gates on synthetic run dirs (the plan's
 * acceptance: "pass + each failure mode"). Every fixture is a hand-built
 * run directory, so a threshold or logic drift shows as a wrong gate state.
 * The §4 meta-rule (empty series present ⇒ FAIL, whole feature absent ⇒
 * SKIP) is pinned directly.
 */
class ValidityCheckerTest {

    /** A clean fixed-rate baseline run, harness output only (no metrics/). */
    private static void baseline(Path dir, long rate, int[] throughput) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), manifest(rate, 2, 6, "baseline", null));
        StringBuilder tp = new StringBuilder();
        for (int t = 0; t < throughput.length; t++) tp.append(t).append(',').append(throughput[t]).append('\n');
        Files.writeString(dir.resolve("throughput.csv"), tp.toString());
    }

    private static String manifest(long rate, int warmup, int duration, String scenario,
                                   String faultAtMs) {
        return "{\n"
                + "  \"system\": \"etcd\",\n"
                + "  \"scenario\": \"" + scenario + "\",\n"
                + "  \"started_at\": \"2026-08-03T14:00:00Z\",\n"
                + "  \"rate_ops_s\": " + rate + ",\n"
                + "  \"warmup_secs\": " + warmup + ",\n"
                + "  \"duration_secs\": " + duration + ",\n"
                + "  \"fault_injected_at_ms\": " + (faultAtMs == null ? "null" : faultAtMs) + "\n"
                + "}\n";
    }

    private static void metric(Path dir, String name, String rows) throws IOException {
        Path md = dir.resolve("metrics");
        Files.createDirectories(md);
        Files.writeString(md.resolve(name + ".csv"), "t_unix,instance,value\n" + rows);
    }

    private static State stateOf(Report r, String gate) {
        return r.gates().stream().filter(g -> g.gate().equals(gate))
                .map(GateResult::state).findFirst().orElseThrow();
    }

    @Test
    void cleanHarnessOnlyRunIsValidWithMetricGatesSkipped(@TempDir Path dir) throws IOException {
        // rate 100, steady ~100 ops/s: rate adherence + convergence PASS,
        // metric gates SKIP (no metrics/ dir), overall VALID.
        baseline(dir, 100, new int[]{100, 100, 100, 100, 100, 100});
        Report r = ValidityChecker.check(dir);
        assertTrue(r.valid(), "a clean harness-only run is valid as far as evaluated");
        assertEquals(State.PASS, stateOf(r, "rate_adherence"));
        assertEquals(State.PASS, stateOf(r, "convergence"));
        assertEquals(State.SKIP, stateOf(r, "loadgen_cpu"));
        assertEquals(State.SKIP, stateOf(r, "durability"));
        assertTrue(Files.exists(dir.resolve("validity.json")));
    }

    @Test
    void underRateFailsClientBottleneckGate(@TempDir Path dir) throws IOException {
        // target 100, achieving ~60 (<99%): the instrument couldn't keep up.
        baseline(dir, 100, new int[]{60, 60, 60, 60, 60, 60});
        Report r = ValidityChecker.check(dir);
        assertEquals(State.FAIL, stateOf(r, "rate_adherence"));
        assertFalse(r.valid());
    }

    @Test
    void stillRampingFailsConvergence(@TempDir Path dir) throws IOException {
        // warmup 2 s at ~40, measurement head at ~100: >20% jump = not
        // converged when measurement began.
        baseline(dir, 100, new int[]{40, 40, 100, 100, 100, 100});
        Report r = ValidityChecker.check(dir);
        assertEquals(State.FAIL, stateOf(r, "convergence"));
    }

    @Test
    void saturationRunSkipsRateAdherence(@TempDir Path dir) throws IOException {
        baseline(dir, 0, new int[]{500, 510, 505, 500, 500, 500});
        Report r = ValidityChecker.check(dir);
        assertEquals(State.SKIP, stateOf(r, "rate_adherence"), "no target rate in saturation mode");
    }

    @Test
    void loadgenCpuOverSeventyPercentFails(@TempDir Path dir) throws IOException {
        baseline(dir, 100, new int[]{100, 100, 100, 100, 100, 100});
        metric(dir, "loadgen_cpu", "1000,10.0.0.20,0.55\n1005,10.0.0.20,0.82\n");
        Report r = ValidityChecker.check(dir);
        assertEquals(State.FAIL, stateOf(r, "loadgen_cpu"), "peak 82% > 70%");
        assertFalse(r.valid());
    }

    @Test
    void emptySeriesWithMetricsDirPresentFailsPerMetaRule(@TempDir Path dir) throws IOException {
        baseline(dir, 100, new int[]{100, 100, 100, 100, 100, 100});
        // metrics/ EXISTS (some other file) but loadgen_cpu.csv is header-only.
        metric(dir, "loadgen_cpu", "");                 // header, no data rows
        metric(dir, "loadgen_cpu_steal", "1000,x,0.0\n"); // this one has data
        Report r = ValidityChecker.check(dir);
        assertEquals(State.FAIL, stateOf(r, "loadgen_cpu"),
                "empty series with metrics/ present is a FAIL, not a SKIP (§4 meta-rule)");
    }

    @Test
    void cpuStealViolatesStationarity(@TempDir Path dir) throws IOException {
        baseline(dir, 100, new int[]{100, 100, 100, 100, 100, 100});
        metric(dir, "node_cpu_steal", "1000,10.0.0.11,0.001\n1005,10.0.0.12,0.03\n");
        Report r = ValidityChecker.check(dir);
        assertEquals(State.FAIL, stateOf(r, "node_cpu_steal"), "3% steal > 1% ceiling");
    }

    @Test
    void clockOffsetOverFiveMillisFails(@TempDir Path dir) throws IOException {
        baseline(dir, 100, new int[]{100, 100, 100, 100, 100, 100});
        metric(dir, "clock_offset", "1000,10.0.0.11,0.002\n1005,10.0.0.12,0.009\n");
        Report r = ValidityChecker.check(dir);
        assertEquals(State.FAIL, stateOf(r, "clock_discipline"), "9 ms > 5 ms");
    }

    @Test
    void baselineRunSkipsFaultGroundTruth(@TempDir Path dir) throws IOException {
        baseline(dir, 100, new int[]{100, 100, 100, 100, 100, 100});
        metric(dir, "node_up", "1000,10.0.0.11,1\n");
        Report r = ValidityChecker.check(dir);
        assertEquals(State.SKIP, stateOf(r, "fault_ground_truth"), "no fault injected — N/A");
    }

    @Test
    void faultRunWithoutAMarkFailsRatherThanPosingAsBaseline(@TempDir Path dir) throws IOException {
        // F50: the injection threw (or stalled past the runner's join), so no
        // mark was ever stamped — but the manifest still says leader_kill.
        // Reading ONLY the mark makes the gate answer "baseline run — N/A",
        // which is the wrong answer to the right question: the run is void as
        // a fault cell, and this gate is the last layer that can say so.
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"),
                manifest(100, 2, 6, "leader_kill", null));
        Files.writeString(dir.resolve("throughput.csv"),
                "0,100\n1,100\n2,100\n3,100\n4,100\n5,100\n");

        Report r = ValidityChecker.check(dir);
        assertEquals(State.FAIL, stateOf(r, "fault_ground_truth"),
                "a fault run carrying no injection mark is not a baseline run");
        assertFalse(r.valid(), "and it must not come out valid");
    }

    @Test
    void faultRunCorroboratedByLeaderChangePasses(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        // fault at 3000 ms after start 14:00:00Z -> epoch of 14:00:03.
        Files.writeString(dir.resolve("manifest.json"),
                manifest(100, 2, 6, "leader_kill", "3000"));
        Files.writeString(dir.resolve("throughput.csv"), "0,100\n1,100\n2,100\n3,0\n4,90\n5,100\n");
        long faultEpoch = Instant.parse("2026-08-03T14:00:03Z").getEpochSecond();
        // leader-change counter steps up across the window.
        metric(dir, "etcd_leader_chg",
                (faultEpoch - 5) + ",10.0.0.11,2\n" + (faultEpoch + 5) + ",10.0.0.11,3\n");
        Report r = ValidityChecker.check(dir);
        assertEquals(State.PASS, stateOf(r, "fault_ground_truth"), "leader changed within ±60 s");
    }

    @Test
    void faultRunWithoutCorroborationFailsForReclassification(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"),
                manifest(100, 2, 6, "leader_kill", "3000"));
        Files.writeString(dir.resolve("throughput.csv"), "0,100\n1,100\n2,100\n3,100\n4,100\n5,100\n");
        long faultEpoch = Instant.parse("2026-08-03T14:00:03Z").getEpochSecond();
        // Counter present but FLAT — a leader_kill that didn't change the leader.
        metric(dir, "etcd_leader_chg",
                (faultEpoch - 5) + ",10.0.0.11,2\n" + (faultEpoch + 5) + ",10.0.0.11,2\n");
        metric(dir, "node_up",
                (faultEpoch) + ",10.0.0.11,1\n");
        Report r = ValidityChecker.check(dir);
        assertEquals(State.FAIL, stateOf(r, "fault_ground_truth"),
                "no corroboration — the run must be reclassified, not averaged in");
        assertTrue(r.gates().stream().anyMatch(g -> g.gate().equals("fault_ground_truth")
                && g.detail().contains("reclassify")));
    }

    @Test
    void checkTreeCountsValidRuns(@TempDir Path root) throws IOException {
        baseline(root.resolve("etcd/baseline/size3/r01"), 100, new int[]{100, 100, 100, 100, 100, 100});
        baseline(root.resolve("etcd/baseline/size3/r02"), 100, new int[]{50, 50, 50, 50, 50, 50}); // under-rate
        assertEquals(1, ValidityChecker.checkTree(root), "one valid, one client-bound");
    }

    // ---- F40: every metric the checker consults must have a producer ----

    @Test
    void everyConsultedMetricNameExistsInTheExportQuerySet() throws IOException {
        // The cross-layer contract pin: metrics/<name>.csv files come from
        // export_queries.txt (M5.4 runs exactly those queries). A gate
        // reading a name no query produces would FAIL every run via the
        // meta-rule with a FALSE "broken retrieval" diagnosis — exactly how
        // clock_offset shipped unbacked (F40). Drift now fails HERE first.
        Path queries = Path.of("../observability/export_queries.txt");
        assertTrue(Files.exists(queries), "export_queries.txt must be in the repo at " + queries);
        var produced = Files.readAllLines(queries).stream()
                .filter(l -> !l.strip().startsWith("#") && l.contains("|"))
                .map(l -> l.substring(0, l.indexOf('|')).strip())
                .filter(n -> !n.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        for (String needed : ValidityChecker.CONSULTED_METRICS) {
            assertTrue(produced.contains(needed),
                    "checker consults metric '" + needed + "' but no export query produces it"
                            + " (defined names: " + produced + ")");
        }
    }

    // ---- F41: gate-3 corroboration is per-system, honest where a system
    //      structurally has no server-side series ----

    private static void faultRun(Path dir, String system) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"),
                manifest(100, 2, 6, "leader_kill", "3000").replace("\"etcd\"", "\"" + system + "\""));
        Files.writeString(dir.resolve("throughput.csv"), "0,100\n1,100\n2,100\n3,0\n4,90\n5,100\n");
    }

    @Test
    void kafkaFaultRunCorroboratedByUnderReplicatedPartitionsPasses(@TempDir Path dir) throws IOException {
        faultRun(dir, "kraft");
        long faultEpoch = Instant.parse("2026-08-03T14:00:03Z").getEpochSecond();
        // URP rises above zero inside the window: replication degraded —
        // the broker fault is corroborated (the F6 dashboards' signature).
        metric(dir, "kafka_urp",
                (faultEpoch - 5) + ",10.0.0.11,0\n" + (faultEpoch + 5) + ",10.0.0.11,4\n");
        Report r = ValidityChecker.check(dir);
        assertEquals(State.PASS, stateOf(r, "fault_ground_truth"),
                "URP > 0 within ±60 s corroborates a Kafka broker fault");
    }

    @Test
    void kafkaFaultRunWithFlatUrpFailsForReclassification(@TempDir Path dir) throws IOException {
        faultRun(dir, "kafka_zk");
        long faultEpoch = Instant.parse("2026-08-03T14:00:03Z").getEpochSecond();
        metric(dir, "kafka_urp",
                (faultEpoch - 5) + ",10.0.0.11,0\n" + (faultEpoch + 5) + ",10.0.0.11,0\n");
        metric(dir, "node_up", faultEpoch + ",10.0.0.11,1\n");
        Report r = ValidityChecker.check(dir);
        assertEquals(State.FAIL, stateOf(r, "fault_ground_truth"),
                "flat URP + node_up steady = the fault left no trace — reclassify");
    }

    @Test
    void paxiFaultRunSkipsGroundTruthNamingTheMissingInstrumentation(@TempDir Path dir) throws IOException {
        // Paxi exposes NO server-side metrics (documented §2/§7 limitation)
        // and the preregistered F26 wedge has no leader change BY DESIGN —
        // an evaluated FAIL would misread both. Honest SKIP, loud reason.
        faultRun(dir, "paxos");
        metric(dir, "node_up", "1000,10.0.0.11,1\n");
        Report r = ValidityChecker.check(dir);
        assertEquals(State.SKIP, stateOf(r, "fault_ground_truth"));
        assertTrue(r.gates().stream().anyMatch(g -> g.gate().equals("fault_ground_truth")
                        && g.detail().contains("P4.5")),
                "the SKIP must name the future corroboration source (docker-events, P4.5)");
    }

    // ---- F42: HotStuff runs must not fail gates they structurally
    //      cannot have inputs for ----

    @Test
    void hotstuffRunSkipsThroughputGatesItStructurallyCannotHave(@TempDir Path dir) throws IOException {
        // A HotStuff run dir has summary.txt + logs/, no throughput.csv or
        // latency.csv (its client SUMMARY is the metrics source, P2.5) —
        // rate_adherence/convergence have no input BY DESIGN, not by breakage.
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"),
                manifest(100, 2, 6, "baseline", null).replace("\"etcd\"", "\"hotstuff\""));
        Files.writeString(dir.resolve("summary.txt"), "(SUMMARY placeholder)");
        Report r = ValidityChecker.check(dir);
        assertEquals(State.SKIP, stateOf(r, "rate_adherence"));
        assertEquals(State.SKIP, stateOf(r, "convergence"));
        assertTrue(r.valid(), "no evaluated gate failed — valid as far as evaluated");
    }

    // ---- F43: the two §4 gate halves with no collector yet must appear
    //      as LOUD skips, not vanish ----

    @Test
    void unimplementedGateHalvesAppearAsLoudSkips(@TempDir Path dir) throws IOException {
        baseline(dir, 100, new int[]{100, 100, 100, 100, 100, 100});
        Report r = ValidityChecker.check(dir);
        assertEquals(State.SKIP, stateOf(r, "window_headroom"),
                "gate 1's window-ceiling half awaits harness self-metrics (M5.3)");
        assertEquals(State.SKIP, stateOf(r, "container_restarts"),
                "gate 4's restart-audit half awaits the docker-events audit (P4.5)");
    }

    // ---- F44: one corrupt run dir must not abort the whole tree walk ----

    @Test
    void checkTreeRecordsAnUncheckableRunAndContinues(@TempDir Path root) throws IOException {
        baseline(root.resolve("etcd/baseline/size3/r01"), 100, new int[]{100, 100, 100, 100, 100, 100});
        Path corrupt = root.resolve("etcd/baseline/size3/r02");
        Files.createDirectories(corrupt);
        Files.writeString(corrupt.resolve("manifest.json"), "{not json");
        baseline(root.resolve("etcd/baseline/size3/r03"), 100, new int[]{100, 100, 100, 100, 100, 100});
        assertEquals(2, ValidityChecker.checkTree(root),
                "the corrupt run counts as not-valid; the runs after it are still checked");
    }
}
