package gr.thesis.bench.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for the engine (P1.5): pin the open-loop schedule,
 * the coordinated-omission correction, the drain barrier, per-second buckets,
 * warmup flagging, and the fail-not-fabricate error path BEFORE P1.1/P1.2
 * modify the engine.
 *
 * Timing tolerances are deliberately generous (laptop-grade): every assertion
 * discriminates correct from broken behavior by an order of magnitude, not by
 * a few percent — these tests must never be flaky.
 */
class WorkloadEngineTest {

    @Test
    void openLoopAchievesTargetRate() throws Exception {
        var driver = FakeDriver.responding(1);
        var cfg = new WorkloadEngine.Config(2, 0, 200, 64, 8, 0.0); // 2 s @ 200 ops/s
        var rec = new LatencyRecorder();
        var r = new WorkloadEngine(driver, cfg, rec).run();

        assertEquals(0, r.errors());
        long issued = driver.issued();
        assertTrue(issued >= 320 && issued <= 410, "expected ~400 issued, got " + issued);
    }

    @Test
    void drainAccountsForEveryIssuedOp() throws Exception {
        // Service time 200 ms means ops issued just before the 1 s deadline
        // are still in flight when the load loop ends. run() must not return
        // until every one of them is completed AND recorded: the Result must
        // account for exactly what was issued — no lost samples at drain.
        var driver = FakeDriver.responding(200);
        var cfg = new WorkloadEngine.Config(1, 0, 100, 256, 8, 0.0);
        var rec = new LatencyRecorder();
        var r = new WorkloadEngine(driver, cfg, rec).run();

        assertEquals(0, r.errors());
        assertEquals(driver.issued(), rec.countAfterWarmup(),
                "every issued op must be recorded by the time run() returns");
        assertEquals(driver.issued(), Arrays.stream(r.committedPerSecond()).sum(),
                "per-second buckets must also be complete at drain");
    }

    @Test
    void lateCompletionsUpToTheDriverBoundStayInTheBuckets() throws Exception {
        // The ConsensusDriver contract bounds completion at 5 s. An op issued
        // in the run's last scheduling instant can therefore complete up to
        // ~duration+5 s later — and timeout-callback slop can tip it into
        // second duration+5. The bucket array needs headroom for that second,
        // or the commit silently vanishes from throughput.csv while the
        // histogram keeps it (2026-07-16 review finding F23: the array was
        // sized duration+5, making the boundary second exactly out of range).
        var driver = FakeDriver.responding(5_300); // 5 s bound + realistic slop
        var cfg = new WorkloadEngine.Config(1, 0, 10, 64, 8, 0.0);
        var rec = new LatencyRecorder();
        var r = new WorkloadEngine(driver, cfg, rec).run();

        assertEquals(0, r.errors());
        assertEquals(driver.issued(), rec.countAfterWarmup(), "drain waits for every op");
        assertEquals(rec.countAfterWarmup(), Arrays.stream(r.committedPerSecond()).sum(),
                "a commit the drain barrier waited for must never vanish from the buckets");
    }

    @Test
    void perSecondBucketsSumToRecordedCommits() throws Exception {
        var driver = FakeDriver.responding(1);
        var cfg = new WorkloadEngine.Config(2, 0, 100, 64, 8, 0.0);
        var rec = new LatencyRecorder();
        var r = new WorkloadEngine(driver, cfg, rec).run();

        assertEquals(rec.countAfterWarmup(), Arrays.stream(r.committedPerSecond()).sum(),
                "warmup=0: histogram count and throughput buckets see the same ops");
    }

    @Test
    void warmupSamplesAreFlaggedOutOfTheMeasurementWindow() throws Exception {
        var driver = FakeDriver.responding(1);
        var cfg = new WorkloadEngine.Config(2, 1, 100, 64, 8, 0.0); // 1 s of 2 s is warmup
        var rec = new LatencyRecorder();
        var r = new WorkloadEngine(driver, cfg, rec).run();

        long total = Arrays.stream(r.committedPerSecond()).sum();
        long measured = rec.countAfterWarmup();
        assertTrue(total >= 170 && total <= 210, "expected ~200 total commits, got " + total);
        assertTrue(measured >= 80 && measured <= 130,
                "expected ~100 post-warmup commits, got " + measured);
    }

    @Test
    void stallLatencyIsChargedAgainstIntendedTime() throws Exception {
        // 3 s run @ 100 ops/s; the driver holds every completion arriving in
        // [1.0 s, 2.0 s) until 2.0 s — a 1 s leader-election-like pause. With
        // coordinated-omission correction, the ~100 ops SCHEDULED during the
        // stall are each charged their queueing delay, so the recorded tail
        // spreads up to ~1 s. A CO-blind (send-time) measurement would show
        // at most one slow op — that is the difference these three
        // percentile assertions pin.
        var driver = FakeDriver.stalling(1, 1_000, 2_000);
        var cfg = new WorkloadEngine.Config(3, 0, 100, 1024, 8, 0.0); // window >> held ops
        var rec = new LatencyRecorder();
        var r = new WorkloadEngine(driver, cfg, rec).run();

        assertEquals(0, r.errors());
        long p50 = rec.percentileMicros(50);
        long p90 = rec.percentileMicros(90);
        long max = rec.percentileMicros(100);
        assertTrue(p50 < 100_000, "most ops unaffected: p50 stays fast, got " + p50 + "us");
        assertTrue(p90 >= 300_000, "the charged queueing delay must dominate p90, got " + p90 + "us");
        assertTrue(max >= 800_000, "the op at stall start is charged ~the full stall, got " + max + "us");
    }

    @Test
    void keySpaceIsBoundedAndReused() throws Exception {
        // The workload model is Paxi's: K=1000 REUSED keys. A keyspace that
        // grows with the op count means zero contention forever and no
        // conflict knob (D7) can ever fire — the exact bug this test pins.
        var driver = FakeDriver.responding(0);
        var cfg = new WorkloadEngine.Config(1, 0, 0, 8, 8, 0.0); // 1 s saturation
        var r = new WorkloadEngine(driver, cfg, new LatencyRecorder()).run();

        assertEquals(0, r.errors());
        assertTrue(driver.issued() >= 10_000,
                "need enough ops to exercise the keyspace, got " + driver.issued());
        assertTrue(driver.distinctKeys() <= 1_000,
                "K=1000 REUSED keys expected, got " + driver.distinctKeys()
                        + " distinct over " + driver.issued() + " ops");
        // Uniform draw over 10k+ ops covers essentially all 1000 buckets
        // (expected misses ≈ 1000·e^(-n/1000) < 0.1) — 950 is ultra-safe.
        assertTrue(driver.distinctKeys() >= 950,
                "uniform coverage of the keyspace expected, got " + driver.distinctKeys());
    }

    @Test
    void conflictFractionMatchesConfigured() throws Exception {
        // D7: fraction c of ops must hit the designated conflict key (id 0).
        // Statistical tolerance, sized to never flake: at n >= 10^4 the
        // binomial std of the realized fraction is sqrt(c(1-c)/n) <= 0.003,
        // so +-0.015 is a >=5-sigma band for both sweep points tested.
        for (double c : new double[]{0.02, 0.10}) {
            var driver = FakeDriver.responding(0);
            var cfg = new WorkloadEngine.Config(1, 0, 0, 8, 8, c); // 1 s saturation
            var r = new WorkloadEngine(driver, cfg, new LatencyRecorder()).run();

            assertEquals(0, r.errors());
            long n = driver.issued();
            assertTrue(n >= 10_000, "need a large sample, got " + n);
            double realized = driver.countFor(0) / (double) n;
            assertTrue(Math.abs(realized - c) <= 0.015,
                    "configured c=" + c + " but realized " + realized + " over " + n + " ops");
        }
    }

    @Test
    void zeroConflictNeverTouchesTheConflictKey() throws Exception {
        // The conflict key is EXCLUSIVE to conflict traffic: if uniform
        // traffic could land on key 0, the realized conflict fraction would
        // be biased by (1-c)/K and c=0 would not mean zero contention.
        var driver = FakeDriver.responding(0);
        var cfg = new WorkloadEngine.Config(1, 0, 0, 8, 8, 0.0);
        new WorkloadEngine(driver, cfg, new LatencyRecorder()).run();

        assertTrue(driver.issued() >= 10_000);
        assertEquals(0, driver.countFor(0),
                "c=0 must mean ZERO writes to the conflict key");
    }

    @Test
    void invalidConflictRatioFailsClosedAtConstruction() {
        for (double bad : new double[]{-0.01, 1.01, Double.NaN}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new WorkloadEngine.Config(1, 0, 0, 8, 8, bad),
                    "conflictRatio=" + bad + " must be rejected");
        }
    }

    @Test
    void eventsAreOffUnlessAnEventLogIsSupplied() throws Exception {
        // Baseline / rate-sweep runs never pay for event recording: the
        // 3-arg constructor keeps the hot path event-free.
        var driver = FakeDriver.responding(1);
        var cfg = new WorkloadEngine.Config(1, 0, 100, 64, 8, 0.0);
        var r = new WorkloadEngine(driver, cfg, new LatencyRecorder()).run();
        assertTrue(r.events() == null, "no EventLog supplied -> none recorded");
    }

    @Test
    void everyCompletionIsAnEventWhenEnabled() throws Exception {
        var driver = FakeDriver.responding(1);
        var cfg = new WorkloadEngine.Config(1, 0, 100, 64, 8, 0.0);
        var events = new EventLog(10_000);
        var r = new WorkloadEngine(driver, cfg, new LatencyRecorder(), events).run();

        assertTrue(r.events() == events);
        assertEquals(driver.issued(), events.commitCount(),
                "every committed op must be a timestamped event (drain-guaranteed)");
        assertEquals(0, events.errorCount());
        assertEquals(0, events.dropped());
    }

    @Test
    void failoverGapIsRecoveredFromAScriptedStall() throws Exception {
        // The F4 measurement end-to-end: driver stalls in [1.0s, 2.0s);
        // the "fault" is marked from another thread mid-stall. Both the
        // fault mark and the completion events use System.nanoTime, so
        // expected = (recovery at ~2.0s) - (mark time), exact on one clock.
        var driver = FakeDriver.stalling(1, 1_000, 2_000);
        var cfg = new WorkloadEngine.Config(3, 0, 100, 1024, 8, 0.0);
        var events = new EventLog(10_000);
        var engine = new WorkloadEngine(driver, cfg, new LatencyRecorder(), events);

        var exec = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var running = exec.submit(() -> engine.run());
            Thread.sleep(1_400); // inside the stall window
            long markRelMs = events.faultInjectedNow();
            assertTrue(markRelMs > 1_000 && markRelMs < 2_000,
                    "mark must land inside the stall, got " + markRelMs + " ms");

            var r = running.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, r.errors());
            long expected = 2_000 - markRelMs; // recovery lifts at the 2.0 s mark
            long failover = events.failoverMillis().orElseThrow();
            assertTrue(Math.abs(failover - expected) < 150,
                    "failover " + failover + " ms should be ~" + expected + " ms");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void deadClusterProducesErrorEventsAndNoFailoverNumber() throws Exception {
        var driver = FakeDriver.failing();
        var cfg = new WorkloadEngine.Config(1, 0, 100, 64, 8, 0.0);
        var events = new EventLog(10_000);
        new WorkloadEngine(driver, cfg, new LatencyRecorder(), events).run();

        events.faultInjectedNow();
        assertEquals(0, events.commitCount());
        assertTrue(events.errorCount() > 0, "failures must be timestamped events too");
        assertTrue(events.failoverMillis().isEmpty(),
                "no recovery -> no fabricated failover number");
    }

    @Test
    void littlesLawSelfConsistency() throws Exception {
        // M0 found this corroboration by luck; here it becomes a permanent
        // self-test. In saturation the window is always full, so Little's
        // Law fixes the relationship  L = λ·W  between three INDEPENDENTLY
        // measured quantities: window (config), throughput (commit buckets),
        // mean latency (histogram). If the engine's window accounting or the
        // recorder's mean drifts, this equality breaks — a whole-instrument
        // consistency check, not a unit check.
        final int window = 32;
        final long serviceMillis = 50;
        var driver = FakeDriver.responding(serviceMillis);
        var cfg = new WorkloadEngine.Config(3, 1, 0, window, 8, 0.0); // saturation
        var rec = new LatencyRecorder();
        var r = new WorkloadEngine(driver, cfg, rec).run();

        assertEquals(0, r.errors());
        // throughput over the measurement window (skip warmup + drain tail)
        long committed = 0;
        int secs = 0;
        for (int t = 1; t < 3; t++) { committed += r.committedPerSecond()[t]; secs++; }
        double throughput = committed / (double) secs;           // ops/s
        double meanSecs = rec.meanMicros() / 1_000_000.0;        // s
        double predictedWindow = throughput * meanSecs;          // Little's Law

        assertTrue(throughput > 0, "saturation must commit");
        assertTrue(Math.abs(predictedWindow - window) / window < 0.25,
                "Little's Law: throughput*mean=" + predictedWindow
                        + " should approximate window=" + window);
    }

    @Test
    void deadClusterYieldsErrorsNotFabricatedData() throws Exception {
        var driver = FakeDriver.failing();
        var cfg = new WorkloadEngine.Config(1, 0, 100, 64, 8, 0.0);
        var rec = new LatencyRecorder();
        var r = new WorkloadEngine(driver, cfg, rec).run();

        assertTrue(r.errors() > 0, "a dead cluster must surface as errors");
        assertEquals(driver.issued(), r.errors(), "every issued op fails");
        assertEquals(0, rec.countAfterWarmup(), "no fabricated latency samples");
        assertEquals(0, Arrays.stream(r.committedPerSecond()).sum(), "no fabricated throughput");
    }

    @Test
    void firstErrorCauseIsSurfacedNotSwallowed() throws Exception {
        // An error COUNT with no cause is undebuggable — proven in the field:
        // a 551-error Kafka saturation run gave zero clue why. The engine
        // must keep the first failure's exception for the run report.
        var driver = FakeDriver.failing();
        var cfg = new WorkloadEngine.Config(1, 0, 100, 64, 8, 0.0);
        var r = new WorkloadEngine(driver, cfg, new LatencyRecorder()).run();

        assertTrue(r.errors() > 0);
        assertTrue(r.firstError() != null, "errors without a cause are undebuggable");
        assertTrue(String.valueOf(r.firstError()).contains("cluster down"),
                "the surfaced cause must be the driver's real exception, got " + r.firstError());
    }

    @Test
    void cleanRunHasNoFirstError() throws Exception {
        var driver = FakeDriver.responding(1);
        var cfg = new WorkloadEngine.Config(1, 0, 100, 64, 8, 0.0);
        var r = new WorkloadEngine(driver, cfg, new LatencyRecorder()).run();
        assertEquals(0, r.errors());
        assertTrue(r.firstError() == null, "no errors -> no fabricated cause");
    }
}
