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
        return manifest(rate, warmup, duration, scenario, faultAtMs, 0.0);
    }

    private static String manifest(long rate, int warmup, int duration, String scenario,
                                   String faultAtMs, double errorRate) {
        return "{\n"
                + "  \"system\": \"etcd\",\n"
                + "  \"scenario\": \"" + scenario + "\",\n"
                + "  \"started_at\": \"2026-08-03T14:00:00Z\",\n"
                + "  \"rate_ops_s\": " + rate + ",\n"
                + "  \"warmup_secs\": " + warmup + ",\n"
                + "  \"duration_secs\": " + duration + ",\n"
                + "  \"error_rate\": " + errorRate + ",\n"
                + "  \"fault_injected_at_ms\": " + (faultAtMs == null ? "null" : faultAtMs) + "\n"
                + "}\n";
    }

    /** A run dir with a chosen scenario and error rate, steady at `rate`. */
    private static void runWithErrorRate(Path dir, String scenario, double errorRate,
                                         String faultAtMs) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"),
                manifest(100, 2, 6, scenario, faultAtMs, errorRate));
        StringBuilder tp = new StringBuilder();
        for (int t = 0; t < 6; t++) tp.append(t).append(",100\n");
        Files.writeString(dir.resolve("throughput.csv"), tp.toString());
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
    void paxiWithNoEventsAuditStillSkipsRatherThanGuessing(@TempDir Path dir) throws IOException {
        // Paxi exposes NO server-side metrics (documented §2/§7 limitation)
        // and the preregistered F26 wedge has no leader change BY DESIGN — an
        // evaluated FAIL would misread both. Until P4.5 this SKIPped naming
        // the events audit as the FUTURE source; the audit now exists, so the
        // SKIP narrowed to the case where it was not collected for this cell.
        // The behaviour changed because the dependency landed, which is the
        // right reason for a test to change.
        faultRun(dir, "paxos");
        metric(dir, "node_up", "1000,10.0.0.11,1\n");
        Report r = ValidityChecker.check(dir);
        assertEquals(State.SKIP, stateOf(r, "fault_ground_truth"));
        assertTrue(r.gates().stream().anyMatch(g -> g.gate().equals("fault_ground_truth")
                        && g.detail().contains("no events/ audit")),
                "the SKIP must say WHY nothing could corroborate it");
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

    // ---- F52: error_rate was written by the writer and gated by NOBODY ----
    // status tolerates up to 50% failures and rate_adherence SKIPs for
    // saturation runs, so a baseline that failed 49% of its operations
    // reported valid: true. The gate is BASELINE-ONLY by decision (D15/F52):
    // fault runs are SUPPOSED to error, and gating them would exclude exactly
    // the preregistered failure evidence — the F26 paxi wedge and the
    // DOUBLE_KILL liveness demo (ledger note N1).

    @Test
    void baselineThatFailedHalfItsOperationsIsNotValid(@TempDir Path dir) throws IOException {
        runWithErrorRate(dir, "baseline", 0.49, null);
        Report r = ValidityChecker.check(dir);
        assertEquals(State.FAIL, stateOf(r, "baseline_error_rate"),
                "a baseline losing 49% of its ops is not a steady-state measurement");
        assertFalse(r.valid(), "a FAILed gate makes the run invalid");
    }

    @Test
    void cleanBaselinePassesTheErrorGate(@TempDir Path dir) throws IOException {
        runWithErrorRate(dir, "baseline", 0.0, null);
        Report r = ValidityChecker.check(dir);
        assertEquals(State.PASS, stateOf(r, "baseline_error_rate"));
        assertTrue(r.valid());
    }

    @Test
    void aBaselineJustUnderTheThresholdStillPasses(@TempDir Path dir) throws IOException {
        // The boundary is worth pinning: 1% is PROVISIONAL until M6.2 fixes
        // it from pilot variance, and it was chosen from measured evidence
        // (M0: 0 errors; the CometBFT G1 acceptance: <1%).
        runWithErrorRate(dir, "baseline", 0.009, null);
        assertEquals(State.PASS, stateOf(ValidityChecker.check(dir), "baseline_error_rate"));
    }

    @Test
    void faultRunsSkipTheErrorGateBecauseTheyAreMeantToError(@TempDir Path dir) throws IOException {
        runWithErrorRate(dir, "leader_kill", 0.49, "3000");
        Report r = ValidityChecker.check(dir);
        assertEquals(State.SKIP, stateOf(r, "baseline_error_rate"),
                "gating fault runs on error rate would exclude the preregistered"
                        + " paxi wedge and the double_kill liveness demo (N1)");
    }

    @Test
    void aManifestWithNoErrorRateFieldFailsRatherThanPasses(@TempDir Path dir) throws IOException {
        // The §4 meta-rule applied to a manifest field: a v1 manifest that
        // never recorded error_rate must not be waved through as if it had
        // recorded zero. Absent is not zero — the same rule the writer
        // follows for the fault fields (F70).
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), "{\n"
                + "  \"system\": \"etcd\",\n  \"scenario\": \"baseline\",\n"
                + "  \"started_at\": \"2026-08-03T14:00:00Z\",\n  \"rate_ops_s\": 100,\n"
                + "  \"warmup_secs\": 2,\n  \"duration_secs\": 6\n}\n");
        StringBuilder tp = new StringBuilder();
        for (int t = 0; t < 6; t++) tp.append(t).append(",100\n");
        Files.writeString(dir.resolve("throughput.csv"), tp.toString());

        assertEquals(State.FAIL, stateOf(ValidityChecker.check(dir), "baseline_error_rate"),
                "a missing error_rate is an unmeasured error rate, not a zero one");
    }

    // ---- S2.1a/S2.2: the RULE ships with the verdict ----

    @Test
    void everyGateTheCheckerEmitsHasADeclaredRuleAndViceVersa(@TempDir Path dir)
            throws IOException {
        // The F40 drift class in a new costume: a rule with no gate is dead
        // text, and a gate with no rule writes a verdict nobody can audit.
        // Pinning both directions is what stops them separating.
        baseline(dir, 100, new int[]{100, 100, 100, 100, 100, 100});
        var emitted = ValidityChecker.check(dir).gates().stream()
                .map(GateResult::gate).collect(java.util.stream.Collectors.toSet());
        assertEquals(emitted, ValidityChecker.GATE_SPECS.keySet(),
                "declared rules and emitted gates must be the same set");
        ValidityChecker.GATE_SPECS.forEach((gate, spec) -> {
            assertFalse(spec.reference().isBlank(), gate + " must cite its methodology §");
            assertFalse(spec.falsePositives().isBlank(),
                    gate + " must say what would make a FAIL a false positive");
        });
    }

    @Test
    void theVerdictCarriesTheThresholdThatJudgedIt(@TempDir Path dir) throws IOException {
        // M6.2 retunes every PROVISIONAL threshold from pilot variance, so
        // runs either side of that are judged by different numbers. Without
        // the value in the verdict, nobody can tell which judged this one.
        baseline(dir, 100, new int[]{100, 100, 100, 100, 100, 100});
        ValidityChecker.check(dir);
        String v = Files.readString(dir.resolve("validity.json"));
        assertTrue(v.contains("\"threshold\": \"achieved/target >= 0.99\""), v);
        assertTrue(v.contains("\"reference\": \"§4.1\""), v);
    }

    @Test
    void onlyAFailCarriesItsFalsePositiveTriage(@TempDir Path dir) throws IOException {
        runWithErrorRate(dir, "baseline", 0.49, null);
        ValidityChecker.check(dir);
        String v = Files.readString(dir.resolve("validity.json"));
        assertTrue(v.contains("check_before_believing"),
                "a FAILed gate must say what to rule out first: " + v);
        // A PASS does not: attaching triage to every green gate would bury
        // the one line a reader has to act on.
        long occurrences = v.lines().filter(l -> l.contains("check_before_believing")).count();
        assertEquals(1, occurrences, v);
    }

    @Test
    void aQuoteInAGateDetailCannotBreakTheReportJson(@TempDir Path dir) throws IOException {
        // The report is assembled by hand like the manifest, so F21's class
        // applies: unescaped text emits broken JSON that every downstream
        // reader then fails on. gate 3's FAIL detail quotes the scenario.
        runWithErrorRate(dir, "leader_kill", 0.0, null);
        ValidityChecker.check(dir);
        String v = Files.readString(dir.resolve("validity.json"));
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(v); // must parse
        assertTrue(v.contains("fault_ground_truth"), v);
    }

    // ---- M5.3/S3.3: window_headroom is an EVALUATED gate at last ----

    @Test
    void aPinnedInFlightWindowMeansTheCLIENTWasTheCeiling(@TempDir Path dir) throws IOException {
        // Little's Law: at full occupancy the reported number is
        // window/latency, which is a fact about the harness. This project met
        // that confusion twice (P2.2c, P2.3) and diagnosed it by hand both
        // times.
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), "{\n"
                + "  \"system\": \"etcd\",\n  \"scenario\": \"baseline\",\n"
                + "  \"started_at\": \"2026-08-03T14:00:00Z\",\n  \"rate_ops_s\": 100,\n"
                + "  \"warmup_secs\": 2,\n  \"duration_secs\": 6,\n  \"window\": 64,\n"
                + "  \"error_rate\": 0.0\n}\n");
        StringBuilder tp = new StringBuilder();
        for (int t = 0; t < 6; t++) tp.append(t).append(",100\n");
        Files.writeString(dir.resolve("throughput.csv"), tp.toString());
        metric(dir, "harness_inflight", "1000,loadgen,20\n1005,loadgen,63\n");

        assertEquals(State.FAIL, stateOf(ValidityChecker.check(dir), "window_headroom"),
                "63 of 64 is 98% occupancy — window-bound, not system-bound");
    }

    @Test
    void aRunWithHeadroomPassesAndSaturationSkips(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), "{\n"
                + "  \"system\": \"etcd\",\n  \"scenario\": \"baseline\",\n"
                + "  \"started_at\": \"2026-08-03T14:00:00Z\",\n  \"rate_ops_s\": 100,\n"
                + "  \"warmup_secs\": 2,\n  \"duration_secs\": 6,\n  \"window\": 64,\n"
                + "  \"error_rate\": 0.0\n}\n");
        StringBuilder tp = new StringBuilder();
        for (int t = 0; t < 6; t++) tp.append(t).append(",100\n");
        Files.writeString(dir.resolve("throughput.csv"), tp.toString());
        metric(dir, "harness_inflight", "1000,loadgen,8\n1005,loadgen,12\n");
        assertEquals(State.PASS, stateOf(ValidityChecker.check(dir), "window_headroom"));

        // Saturation PINS the window BY METHOD (Paxi's procedure) — gating it
        // there would fail every run that did what it was told.
        baseline(dir, 0, new int[]{500, 500, 500, 500, 500, 500});
        metric(dir, "harness_inflight", "1000,loadgen,64\n");
        assertEquals(State.SKIP, stateOf(ValidityChecker.check(dir), "window_headroom"));
    }

    // ---- S3.4/P4.5: the docker-events audit closes two gates ----

    private static void events(Path dir, String lines) throws IOException {
        Path e = dir.resolve("events");
        Files.createDirectories(e);
        Files.writeString(e.resolve("10.0.0.11.txt"), lines);
    }

    @Test
    void aBaselineThatKilledAContainerBrokeStationarity(@TempDir Path dir) throws IOException {
        baseline(dir, 100, new int[]{100, 100, 100, 100, 100, 100});
        events(dir, "1723600000 thesis-etcd1 die\n");
        assertEquals(State.FAIL, stateOf(ValidityChecker.check(dir), "container_restarts"),
                "a baseline measured a cluster that changed under it");
    }

    @Test
    void aQuietBaselinePassesTheRestartGate(@TempDir Path dir) throws IOException {
        baseline(dir, 100, new int[]{100, 100, 100, 100, 100, 100});
        events(dir, "1723600000 thesis-etcd1 health_status\n");
        assertEquals(State.PASS, stateOf(ValidityChecker.check(dir), "container_restarts"));
    }

    @Test
    void aFaultRunIsEXPECTEDToShowAKillAndFailsWhenItDoesNot(@TempDir Path dir)
            throws IOException {
        // The reason this gate could not simply count events: a fault run's
        // own kill is BY DESIGN, so counting would fail exactly the runs the
        // campaign exists to produce. Read against the scenario, the same
        // audit becomes evidence instead of an alarm.
        runWithErrorRate(dir, "leader_kill", 0.0, "3000");
        events(dir, "1723600000 thesis-etcd2 die\n");
        assertEquals(State.PASS, stateOf(ValidityChecker.check(dir), "container_restarts"));

        events(dir, "1723600000 thesis-etcd2 health_status\n");
        assertEquals(State.FAIL, stateOf(ValidityChecker.check(dir), "container_restarts"),
                "no container died in a kill run — the fault did not land");
    }

    @Test
    void paxiAndHotstuffFinallyHaveAKillWitness(@TempDir Path dir) throws IOException {
        // F41 deferred this to P4.5. These two expose NO server metrics at
        // all, so the events audit is not a fallback for them — it is the
        // only possible ground truth, and for a kill it is a better one than
        // any gauge because it observes the process dying rather than
        // inferring it from a counter.
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), "{\n"
                + "  \"system\": \"paxos\",\n  \"scenario\": \"leader_kill\",\n"
                + "  \"started_at\": \"2026-08-03T14:00:00Z\",\n  \"rate_ops_s\": 100,\n"
                + "  \"warmup_secs\": 2,\n  \"duration_secs\": 6,\n  \"window\": 64,\n"
                + "  \"error_rate\": 0.0,\n  \"fault_injected_at_ms\": 3000\n}\n");
        StringBuilder tp = new StringBuilder();
        for (int t = 0; t < 6; t++) tp.append(t).append(",100\n");
        Files.writeString(dir.resolve("throughput.csv"), tp.toString());

        events(dir, "1723600000 thesis-paxi2 die\n");
        assertEquals(State.PASS, stateOf(ValidityChecker.check(dir), "fault_ground_truth"),
                "the audit IS paxi's ground truth — it has nothing else");

        events(dir, "1723600000 thesis-paxi2 health_status\n");
        assertEquals(State.FAIL, stateOf(ValidityChecker.check(dir), "fault_ground_truth"),
                "nothing died, so the fault did not land where the run claims");
    }
}
