package gr.thesis.bench.driver;

import gr.thesis.bench.core.LatencyRecorder;
import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.core.WorkloadEngine;
import gr.thesis.bench.topology.LocalDockerProvider;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2.3 acceptance (integration — Docker): the CometBFT driver on a
 * single-node kvstore chain. THE G1 FLAW-A REGRESSION: the retired probe
 * ran 6 blocking threads on broadcast_tx_commit — with a ~1 s block
 * interval its measurable ceiling was clients ÷ block time ≈ 6 tx/s while
 * the system does thousands (PaxiBFT measured Tendermint at ~1750 tx/s
 * with 90 clients). The async driver with a deep in-flight window must
 * sustain >300 tx/s — ≥50x that ceiling — or the flaw is back.
 *
 * Facts this driver encodes were PROBED against the real node first
 * (2026-07-15, cometbft v0.38.17):
 *  - HTTP 200 does NOT mean commit: a failed CheckTx still returns 200
 *    with result.check_tx.code != 0; v0.38 names the DeliverTx result
 *    "tx_result". Success = no top-level "error" AND both codes == 0.
 *  - The mempool cache REJECTS duplicate tx bytes with a JSON-RPC error
 *    ("tx already exists in cache") — every tx must carry a nonce.
 *  - rpc.max_subscription_clients (default 100) caps concurrent
 *    broadcast_tx_commit callers: measured 99/250 committed, 151 rejected
 *    at the default; 250/250 after the provider raises it to 2000.
 */
class CometBftDriverTest {

    @Test
    void commitsUniqueTxsAndSustainsFiftyTimesTheOldCeiling() throws Exception {
        try (var provider = new LocalDockerProvider()) {
            provider.start(SystemUnderTest.TENDERMINT, 1);
            try (var driver = new CometBftDriver(provider.clientEndpoints())) {
                driver.connect();

                // 1. Committed write: completes only when the tx is in a
                //    committed block with both codes zero.
                driver.write(7, new byte[64]).toCompletableFuture().get(15, TimeUnit.SECONDS);
                // 2. Same keyId again: the nonce must make the tx bytes
                //    unique or the mempool cache rejects it (probed fact).
                driver.write(7, new byte[64]).toCompletableFuture().get(15, TimeUnit.SECONDS);

                // 3. Flaw-A regression: saturation, 12 s. Window 600, not
                //    the plan's ≥200 floor: with ~1 s block inclusion the
                //    throughput is window/latency (Little's Law — window
                //    200 measured exactly 220 tx/s), so the window must
                //    exceed the target x latency for the BLOCK capacity,
                //    not the window, to bind. The provider's raised
                //    subscription limit (2000) makes the depth possible.
                var cfg = new WorkloadEngine.Config(12, 2, 0, 600, 1024, 0.0);
                var rec = new LatencyRecorder();
                var r = new WorkloadEngine(driver, cfg, rec).run();

                long attempts = rec.countAfterWarmup() + r.errors();
                assertTrue(r.errors() <= attempts / 100,
                        "error rate above 1% (" + r.errors() + "/" + attempts
                                + ") — first cause: " + r.firstError());
                double txPerSec = rec.countAfterWarmup() / 10.0;
                System.out.printf("G1 flaw-A: %.0f tx/s (old ceiling ~6), p50=%d us%n",
                        txPerSec, rec.percentileMicros(50));
                assertTrue(txPerSec > 300,
                        "must sustain >300 tx/s (>=50x the 6-thread probe's ceiling), got "
                                + txPerSec);
                // p50 is block-interval flavored: must be well under the 5 s
                // driver deadline but not sub-millisecond (a sub-ms p50 would
                // mean we measured something other than block inclusion).
                long p50 = rec.percentileMicros(50);
                assertTrue(p50 > 1_000 && p50 < 3_000_000,
                        "p50 should sit near the block cadence, got " + p50 + " us");
            }
        }
    }

    @Test
    void driverRequiresAtLeastOneEndpoint() {
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new CometBftDriver(java.util.List.of())).getMessage() != null);
    }
}
