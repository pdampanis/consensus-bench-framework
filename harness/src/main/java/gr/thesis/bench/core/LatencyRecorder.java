package gr.thesis.bench.core;

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

/**
 * Thread-safe latency recorder on real HdrHistogram (Gil Tene) — the
 * industry standard used by YCSB, Cassandra, and Kafka's own tooling.
 * Replaces the pure-JDK log-bucket stand-in (same API, ~3% error) that
 * carried the skeleton until Maven was verified; at 3 significant digits
 * every recorded value is within 0.1% of what was measured.
 *
 * Two histograms, matching what the analysis needs:
 *   all  - every sample, kept for debugging warmup behavior;
 *   warm - post-warmup only. Every reported statistic and the persisted
 *          .hlog come from THIS one — the measurement window is the only
 *          window that produces thesis figures.
 *
 * ConcurrentHistogram: wait-free recording from the driver completion
 * threads (the per-op hot path), no locks, no allocation per record.
 * Auto-resizing, because CO-corrected latencies during a long stall can
 * reach tens of seconds and a fixed ceiling would throw mid-run — losing
 * the exact samples a failover study exists to capture.
 */
public final class LatencyRecorder {

    private static final int SIGNIFICANT_DIGITS = 3;

    private final ConcurrentHistogram all  = new ConcurrentHistogram(SIGNIFICANT_DIGITS);
    private final ConcurrentHistogram warm = new ConcurrentHistogram(SIGNIFICANT_DIGITS);

    public void record(long micros, boolean afterWarmup) {
        long v = Math.max(1, micros); // clamp sub-us artifacts of clock math
        all.recordValue(v);
        if (afterWarmup) warm.recordValue(v);
    }

    /** Percentile over post-warmup samples only (the measurement window).
     *  p in [0,100]; p=100 is the exact observed maximum. */
    public long percentileMicros(double p) {
        return warm.getValueAtPercentile(p);
    }

    /** TRUE arithmetic mean over post-warmup samples — the one statistic
     *  tail events always move (replaces the old avg=p50 placeholder). */
    public double meanMicros() {
        return warm.getMean();
    }

    public long countAfterWarmup() {
        return warm.getTotalCount();
    }

    /** Copy of the post-warmup histogram — the input to methodology §3's
     *  pooling (per-run histograms are MERGED via Histogram.add, never
     *  percentile-averaged) and to the persisted latency.hlog. A copy, so
     *  no caller can mutate the recorder's live state. */
    public Histogram warmSnapshot() {
        return warm.copy();
    }
}
