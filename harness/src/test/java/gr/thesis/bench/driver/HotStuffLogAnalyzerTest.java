package gr.thesis.bench.driver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The logs.py port (see HotStuffLogAnalyzer) against hand-computed
 * fixtures whose line shapes satisfy logs.py's own regexes — every
 * expected number below is derived by hand from the fixture timestamps,
 * so a formula drift from upstream shows up as a wrong integer, not a
 * vague failure. The live-log shape check (do real -vv lines match these
 * regexes at commit dc01ac8?) is the first VM run's job and is listed in
 * the per-algorithm guide; logs.py itself works against exactly these
 * patterns, which is the strongest pre-VM evidence available.
 */
class HotStuffLogAnalyzerTest {

    private static final String CLIENT = """
            [2026-07-18T10:00:00.000Z INFO client] Transactions size: 1024 B
            [2026-07-18T10:00:00.000Z INFO client] Transactions rate: 200 tx/s
            [2026-07-18T10:00:01.000Z INFO client] Start sending transactions
            [2026-07-18T10:00:02.000Z INFO client] Sending sample transaction 0
            [2026-07-18T10:00:04.000Z INFO client] Sending sample transaction 1
            """;

    /** The client's TARGET node: config lines + both sample batches. */
    private static final String NODE0 = """
            [2026-07-18T10:00:00.500Z INFO consensus::config] Timeout delay set to 5000 ms
            [2026-07-18T10:00:00.500Z INFO consensus::config] Sync retry delay set to 10000 ms
            [2026-07-18T10:00:00.500Z INFO mempool::config] Garbage collection depth set to 50 rounds
            [2026-07-18T10:00:00.500Z INFO mempool::config] Sync retry delay set to 5000 ms
            [2026-07-18T10:00:00.500Z INFO mempool::config] Sync retry nodes set to 3 nodes
            [2026-07-18T10:00:00.500Z INFO mempool::config] Batch size set to 500000 B
            [2026-07-18T10:00:00.500Z INFO mempool::config] Max batch delay set to 100 ms
            [2026-07-18T10:00:02.500Z INFO mempool::core] Batch abc= contains sample tx 0
            [2026-07-18T10:00:02.500Z INFO mempool::core] Batch abc= contains 51200 B
            [2026-07-18T10:00:03.000Z INFO consensus::core] Created B1 -> abc=
            [2026-07-18T10:00:04.000Z INFO consensus::core] Committed B1 -> abc=
            [2026-07-18T10:00:04.500Z INFO mempool::core] Batch def= contains sample tx 1
            [2026-07-18T10:00:04.500Z INFO mempool::core] Batch def= contains 51200 B
            [2026-07-18T10:00:05.000Z INFO consensus::core] Created B2 -> def=
            [2026-07-18T10:00:06.000Z INFO consensus::core] Committed B2 -> def=
            """;

    /** A replica: commits the same batches LATER — the merge must keep the
     *  EARLIEST timestamp (logs.py _merge_results). */
    private static final String NODE1 = """
            [2026-07-18T10:00:04.200Z INFO consensus::core] Committed B1 -> abc=
            [2026-07-18T10:00:06.300Z INFO consensus::core] Committed B2 -> def=
            """;

    @Test
    void computesTheSummaryExactlyAsLogsPyWould() {
        String summary = HotStuffLogAnalyzer.summarize(
                List.of(CLIENT), List.of(NODE0, NODE1), 0);
        HotStuffSummary s = HotStuffSummary.parse(summary);

        assertEquals(0, s.faults());
        assertEquals(2, s.committeeSize(), "nodes + faults");
        assertEquals(200, s.inputRateTxPerSec());
        assertEquals(1024, s.transactionSizeBytes());
        // e2e duration = last commit (t+6.0, EARLIEST across nodes) minus
        // client start (t+1.0) = 5 s.
        assertEquals(5, s.executionTimeSecs());
        // consensus: 102400 B over (6.0 - 3.0) s = 34133 B/s -> /1024 = 33 tx/s.
        assertEquals(33, s.consensusTps());
        // mean((4.0-3.0), (6.0-5.0)) = 1.0 s = 1000 ms.
        assertEquals(1000, s.consensusLatencyMs());
        // e2e: 102400 B over 5 s = 20480 B/s -> /1024 = 20 tx/s.
        assertEquals(20, s.endToEndTps());
        // sample 0: sent 2.0 committed 4.0; sample 1: sent 4.0 committed 6.0
        // -> mean 2.0 s = 2000 ms.
        assertEquals(2000, s.endToEndLatencyMs());
    }

    @Test
    void zeroCommitsRefusesToSynthesizeAZeroSummary() {
        // Deliberate deviation from logs.py (which prints zeros): a wedge or
        // dead cluster is a FAILED run, never an all-zero data row.
        String bootedOnly = NODE0.lines()
                .filter(l -> !l.contains("Committed"))
                .reduce("", (a, b) -> a + b + "\n");
        var e = assertThrows(IllegalStateException.class,
                () -> HotStuffLogAnalyzer.summarize(List.of(CLIENT), List.of(bootedOnly), 0));
        assertTrue(e.getMessage().contains("no committed batch"), e.getMessage());
    }

    @Test
    void clientErrorAndNodePanicFailClosed() {
        assertThrows(IllegalStateException.class, () -> HotStuffLogAnalyzer.summarize(
                List.of(CLIENT + "[t Z x] Error: connection refused\n"), List.of(NODE0), 0));
        assertThrows(IllegalStateException.class, () -> HotStuffLogAnalyzer.summarize(
                List.of(CLIENT), List.of(NODE0 + "thread panicked at src/core.rs\n"), 0));
    }

    @Test
    void missingConfigLineFailsClosedNamingTheField() {
        String noBatchSize = NODE0.replace(
                "[2026-07-18T10:00:00.500Z INFO mempool::config] Batch size set to 500000 B\n", "");
        var e = assertThrows(IllegalStateException.class,
                () -> HotStuffLogAnalyzer.summarize(List.of(CLIENT), List.of(noBatchSize), 0));
        assertTrue(e.getMessage().contains("Batch size"),
                "the missing field must be NAMED: " + e.getMessage());
    }

    @Test
    void batchedButNeverSentSampleFailsLoud() {
        // logs.py's `assert tx_id in sent` — misaligned client/node logs
        // must never silently skew the latency mean.
        String phantom = NODE0
                + "[2026-07-18T10:00:05.500Z INFO mempool::core] Batch ghi= contains sample tx 9\n"
                + "[2026-07-18T10:00:05.600Z INFO mempool::core] Batch ghi= contains 100 B\n"
                + "[2026-07-18T10:00:05.700Z INFO consensus::core] Created B3 -> ghi=\n"
                + "[2026-07-18T10:00:05.800Z INFO consensus::core] Committed B3 -> ghi=\n";
        var e = assertThrows(IllegalStateException.class,
                () -> HotStuffLogAnalyzer.summarize(List.of(CLIENT), List.of(phantom), 0));
        assertTrue(e.getMessage().contains("sample tx 9"), e.getMessage());
    }
}
