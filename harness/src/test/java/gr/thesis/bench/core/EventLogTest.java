package gr.thesis.bench.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.4 unit level: the event buffer itself — encoding, overflow behavior,
 * and the failover computation, all on synthetic nanos (no engine).
 */
class EventLogTest {

    @Test
    void failoverIsFirstCommitAtOrAfterTheFault() {
        var log = new EventLog(16);
        log.start(1_000_000L); // arbitrary origin
        log.append(1_000_000L + 10_000_000L, true);   // +10 ms commit
        log.append(1_000_000L + 20_000_000L, false);  // +20 ms error
        log.faultInjectedAt(1_000_000L + 25_000_000L);   // fault at +25 ms
        log.append(1_000_000L + 30_000_000L, false);  // +30 ms error (still down)
        log.append(1_000_000L + 95_000_000L, true);   // +95 ms first commit after fault
        log.append(1_000_000L + 96_000_000L, true);

        assertEquals(70, log.failoverMillis().orElseThrow(),
                "kill->first-commit gap: 95-25 = 70 ms");
    }

    @Test
    void noCommitAfterFaultMeansNoFailoverNumber() {
        // A cluster that never recovers must yield NO failover time — an
        // absent number, never a fabricated one (fail closed).
        var log = new EventLog(16);
        log.start(0);
        log.append(10_000_000L, true);
        log.faultInjectedAt(20_000_000L);
        log.append(30_000_000L, false);

        assertTrue(log.failoverMillis().isEmpty());
        assertEquals(1, log.commitCount());
        assertEquals(1, log.errorCount());
    }

    @Test
    void faultMarkBeforeStartFailsLoudInsteadOfProducingAGarbageMark() {
        // F47: the campaign's fault thread starts before engine.run() sets
        // the origin. If its mark ever landed pre-start, the rel-nanos
        // would be measured against origin 0 — a garbage failover number
        // that LOOKS real. Fail loud instead; the runner waits on
        // isStarted() so this can only fire on a real wiring bug.
        var log = new EventLog(4);
        org.junit.jupiter.api.Assertions.assertFalse(log.isStarted());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> log.faultInjectedAt(5_000_000L));
        log.start(0); // 0 is a legitimate origin (synthetic-nanos tests use it)
        assertTrue(log.isStarted());
        log.faultInjectedAt(5_000_000L); // now fine
    }

    @Test
    void overflowDropsLoudlyInsteadOfCrashing() {
        // The buffer is preallocated (no allocation on the hot path); if a
        // run outgrows it, later events are counted as dropped — the run
        // keeps going, and the dropped count is the evidence the validity
        // layer needs to reject the failover number.
        var log = new EventLog(4);
        log.start(0);
        for (int i = 0; i < 10; i++) log.append(i * 1_000_000L, true);

        assertEquals(4, log.commitCount());
        assertEquals(6, log.dropped());
    }
}
