package gr.thesis.bench.topology;

import gr.thesis.bench.core.SystemUnderTest;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the close() contract (P0.4, review F7): teardown keeps going when
 * stop() fails — but NEVER silently. The old default caught and ignored the
 * exception, which is v6's `|| true` disease expressed in Java: a failed
 * stop() means leaked containers or billed VMs that nobody would see.
 */
class ClusterProviderCloseTest {

    private static ClusterProvider throwingProvider(AtomicBoolean stopAttempted) {
        return new ClusterProvider() {
            @Override public List<NodeHandle> start(SystemUnderTest system, int clusterSize) {
                throw new UnsupportedOperationException("not under test");
            }
            @Override public List<String> clientEndpoints() { return List.of(); }
            @Override public void stop() {
                stopAttempted.set(true);
                throw new IllegalStateException("boom: containers survived");
            }
        };
    }

    @Test
    void closeAttemptsStopAndDoesNotThrow() {
        var stopAttempted = new AtomicBoolean();
        assertDoesNotThrow(throwingProvider(stopAttempted)::close,
                "close() must not propagate: campaign teardown continues");
        assertTrue(stopAttempted.get(), "close() must actually attempt stop()");
    }

    @Test
    void closeReportsTheFailureInsteadOfSwallowingIt() {
        // slf4j-simple writes to System.err; capture it around close().
        PrintStream realErr = System.err;
        var captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            throwingProvider(new AtomicBoolean()).close();
        } finally {
            System.setErr(realErr);
        }
        String err = captured.toString();
        assertTrue(err.contains("stop() failed") && err.contains("containers survived"),
                "the failure must be visible, got: <" + err + ">");
    }
}
