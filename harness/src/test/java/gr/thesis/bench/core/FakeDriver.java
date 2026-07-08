package gr.thesis.bench.core;

import gr.thesis.bench.driver.ConsensusDriver;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic in-memory driver for engine characterization tests: completes
 * each write after a fixed service delay on its own scheduler thread; can hold
 * every completion arriving inside a configured stall window until the window
 * ends (a leader election / GC pause in miniature); can fail every write (a
 * dead cluster). No network, no Docker — the WorkloadEngine's behavior is the
 * only thing under test.
 */
final class FakeDriver implements ConsensusDriver {

    private final long delayMillis;
    private final long stallStartMillis; // relative to connect(); -1 = never stalls
    private final long stallEndMillis;
    private final boolean failAll;
    private final AtomicLong issued = new AtomicLong();
    private volatile long connectNanos;
    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fake-driver");
                t.setDaemon(true);
                return t;
            });

    /** Healthy cluster: every write commits after {@code delayMillis}. */
    static FakeDriver responding(long delayMillis) {
        return new FakeDriver(delayMillis, -1, -1, false);
    }

    /** Healthy except that writes arriving in [stallStart, stallEnd) ms after
     *  connect are all held and complete together when the stall lifts. */
    static FakeDriver stalling(long delayMillis, long stallStartMillis, long stallEndMillis) {
        return new FakeDriver(delayMillis, stallStartMillis, stallEndMillis, false);
    }

    /** Dead cluster: every write completes exceptionally, immediately. */
    static FakeDriver failing() {
        return new FakeDriver(0, -1, -1, true);
    }

    private FakeDriver(long delayMillis, long stallStartMillis, long stallEndMillis, boolean failAll) {
        this.delayMillis = delayMillis;
        this.stallStartMillis = stallStartMillis;
        this.stallEndMillis = stallEndMillis;
        this.failAll = failAll;
    }

    /** Total writes the engine submitted — the accounting ground truth. */
    long issued() {
        return issued.get();
    }

    /** Per-keyId write counts — pins the K=1000 reuse contract (P1.1). */
    private final java.util.concurrent.atomic.AtomicLongArray keyCounts =
            new java.util.concurrent.atomic.AtomicLongArray(ConsensusDriver.KEY_SPACE);

    long distinctKeys() {
        long d = 0;
        for (int i = 0; i < keyCounts.length(); i++) if (keyCounts.get(i) > 0) d++;
        return d;
    }

    /** Writes that hit one specific key — pins the D7 conflict routing. */
    long countFor(int keyId) {
        return keyCounts.get(keyId);
    }

    @Override public SystemUnderTest system() { return SystemUnderTest.ETCD; }

    @Override public void connect() { connectNanos = System.nanoTime(); }

    @Override public CompletionStage<Void> write(int keyId, byte[] value) {
        issued.incrementAndGet();
        keyCounts.incrementAndGet(keyId); // throws on out-of-range: contract enforced

        CompletableFuture<Void> f = new CompletableFuture<>();
        if (failAll) {
            f.completeExceptionally(new IllegalStateException("fake: cluster down"));
            return f;
        }
        long nowMillis = (System.nanoTime() - connectNanos) / 1_000_000L;
        long completeAtMillis = nowMillis + delayMillis;
        if (stallStartMillis >= 0 && nowMillis >= stallStartMillis && nowMillis < stallEndMillis) {
            completeAtMillis = stallEndMillis + delayMillis; // held until the stall lifts
        }
        exec.schedule(() -> f.complete(null), completeAtMillis - nowMillis, TimeUnit.MILLISECONDS);
        return f;
    }

    @Override public Optional<Integer> currentLeaderIndex() { return Optional.of(0); }

    @Override public void close() { exec.shutdownNow(); }
}
