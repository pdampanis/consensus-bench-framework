package gr.thesis.bench.results;

import gr.thesis.bench.core.EventLog;
import gr.thesis.bench.core.LatencyRecorder;
import gr.thesis.bench.core.Scenario;
import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.core.WorkloadEngine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the results contract:
 *  - P0.4: every run-window second written zeros included; error_rate;
 *    honest status.
 *  - P1.2: conflict runs path-separated + conflict_ratio field.
 *  - P1.3: true mean; latency.hlog round-trip.
 *  - P1.6: the manifest pins what methodology §1 SAYS it pins — load
 *    params, environment (local runs are never thesis data), image ref,
 *    harness version, a deterministic config hash — plus P1.4's fault
 *    timestamp and failover result.
 */
class CsvResultsWriterTest {

    @TempDir
    Path root;

    private static final CsvResultsWriter.RunIdentity ID =
            new CsvResultsWriter.RunIdentity(SystemUnderTest.ETCD, Scenario.BASELINE, 1, 0.0, "t1");
    private static final CsvResultsWriter.RunIdentity FAULT_ID =
            new CsvResultsWriter.RunIdentity(SystemUnderTest.ETCD, Scenario.LEADER_KILL, 3, 0.0, "t1");
    private static final WorkloadEngine.Config CFG =
            new WorkloadEngine.Config(10, 2, 100, 64, 256, 0.0);
    private static final String IMG = "quay.io/coreos/etcd@sha256:5a65b4c6test";

    private void writeRun(CsvResultsWriter.RunIdentity id, WorkloadEngine.Result r,
                          WorkloadEngine.Config cfg) throws IOException {
        new CsvResultsWriter().write(root, id, r, cfg, "local", IMG,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(cfg.durationSecs()));
    }

    private static WorkloadEngine.Result result(long[] perSecond, long errors, int warmSamples) {
        var rec = new LatencyRecorder();
        for (int i = 0; i < warmSamples; i++) rec.record(1_000, true);
        return new WorkloadEngine.Result(perSecond, rec, errors);
    }

    private static long[] tenGoodSeconds() {
        long[] ps = new long[15];
        Arrays.fill(ps, 0, 10, 10L);
        return ps;
    }

    private String manifest(CsvResultsWriter.RunIdentity id) throws IOException {
        return Files.readString(id.dir(root).resolve("manifest.json"));
    }

    // ---- P0.4 ----

    @Test
    void everyRunSecondIsWrittenZerosIncluded() throws IOException {
        var cfg = new WorkloadEngine.Config(400, 5, 100, 64, 256, 0.0);
        long[] ps = new long[cfg.durationSecs() + 5];
        Arrays.fill(ps, 0, cfg.durationSecs(), 7L);
        ps[310] = 0;                        // stall second beyond the old warmup+300 cutoff
        ps[cfg.durationSecs() + 2] = 3;     // a late drain-buffer completion

        writeRun(ID, result(ps, 0, 100), cfg);
        Map<Integer, Long> rows = new HashMap<>();
        for (String line : Files.readAllLines(ID.dir(root).resolve("throughput.csv"))) {
            if (line.isBlank()) continue;
            String[] p = line.split(",");
            rows.put(Integer.parseInt(p[0]), Long.parseLong(p[1]));
        }

        assertEquals(0L, rows.get(310), "zero-commit second inside the run must be present");
        for (int t = 0; t < cfg.durationSecs(); t++) {
            assertTrue(rows.containsKey(t), "missing run-window second t=" + t);
        }
        assertEquals(3L, rows.get(cfg.durationSecs() + 2), "nonzero drain-buffer second kept");
        assertFalse(rows.containsKey(cfg.durationSecs() + 4), "zero drain-buffer second dropped");
    }

    @Test
    void manifestOfCleanRunIsCompleteWithZeroErrorRate() throws IOException {
        writeRun(ID, result(tenGoodSeconds(), 0, 100), CFG);
        assertTrue(manifest(ID).contains("\"status\": \"complete\""));
        assertTrue(manifest(ID).contains("\"error_rate\": 0.0000"));
    }

    @Test
    void majorityErrorRunMayNotClaimComplete() throws IOException {
        writeRun(ID, result(tenGoodSeconds(), 900, 100), CFG); // 100 commits vs 900 errors
        assertTrue(manifest(ID).contains("\"error_rate\": 0.9000"));
        assertTrue(manifest(ID).contains("\"status\": \"failed\""),
                "90% errors is a failed run, not a complete one");
    }

    @Test
    void zeroOpsRunIsFailed() throws IOException {
        writeRun(ID, result(new long[15], 0, 0), CFG);
        assertTrue(manifest(ID).contains("\"status\": \"failed\""));
    }

    // ---- P1.2 ----

    @Test
    void conflictRunGetsItsOwnPathSegmentAndManifestField() throws IOException {
        var id = new CsvResultsWriter.RunIdentity(
                SystemUnderTest.PAXOS, Scenario.BASELINE, 3, 0.10, "t1");
        assertTrue(id.dir(root).toString().endsWith("paxos/baseline/size3/c10/t1"),
                "conflict runs must be path-separated: " + id.dir(root));

        writeRun(id, result(tenGoodSeconds(), 0, 100), CFG);
        assertTrue(manifest(id).contains("\"conflict_ratio\": 0.10"));
    }

    @Test
    void zeroConflictKeepsTheLegacyPathShape() {
        assertTrue(ID.dir(root).toString().endsWith("etcd/baseline/size1/t1"),
                "unexpected path: " + ID.dir(root));
    }

    @Test
    void nonWholePercentConflictRatioFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new CsvResultsWriter.RunIdentity(
                        SystemUnderTest.PAXOS, Scenario.BASELINE, 3, 0.025, "t1"));
    }

    // ---- P1.3 ----

    @Test
    void latencyCsvCarriesTrueMeanNotTheP50Placeholder() throws IOException {
        var rec = new LatencyRecorder();
        for (int i = 0; i < 999; i++) rec.record(100, true);
        rec.record(1_000_000, true);

        writeRun(ID, new WorkloadEngine.Result(tenGoodSeconds(), rec, 0), CFG);
        String latency = Files.readString(ID.dir(root).resolve("latency.csv"));

        long avg = Long.parseLong(latency.lines()
                .filter(l -> l.startsWith("avg,")).findFirst().orElseThrow().split(",")[1]);
        long p50 = Long.parseLong(latency.lines()
                .filter(l -> l.startsWith("p50,")).findFirst().orElseThrow().split(",")[1]);
        assertTrue(Math.abs(avg - 1_100) < 20, "avg must be the true mean, got " + avg);
        assertTrue(avg > 5 * p50, "avg==p50 placeholder is back: avg=" + avg + " p50=" + p50);
    }

    @Test
    void fullHistogramIsPersistedAndReadsBack() throws IOException {
        var rec = new LatencyRecorder();
        for (long v = 1; v <= 1_000; v++) rec.record(v, true);

        writeRun(ID, new WorkloadEngine.Result(tenGoodSeconds(), rec, 0), CFG);

        var hlog = ID.dir(root).resolve("latency.hlog");
        assertTrue(Files.exists(hlog), "latency.hlog must be written beside the CSVs");
        try (var reader = new org.HdrHistogram.HistogramLogReader(hlog.toFile())) {
            var h = (org.HdrHistogram.Histogram) reader.nextIntervalHistogram();
            assertEquals(1_000, h.getTotalCount(), "re-read histogram carries every sample");
            assertTrue(Math.abs(h.getValueAtPercentile(50) - 500) <= 2,
                    "re-read p50 ~500, got " + h.getValueAtPercentile(50));
        }
    }

    // ---- P1.6: the manifest pins what the methodology claims ----

    @Test
    void manifestPinsLoadParamsEnvironmentImageAndVersion() throws IOException {
        writeRun(ID, result(tenGoodSeconds(), 0, 100), CFG);
        String m = manifest(ID);
        assertTrue(m.contains("\"environment\": \"local\""), m);
        assertTrue(m.contains("\"image\": \"" + IMG + "\""), m);
        assertTrue(m.contains("\"harness_version\": "), m);
        assertTrue(m.contains("\"rate_ops_s\": 100"), m);
        assertTrue(m.contains("\"window\": 64"), m);
        assertTrue(m.contains("\"value_size_bytes\": 256"), m);
        assertTrue(m.contains("\"duration_secs\": 10"), m);
        assertTrue(m.contains("\"warmup_secs\": 2"), m);
    }

    @Test
    void configHashIsDeterministicAndSensitiveToEveryParam() throws IOException {
        Pattern hash = Pattern.compile("\"config_hash\": \"([0-9a-f]{12})\"");

        writeRun(ID, result(tenGoodSeconds(), 0, 100), CFG);
        Matcher m1 = hash.matcher(manifest(ID));
        assertTrue(m1.find(), "config_hash missing or malformed");

        writeRun(ID, result(tenGoodSeconds(), 0, 100), CFG); // same inputs again
        Matcher m2 = hash.matcher(manifest(ID));
        assertTrue(m2.find());
        assertEquals(m1.group(1), m2.group(1), "hash must be deterministic");

        var fasterCfg = new WorkloadEngine.Config(10, 2, 200, 64, 256, 0.0); // rate differs
        writeRun(ID, result(tenGoodSeconds(), 0, 100), fasterCfg);
        Matcher m3 = hash.matcher(manifest(ID));
        assertTrue(m3.find());
        assertNotEquals(m1.group(1), m3.group(1), "a changed param must change the hash");
    }

    @Test
    void faultFieldsAreNullForBaselineAndRealForFaultRuns() throws IOException {
        // Baseline: no events -> explicit nulls (absent number, not zero).
        writeRun(ID, result(tenGoodSeconds(), 0, 100), CFG);
        assertTrue(manifest(ID).contains("\"fault_injected_at_ms\": null"));
        assertTrue(manifest(ID).contains("\"failover_ms\": null"));

        // Fault run: EventLog carries mark + recovery -> real numbers.
        var events = new EventLog(16);
        events.start(0);
        events.append(10_000_000L, true);
        events.faultInjectedAt(25_000_000L);           // +25 ms
        events.append(95_000_000L, true);              // recovery at +95 ms
        var rec = new LatencyRecorder();
        for (int i = 0; i < 100; i++) rec.record(1_000, true);
        writeRun(ID, new WorkloadEngine.Result(tenGoodSeconds(), rec, 0, events), CFG);

        assertTrue(manifest(ID).contains("\"fault_injected_at_ms\": 25"), manifest(ID));
        assertTrue(manifest(ID).contains("\"failover_ms\": 70"), manifest(ID));
    }

    @Test
    void unrecoveredFaultRunHasMarkButNullFailover() throws IOException {
        var events = new EventLog(16);
        events.start(0);
        events.append(10_000_000L, true);
        events.faultInjectedAt(25_000_000L);
        // no commit after the fault — the cluster never recovered
        var rec = new LatencyRecorder();
        for (int i = 0; i < 100; i++) rec.record(1_000, true);
        writeRun(ID, new WorkloadEngine.Result(tenGoodSeconds(), rec, 50, events), CFG);

        assertTrue(manifest(ID).contains("\"fault_injected_at_ms\": 25"), manifest(ID));
        assertTrue(manifest(ID).contains("\"failover_ms\": null"),
                "no recovery -> null, never a fabricated number: " + manifest(ID));
    }

    // ---- F50: a fault run whose fault never fired is not a fault result ----
    // The mark is stamped only after FaultInjector.apply() RETURNS, so
    // "mark present" <=> "the fault demonstrably fired". A fault-scenario run
    // that cannot show a mark is void as that scenario, however clean its
    // measurement looks — and the manifest is where every downstream layer
    // (campaign resume, ValidityChecker, analyse.py) learns that.

    @Test
    void faultScenarioWithoutAMarkMayNotClaimComplete() throws IOException {
        // The engine started the log; the fault thread never stamped it
        // (injection threw, or stalled past the runner's join). The
        // measurement itself is pristine — which is precisely why an honest
        // status is the only thing standing between it and the leader_kill
        // figures.
        var events = new EventLog(16);
        events.start(0);
        events.append(10_000_000L, true);
        var rec = new LatencyRecorder();
        for (int i = 0; i < 100; i++) rec.record(1_000, true);
        writeRun(FAULT_ID, new WorkloadEngine.Result(tenGoodSeconds(), rec, 0, events), CFG);

        assertTrue(manifest(FAULT_ID).contains("\"fault_injected_at_ms\": null"), manifest(FAULT_ID));
        assertTrue(manifest(FAULT_ID).contains("\"status\": \"failed\""),
                "an unmarked fault run must not claim complete: " + manifest(FAULT_ID));
    }

    @Test
    void faultScenarioWithNoEventLogAtAllIsAlsoFailed() throws IOException {
        // The baseline-shaped Result (no EventLog) can never evidence a fault.
        writeRun(FAULT_ID, result(tenGoodSeconds(), 0, 100), CFG);
        assertTrue(manifest(FAULT_ID).contains("\"status\": \"failed\""),
                "no event log means no fault evidence: " + manifest(FAULT_ID));
    }

    @Test
    void faultScenarioWithAMarkStillCompletes() throws IOException {
        // The guard against over-correcting: a fault that DID fire is a
        // perfectly good fault result.
        var events = new EventLog(16);
        events.start(0);
        events.append(10_000_000L, true);
        events.faultInjectedAt(25_000_000L);
        events.append(95_000_000L, true);
        var rec = new LatencyRecorder();
        for (int i = 0; i < 100; i++) rec.record(1_000, true);
        writeRun(FAULT_ID, new WorkloadEngine.Result(tenGoodSeconds(), rec, 0, events), CFG);

        assertTrue(manifest(FAULT_ID).contains("\"status\": \"complete\""),
                "a marked fault run is valid data: " + manifest(FAULT_ID));
    }

    // ---- F70: an EventLog that OVERFLOWED may not pose as a measurement ----
    // EventLog.append drops silently past capacity and only counts the drops;
    // RemoteRunner caps the buffer at 4,000,000 events, which a saturation run
    // exceeds. If the buffer fills before the fault mark, failoverMillis()
    // finds no qualifying commit and returns empty — so the run writes
    // "fault_injected_at_ms": <n> with "failover_ms": null, which is
    // INDISTINGUISHABLE from the honest "the system never recovered" (the
    // preregistered paxi wedge, F26). The distinguishing fact — that we lost
    // the record — was computed by EventLog.dropped() and then read by nobody.

    @Test
    void faultRunThatLostItsEventRecordMayNotClaimComplete() throws IOException {
        // Capacity 2, four appends: the log overflows BEFORE the mark, so the
        // recovery commit at 95 ms is dropped and never seen again.
        var events = new EventLog(2);
        events.start(0);
        events.append(1_000_000L, true);
        events.append(2_000_000L, true);
        events.append(3_000_000L, true);          // dropped
        events.faultInjectedAt(25_000_000L);
        events.append(95_000_000L, true);         // the recovery — dropped

        var rec = new LatencyRecorder();
        for (int i = 0; i < 100; i++) rec.record(1_000, true);
        writeRun(FAULT_ID, new WorkloadEngine.Result(tenGoodSeconds(), rec, 0, events), CFG);

        String m = manifest(FAULT_ID);
        assertTrue(m.contains("\"fault_injected_at_ms\": 25"), m);
        assertTrue(m.contains("\"failover_ms\": null"), m);
        assertTrue(m.contains("\"status\": \"failed\""),
                "a fault run whose event record overflowed cannot tell 'never recovered'"
                        + " from 'we lost the evidence' — it must not claim complete: " + m);
    }

    @Test
    void manifestCarriesTheDroppedEventCount() throws IOException {
        var events = new EventLog(2);
        events.start(0);
        events.append(1_000_000L, true);
        events.append(2_000_000L, true);
        events.append(3_000_000L, true);          // dropped
        events.faultInjectedAt(25_000_000L);

        var rec = new LatencyRecorder();
        for (int i = 0; i < 100; i++) rec.record(1_000, true);
        writeRun(FAULT_ID, new WorkloadEngine.Result(tenGoodSeconds(), rec, 0, events), CFG);

        assertTrue(manifest(FAULT_ID).contains("\"events_dropped\": 1"),
                "the drop count is the only thing that distinguishes a lost record from"
                        + " a real absence — it belongs in the manifest: " + manifest(FAULT_ID));
    }

    @Test
    void baselineRunReportsNullDroppedNotZero() throws IOException {
        // A run with no EventLog did not measure zero drops; it did not
        // measure drops at all. Absent != zero, as with the fault fields.
        writeRun(ID, result(tenGoodSeconds(), 0, 100), CFG);
        assertTrue(manifest(ID).contains("\"events_dropped\": null"), manifest(ID));
    }

    @Test
    void faultRunWithDropsButAResolvedFailoverStaysComplete() throws IOException {
        // The guard against over-correcting, and the reason the rule is not
        // simply "drops > 0 => failed": here the buffer filled AFTER the
        // recovery commit was already recorded, so the failover number is
        // real and the run is valid data. Discarding it would throw away a
        // good measurement to punish a late overflow.
        var events = new EventLog(4);
        events.start(0);
        events.append(1_000_000L, true);
        events.faultInjectedAt(25_000_000L);
        events.append(95_000_000L, true);         // recovery, captured
        events.append(96_000_000L, true);
        events.append(97_000_000L, true);
        events.append(98_000_000L, true);         // dropped

        var rec = new LatencyRecorder();
        for (int i = 0; i < 100; i++) rec.record(1_000, true);
        writeRun(FAULT_ID, new WorkloadEngine.Result(tenGoodSeconds(), rec, 0, events), CFG);

        String m = manifest(FAULT_ID);
        assertTrue(m.contains("\"failover_ms\": 70"), m);
        assertTrue(m.contains("\"events_dropped\": 1"), m);
        assertTrue(m.contains("\"status\": \"complete\""),
                "drops after a captured recovery do not void the measurement: " + m);
    }
}
