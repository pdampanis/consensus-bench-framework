package gr.thesis.bench.driver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2.5: the HotStuff boundary parser (M2.5). asonnino/hotstuff exposes no
 * Prometheus metrics — its benchmark tooling's SUMMARY block is the ONLY
 * metrics source ("its logs ARE its metrics", P4.5), so this parser is
 * the system's entire measurement surface and must fail CLOSED on any
 * surprise: a missing field silently defaulted would fabricate a thesis
 * number.
 *
 * The fixture is VERBATIM the format of benchmark/benchmark/logs.py
 * result() (fetched from asonnino/hotstuff main, 2026-07-16): integers
 * carry thousands separators, latencies are ms, and the block ends with a
 * dashed rule. End-to-end TPS/latency are the client-observed primaries
 * (the same metric class as every other driver's numbers); Consensus
 * TPS/latency are protocol-internal.
 */
class HotStuffSummaryTest {

    private static final String FIXTURE = """

            -----------------------------------------
             SUMMARY:
            -----------------------------------------
             + CONFIG:
             Faults: 0 nodes
             Committee size: 4 nodes
             Input rate: 10,000 tx/s
             Transaction size: 1,024 B
             Execution time: 301 s

             Consensus timeout delay: 5,000 ms
             Consensus sync retry delay: 5,000 ms
             Mempool GC depth: 50 rounds
             Mempool sync retry delay: 5,000 ms
             Mempool sync retry nodes: 3 nodes
             Mempool batch size: 500,000 B
             Mempool max batch delay: 100 ms

             + RESULTS:
             Consensus TPS: 7,812 tx/s
             Consensus BPS: 7,999,488 B/s
             Consensus latency: 42 ms

             End-to-end TPS: 7,801 tx/s
             End-to-end BPS: 7,988,224 B/s
             End-to-end latency: 61 ms
            -----------------------------------------
            """;

    @Test
    void parsesTheCanonicalSummaryFixture() {
        HotStuffSummary s = HotStuffSummary.parse(FIXTURE);
        assertEquals(0, s.faults());
        assertEquals(4, s.committeeSize());
        assertEquals(10_000, s.inputRateTxPerSec(), "thousands separator stripped");
        assertEquals(1_024, s.transactionSizeBytes());
        assertEquals(301, s.executionTimeSecs());
        assertEquals(7_812, s.consensusTps());
        assertEquals(42, s.consensusLatencyMs());
        assertEquals(7_801, s.endToEndTps(), "the client-observed primary");
        assertEquals(61, s.endToEndLatencyMs());
    }

    @Test
    void missingResultLineFailsClosed() {
        String truncated = FIXTURE.replace(" End-to-end TPS: 7,801 tx/s\n", "");
        var e = assertThrows(IllegalStateException.class,
                () -> HotStuffSummary.parse(truncated));
        assertTrue(e.getMessage().contains("End-to-end TPS"),
                "the error must NAME the missing field, got: " + e.getMessage());
    }

    @Test
    void logWithoutASummaryBlockFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> HotStuffSummary.parse("client started\nrate too high\n"));
    }

    @Test
    void duplicateSummaryBlocksFailClosed() {
        // Two blocks (an appended rerun log) are ambiguous — picking one
        // silently could report the wrong run. Fail loud; the campaign
        // runner owns one-log-per-run hygiene.
        assertThrows(IllegalStateException.class,
                () -> HotStuffSummary.parse(FIXTURE + FIXTURE));
    }
}
