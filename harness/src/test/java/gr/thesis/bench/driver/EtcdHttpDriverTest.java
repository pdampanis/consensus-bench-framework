package gr.thesis.bench.driver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F30 (2026-07-17 review): the HTTP fallback driver is constructed with ONE
 * endpoint — it structurally cannot know which cluster member leads, so it
 * must never claim one. The M0-era stub returned Optional.of(0): the exact
 * v6 "kill node1 and hope" trap, armed for whoever wires fault targeting
 * through the fallback path. Honest absence is the codebase's rule
 * (EtcdDriver returns empty mid-election; CometBftDriver returns empty for
 * the rotating proposer) — this driver follows it.
 */
class EtcdHttpDriverTest {

    @Test
    void singleEndpointFallbackDriverNeverClaimsALeader() throws Exception {
        var driver = new EtcdHttpDriver("http://127.0.0.1:2379");
        assertTrue(driver.currentLeaderIndex().isEmpty(),
                "a one-endpoint driver claiming leader index 0 is the v6 kill-node-1 trap");
    }
}
