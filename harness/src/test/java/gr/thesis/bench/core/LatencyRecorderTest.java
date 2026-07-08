package gr.thesis.bench.core;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.3: the recorder is now real HdrHistogram (3 significant digits — every
 * value is within 0.1% of what was recorded; the pure-JDK stand-in was ~3%).
 * These tests pin the properties the thesis statistics depend on:
 *  - deterministic, tight percentiles on a known sample set;
 *  - a TRUE mean (the old avg=p50 placeholder actively hid skew — mean is
 *    the one moment tail events move);
 *  - a full post-warmup histogram snapshot, because methodology §3 pools
 *    per-run histograms (never averages percentiles) — impossible from
 *    point percentiles.
 */
class LatencyRecorderTest {

    /** 3-significant-digit precision: value within 0.1% + 1 unit. */
    private static void assertWithinPrecision(long expected, long actual, String what) {
        double tol = expected / 1000.0 + 1;
        assertTrue(Math.abs(actual - expected) <= tol,
                what + ": expected ~" + expected + ", got " + actual);
    }

    @Test
    void percentilesAreExactOnAKnownSampleSet() {
        var rec = new LatencyRecorder();
        for (long v = 1; v <= 10_000; v++) rec.record(v, true); // uniform 1..10000 us
        assertEquals(10_000, rec.countAfterWarmup());
        assertWithinPrecision(5_000, rec.percentileMicros(50), "p50");
        assertWithinPrecision(9_500, rec.percentileMicros(95), "p95");
        assertWithinPrecision(9_900, rec.percentileMicros(99), "p99");
        assertWithinPrecision(10_000, rec.percentileMicros(100), "max");
    }

    @Test
    void meanIsTrueMeanNotMedian() {
        // 999 fast ops + 1 catastrophic one: the median ignores the outlier,
        // the mean must not. true mean = (999*100 + 1_000_000)/1000 = 1099.9
        var rec = new LatencyRecorder();
        for (int i = 0; i < 999; i++) rec.record(100, true);
        rec.record(1_000_000, true);

        assertWithinPrecision(100, rec.percentileMicros(50), "p50");
        double mean = rec.meanMicros();
        assertTrue(Math.abs(mean - 1_099.9) < 15, "true mean expected ~1099.9, got " + mean);
        assertTrue(mean > 5 * rec.percentileMicros(50), "mean must expose the skew p50 hides");
    }

    @Test
    void warmupSamplesStayOutOfEveryStatistic() {
        var rec = new LatencyRecorder();
        rec.record(1_000_000, false); // warmup outlier
        for (int i = 0; i < 100; i++) rec.record(200, true);

        assertEquals(100, rec.countAfterWarmup());
        assertWithinPrecision(200, rec.percentileMicros(100), "max excludes warmup");
        assertTrue(rec.meanMicros() < 250, "mean excludes warmup, got " + rec.meanMicros());
    }

    @Test
    void snapshotMergingEqualsPoolingTheRawSamples() {
        // The methodology's pooling path: per-run histograms are MERGED, and
        // the pooled distribution must equal a histogram of all raw samples.
        var runA = new LatencyRecorder();
        var runB = new LatencyRecorder();
        var truth = new LatencyRecorder();
        for (long v = 1; v <= 5_000; v++) { runA.record(v, true); truth.record(v, true); }
        for (long v = 100_000; v <= 104_999; v++) { runB.record(v, true); truth.record(v, true); }

        Histogram pooled = runA.warmSnapshot();
        pooled.add(runB.warmSnapshot());

        assertEquals(truth.countAfterWarmup(), pooled.getTotalCount());
        for (double p : new double[]{50, 95, 99, 99.9}) {
            assertWithinPrecision(truth.percentileMicros(p),
                    pooled.getValueAtPercentile(p), "pooled p" + p);
        }
    }
}
