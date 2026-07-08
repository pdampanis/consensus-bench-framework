package gr.thesis.bench.core;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Timestamped completion events for fault runs (P1.4) — the instrument
 * behind F4 (failover ECDF): failover time is the gap from the injected
 * fault to the first subsequent successful commit, which needs per-event
 * timestamps, not the engine's 1-second buckets.
 *
 * Design, driven by the validity requirements:
 *  - OPT-IN: baseline/sweep runs never pay for it. The engine records
 *    events only when a log is supplied (same injection pattern as
 *    LatencyRecorder).
 *  - One clock domain: completion events AND the fault mark both come from
 *    System.nanoTime in this JVM, so the gap is exact — no cross-clock skew
 *    (chrony matters for Prometheus corroboration, not for this number).
 *  - Allocation-free hot path: a preallocated long[] with a lock-free
 *    claimed-slot append (getAndIncrement). One long per event: nanos since
 *    origin, errors stored as -(rel+1) so rel=0 stays unambiguous.
 *  - Fail loud on overflow: if a run outgrows the buffer, later events are
 *    COUNTED as dropped and the run continues — a dropped>0 failover number
 *    is evidence for the validity layer to reject, never a crash and never
 *    a silent truncation.
 *
 * Lifecycle: the campaign runner sizes the log (~rate x duration + window),
 * hands it to the engine, and marks the fault via faultInjectedNow() from
 * the injector thread while run() is in flight.
 */
public final class EventLog {

    private final long[] events;          // rel nanos; errors as -(rel+1)
    private final AtomicInteger next = new AtomicInteger();
    private final LongAdder dropped = new LongAdder();
    private volatile long originNanos;
    private volatile long faultRelNanos = Long.MIN_VALUE; // MIN_VALUE = no fault marked

    public EventLog(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.events = new long[capacity];
    }

    /** Engine calls once at run start; every timestamp is relative to this. */
    public void start(long originNanos) {
        this.originNanos = originNanos;
    }

    /** Hot path (driver completion threads): lock-free slot claim + store. */
    public void append(long absNanos, boolean ok) {
        int slot = next.getAndIncrement();
        if (slot >= events.length) {
            dropped.increment();
            return;
        }
        long rel = absNanos - originNanos;
        events[slot] = ok ? rel : -(rel + 1);
    }

    /** Injector thread marks the fault; returns ms since run start (handy
     *  for logs and for the manifest's fault timestamp). */
    public long faultInjectedNow() {
        return faultInjectedAt(System.nanoTime());
    }

    /** Same, with an explicit nanoTime — unit-testable without sleeping. */
    public long faultInjectedAt(long absNanos) {
        long rel = absNanos - originNanos;
        this.faultRelNanos = rel;
        return rel / 1_000_000L;
    }

    /**
     * Failover time: first successful commit AT OR AFTER the fault mark,
     * minus the mark, in milliseconds. Empty when no fault was marked or no
     * commit followed it — an absent number, never a fabricated one.
     * Linear scan (slots may be microscopically out of claim order across
     * threads, so min is taken over all qualifying commits); runs once at
     * results time, not on the hot path.
     */
    public OptionalLong failoverMillis() {
        if (faultRelNanos == Long.MIN_VALUE) return OptionalLong.empty();
        long first = Long.MAX_VALUE;
        int n = Math.min(next.get(), events.length);
        for (int i = 0; i < n; i++) {
            long e = events[i];
            if (e >= 0 && e >= faultRelNanos && e < first) first = e;
        }
        return first == Long.MAX_VALUE
                ? OptionalLong.empty()
                : OptionalLong.of((first - faultRelNanos) / 1_000_000L);
    }

    public long commitCount() {
        long c = 0;
        int n = Math.min(next.get(), events.length);
        for (int i = 0; i < n; i++) if (events[i] >= 0) c++;
        return c;
    }

    public long errorCount() {
        long c = 0;
        int n = Math.min(next.get(), events.length);
        for (int i = 0; i < n; i++) if (events[i] < 0) c++;
        return c;
    }

    /** Events lost to a full buffer — dropped > 0 must fail validity. */
    public long dropped() {
        return dropped.sum();
    }

    /** When the fault was marked, in ms since run start — empty if none.
     *  Goes into the manifest (P1.6) so gate 3 (fault ground truth) can
     *  align the Prometheus corroboration window on it. */
    public OptionalLong faultMarkMillis() {
        return faultRelNanos == Long.MIN_VALUE
                ? OptionalLong.empty()
                : OptionalLong.of(faultRelNanos / 1_000_000L);
    }
}
