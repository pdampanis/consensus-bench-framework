package gr.thesis.bench.core;

import gr.thesis.bench.driver.ConsensusDriver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Unified load generator: open-loop arrival schedule with a bounded
 * in-flight window, driven identically for every ConsensusDriver.
 *
 * Two modes, matching what the papers actually do:
 *  - targetRatePerSec > 0: open-loop at a fixed rate (the HotStuff paper's
 *    method: "varying the operation request rate until the system
 *    saturated"). Latency is recorded against the INTENDED send time,
 *    which corrects coordinated omission: if the system stalls during a
 *    leader election, the queueing delay is charged to the operations
 *    scheduled during the stall instead of silently not being sampled.
 *  - targetRatePerSec <= 0: saturation mode - submit as fast as the
 *    in-flight window allows (closed-loop with high concurrency), used to
 *    find max throughput as in Paxi ("increasing the concurrency level
 *    until the system is saturated").
 *
 * Throughput is counted per completed-second bucket (real per-second
 * series for every system - no more synthetic flat lines). Warmup-period
 * samples are recorded but flagged, so the results writer can discard
 * them exactly as analyse.py expects (t_off >= warmupSecs).
 */
public final class WorkloadEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkloadEngine.class);

    /**
     * @param conflictRatio D7 knob — fraction of ops routed to the designated
     *        conflict key (id 0, exclusive to conflict traffic). 0 disables.
     */
    public record Config(int durationSecs,
                         int warmupSecs,
                         long targetRatePerSec,
                         int maxInFlight,
                         int valueSizeBytes,
                         double conflictRatio) {
        public Config {
            // Fail closed at construction: an out-of-range or NaN ratio would
            // silently skew every number derived from the run. The negated
            // form (!(x >= 0 && x <= 1)) rejects NaN too.
            if (!(conflictRatio >= 0.0 && conflictRatio <= 1.0)) {
                throw new IllegalArgumentException(
                        "conflictRatio must be in [0,1], got " + conflictRatio);
            }
        }
    }

    private final ConsensusDriver driver;
    private final Config cfg;
    private final LatencyRecorder recorder;
    private final EventLog events; // null = event recording off (baseline runs)
    private final AtomicLongArray perSecondCommits;
    private final LongAdder errors = new LongAdder();
    /** Issued operations, for the M5.3 self-metrics. A LongAdder increment on
     *  the issue path costs a couple of nanoseconds against an operation that
     *  takes microseconds-to-milliseconds over a network — the same trade the
     *  error counter beside it already makes, and the alternative (deriving
     *  it) cannot see the warmup window the histogram excludes. */
    private final LongAdder submitted = new LongAdder();
    // First failure's cause — an error COUNT alone is undebuggable (learned
    // from a 551-error run that gave zero clue). One CAS attempt per error,
    // error path only; the success hot path is untouched.
    private final java.util.concurrent.atomic.AtomicReference<Throwable> firstError =
            new java.util.concurrent.atomic.AtomicReference<>();
    private volatile long startNanos;
    /** The live window, published for the M5.3 self-metrics gauge. Read by
     *  the scrape thread only; the per-op path never touches this reference,
     *  so instrumenting the instrument costs the measurement nothing. */
    private volatile Semaphore inFlightWindow;

    public WorkloadEngine(ConsensusDriver driver, Config cfg, LatencyRecorder recorder) {
        this(driver, cfg, recorder, null);
    }

    /** Fault-run variant: completions are additionally timestamped into
     *  {@code events} (P1.4) so failover gaps resolve sub-second. */
    public WorkloadEngine(ConsensusDriver driver, Config cfg, LatencyRecorder recorder,
                          EventLog events) {
        this.driver = driver;
        this.cfg = cfg;
        this.recorder = recorder;
        this.events = events;
        // Capacity must strictly EXCEED duration + the 5 s per-op completion
        // bound (ConsensusDriver.write contract): an op issued in the run's
        // last instant plus timeout-callback slop can complete in second
        // duration+5, and a commit the drain barrier waited for must never
        // vanish from the buckets (F23).
        this.perSecondCommits = new AtomicLongArray(cfg.durationSecs() + 6);
    }

    public Result run() throws Exception {
        log.debug("phase: connect ({} driver)", driver.system());
        driver.connect();
        final Semaphore inFlight = new Semaphore(cfg.maxInFlight());
        this.inFlightWindow = inFlight;
        final byte[] value = new byte[cfg.valueSizeBytes()];
        final long endNanos;
        startNanos = System.nanoTime();
        endNanos = startNanos + cfg.durationSecs() * 1_000_000_000L;
        if (events != null) events.start(startNanos); // one clock domain for events + fault mark

        final boolean openLoop = cfg.targetRatePerSec() > 0;
        final long interArrivalNanos =
                openLoop ? 1_000_000_000L / cfg.targetRatePerSec() : 0L;

        log.debug("phase: warmup start (mode={}, duration={}s, warmup={}s, window={})",
                openLoop ? cfg.targetRatePerSec() + " ops/s open-loop" : "saturation",
                cfg.durationSecs(), cfg.warmupSecs(), cfg.maxInFlight());
        final Thread reporter = startDebugReporter();

        long nextIntendedNanos = startNanos;

        try {
            while (System.nanoTime() < endNanos) {
                if (openLoop) {
                    // Open loop: wait until the schedule says this op departs.
                    long now = System.nanoTime();
                    if (now < nextIntendedNanos) {
                        java.util.concurrent.locks.LockSupport.parkNanos(nextIntendedNanos - now);
                    }
                }
                inFlight.acquire();                       // bounded window
                submitted.increment();
                final long intended = openLoop ? nextIntendedNanos : System.nanoTime();
                nextIntendedNanos += interArrivalNanos;

                driver.write(keyFor(cfg.conflictRatio()), value).whenComplete((v, err) -> {
                    final long completed = System.nanoTime();
                    try {
                        if (events != null) events.append(completed, err == null);
                        if (err != null) {
                            errors.increment();
                            firstError.compareAndSet(null, err);
                            return;
                        }
                        // Coordinated-omission-corrected latency: completion minus
                        // INTENDED start, not minus actual send.
                        final long latencyMicros = (completed - intended) / 1_000L;
                        final int second = (int) ((completed - startNanos) / 1_000_000_000L);
                        final boolean warm = second >= cfg.warmupSecs();
                        recorder.record(latencyMicros, warm);
                        if (second >= 0 && second < perSecondCommits.length()) {
                            perSecondCommits.incrementAndGet(second);
                        }
                    } finally {
                        // Released LAST: the drain barrier in run() acquires every
                        // permit, so releasing after recording guarantees the
                        // Result is complete — no samples lost in a race between
                        // the final callbacks and the snapshot.
                        inFlight.release();
                    }
                });
            }
            log.debug("phase: load end, draining in-flight window");
            // Drain the window before reporting.
            inFlight.acquire(cfg.maxInFlight());
            // …and hand the permits straight back. The barrier's job is done
            // the instant it completes, and HOLDING them would leave
            // availablePermits() at zero — which currentInFlight() reads as a
            // FULL window when in truth nothing is in flight at all. A
            // Prometheus scrape landing on the drain would then record a
            // spurious peak and FAIL window_headroom on a clean run (M5.3).
            inFlight.release(cfg.maxInFlight());
        } finally {
            if (reporter != null) reporter.interrupt();
        }
        log.debug("phase: run complete ({} committed after warmup, {} errors)",
                recorder.countAfterWarmup(), errors.sum());
        if (errors.sum() > 0) {
            // Never silent: the count goes in the results, the CAUSE goes in
            // the log — a run report saying "551 errors" with no exception
            // sends whoever reads it on a blind chase.
            log.warn("{} ops failed; first cause: {}", errors.sum(),
                    String.valueOf(firstError.get()));
        }
        return new Result(snapshotPerSecond(), recorder, errors.sum(), events, firstError.get());
    }

    /**
     * Operations in flight right now — {@code window - availablePermits}.
     * Zero before the run starts. This is the input to the window_headroom
     * validity gate: a run whose occupancy sits pinned at the ceiling is
     * reporting window/latency (Little's Law), which is a fact about the
     * CLIENT, not about consensus.
     */
    /** Issued so far — live, for the :9400 endpoint. */
    public long submittedCount() {
        return submitted.sum();
    }

    /** Failed so far — live, for the :9400 endpoint. */
    public long errorCount() {
        return errors.sum();
    }

    public int currentInFlight() {
        Semaphore s = inFlightWindow;
        return s == null ? 0 : cfg.maxInFlight() - s.availablePermits();
    }

    /**
     * Verbose-only companion thread: announces the warmup->measurement
     * boundary and logs each completed second's commit count. It only READS
     * the atomics the completion callbacks write and exists at all only when
     * DEBUG is enabled — the hot per-operation path is untouched either way.
     */
    private Thread startDebugReporter() {
        if (!log.isDebugEnabled()) return null;
        Thread t = new Thread(() -> {
            boolean measuring = false;
            try {
                while (true) {
                    Thread.sleep(1_000);
                    int sec = (int) ((System.nanoTime() - startNanos) / 1_000_000_000L);
                    if (!measuring && sec >= cfg.warmupSecs()) {
                        log.debug("phase: warmup end -> measurement window");
                        measuring = true;
                    }
                    int done = sec - 1; // last fully completed second
                    if (done >= 0 && done < perSecondCommits.length()) {
                        log.debug("t={}s committed={} ops", done, perSecondCommits.get(done));
                    }
                }
            } catch (InterruptedException e) {
                // Engine finished (or failed): normal shutdown of the reporter.
            }
        }, "bench-debug-reporter");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Key selection over the REUSED keyspace (Paxi Table 3: K = 1000 reused
     * keys), with the D7 conflict knob — exactly Paxi's benchmark model
     * (its `Conflicts` percentage routes ops to a shared portion of the
     * keyspace):
     *
     *   with probability c        -> key 0, the DESIGNATED conflict key
     *   with probability (1 - c)  -> uniform over [1, KEY_SPACE)
     *
     * Key 0 is exclusive to conflict traffic: if uniform traffic could also
     * land on it, the realized conflict fraction would be c + (1-c)/K — a
     * built-in bias of ~0.1% that would matter most exactly at the smallest
     * sweep point (c = 2%). Keeping the hot key out of the uniform range
     * makes the realized fraction equal c by construction, so the manifest
     * value IS the truth about the workload, not an approximation of it.
     *
     * Typed int, no per-op allocation. History: a previous version appended
     * the op index, making every key globally unique — zero contention, and
     * this knob could never have fired. The int contract makes that bug
     * inexpressible.
     */
    private static int keyFor(double conflictRatio) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (conflictRatio > 0.0 && rnd.nextDouble() < conflictRatio) {
            return 0;
        }
        return 1 + rnd.nextInt(ConsensusDriver.KEY_SPACE - 1);
    }

    private long[] snapshotPerSecond() {
        long[] out = new long[perSecondCommits.length()];
        for (int i = 0; i < out.length; i++) out[i] = perSecondCommits.get(i);
        return out;
    }

    /** @param events     null unless the run recorded timestamped events (fault runs)
     *  @param firstError the first failed op's cause; null when errors == 0 */
    public record Result(long[] committedPerSecond, LatencyRecorder latencies, long errors,
                         EventLog events, Throwable firstError) {
        /** Fault-run shape without a captured cause — keeps existing callers/tests. */
        public Result(long[] committedPerSecond, LatencyRecorder latencies, long errors,
                      EventLog events) {
            this(committedPerSecond, latencies, errors, events, null);
        }
        /** Baseline-run shape (no event recording) — keeps existing callers/tests. */
        public Result(long[] committedPerSecond, LatencyRecorder latencies, long errors) {
            this(committedPerSecond, latencies, errors, null, null);
        }
    }
}
