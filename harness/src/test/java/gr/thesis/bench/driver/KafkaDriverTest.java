package gr.thesis.bench.driver;

import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.topology.ClusterProvider;
import gr.thesis.bench.topology.LocalDockerProvider;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2.2b acceptance (integration — Docker): the production Kafka driver on a
 * single-node KRaft broker (P2.2a substrate).
 *
 *  1. A completed write() means the broker acknowledged under acks=all —
 *     committed, not merely buffered in the client.
 *  2. currentLeaderIndex() maps the bench topic's partition-0 leader to the
 *     node index (trivially 0 on one broker — the multi-broker mapping is
 *     exercised when the campaign provider lands).
 *  3. The F18 bounded-completion contract: kill the broker and the write
 *     must complete EXCEPTIONALLY within delivery.timeout (5 s) + slack —
 *     never hang the engine's drain barrier. send() must not block either:
 *     metadata is warmed at connect(), so the fail path is asynchronous.
 */
class KafkaDriverTest {

    @Test
    void commitsWritesDetectsBrokerAndFailsClosedWhenBrokerDies() throws Exception {
        try (var provider = new LocalDockerProvider()) {
            List<ClusterProvider.NodeHandle> nodes = provider.start(SystemUnderTest.KRAFT, 1);
            try (var driver = new KafkaDriver(SystemUnderTest.KRAFT, provider.clientEndpoints())) {
                driver.connect();

                // 1. Committed write (acks=all ack, timed in the callback).
                driver.write(7, new byte[64]).toCompletableFuture().get(15, TimeUnit.SECONDS);

                // 2. The only broker is the leader of every partition.
                assertEquals(0, driver.currentLeaderIndex().orElseThrow());

                // 3. Dead broker: fail closed within the bound, never hang.
                DockerClientFactory.instance().client()
                        .killContainerCmd(nodes.get(0).containerName()).exec();
                long t0 = System.nanoTime();
                var f = driver.write(8, new byte[64]).toCompletableFuture();
                assertThrows(ExecutionException.class, () -> f.get(15, TimeUnit.SECONDS),
                        "dead broker: the write must fail, not hang or claim commit");
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                assertTrue(elapsedMs < 8_000,
                        "must fail within delivery.timeout=5s (+slack), took " + elapsedMs + " ms");
            }
        }
    }

    @Test
    void driverServesKafkaSystemsOnly() {
        assertThrows(IllegalArgumentException.class,
                () -> new KafkaDriver(SystemUnderTest.ETCD, List.of("localhost:9092")));
    }
}
