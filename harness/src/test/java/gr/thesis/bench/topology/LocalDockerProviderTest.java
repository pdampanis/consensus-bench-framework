package gr.thesis.bench.topology;

import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.driver.EtcdHttpDriver;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0.2 acceptance (integration — needs the local Docker daemon):
 *  1. size 1: /health answers, a committed PUT succeeds, stop() leaves zero
 *     thesis-* containers;
 *  2. size 3: quorum write succeeds; kill 1 of 3 -> writes still succeed
 *     (majority holds, possibly after a re-election); kill 2 of 3 -> writes
 *     FAIL (fail-closed proof: the harness must see lost quorum as errors,
 *     never as data);
 *  3. only ETCD is supported here — anything else fails with a typed error.
 */
class LocalDockerProviderTest {

    /** One bounded, committed write via the same driver the harness uses. */
    private static boolean writeSucceeds(String endpoint, int keyId) {
        try (EtcdHttpDriver d = new EtcdHttpDriver(endpoint)) {
            d.connect();
            d.write(keyId, new byte[8]).toCompletableFuture().get(6, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Retry writes until deadline — leader elections take a few seconds. */
    private static boolean writeSucceedsWithin(String endpoint, int keyId, Duration deadline) throws Exception {
        long end = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < end) {
            if (writeSucceeds(endpoint, keyId)) return true;
            Thread.sleep(500);
        }
        return false;
    }

    private static long thesisContainersAlive() {
        return DockerClientFactory.instance().client().listContainersCmd()
                .withShowAll(true).exec().stream()
                .flatMap(c -> java.util.Arrays.stream(c.getNames()))
                .filter(n -> n.startsWith("/thesis-"))
                .count();
    }

    @Test
    void size1_healthy_thenStopLeavesNoContainers() throws Exception {
        var provider = new LocalDockerProvider();
        try {
            List<ClusterProvider.NodeHandle> nodes = provider.start(SystemUnderTest.ETCD, 1);
            assertEquals(1, nodes.size());
            List<String> eps = provider.clientEndpoints();
            assertEquals(1, eps.size());

            HttpResponse<String> health = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(eps.get(0) + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"health\":\"true\""), health.body());

            assertTrue(writeSucceeds(eps.get(0), 1), "committed PUT on a healthy node");
        } finally {
            provider.stop();
        }
        assertEquals(0, thesisContainersAlive(), "stop() must leave zero thesis-* containers");
    }

    @Test
    void size3_quorumSurvivesOneKill_failsClosedOnSecond() throws Exception {
        var provider = new LocalDockerProvider();
        try {
            List<ClusterProvider.NodeHandle> nodes = provider.start(SystemUnderTest.ETCD, 3);
            assertEquals(3, nodes.size());
            // Drive all writes through node index 2; kill 0 then 1.
            String ep = provider.clientEndpoints().get(2);

            assertTrue(writeSucceeds(ep, 2), "3/3 quorum write");

            var docker = DockerClientFactory.instance().client();
            docker.killContainerCmd(nodes.get(0).containerName()).exec();
            assertTrue(writeSucceedsWithin(ep, 3, Duration.ofSeconds(20)),
                    "2/3 majority must keep committing (allowing for a re-election)");

            docker.killContainerCmd(nodes.get(1).containerName()).exec();
            assertFalse(writeSucceeds(ep, 4),
                    "1/3 has no quorum: the write MUST fail, not fabricate a commit");
        } finally {
            provider.stop();
        }
        assertEquals(0, thesisContainersAlive(), "stop() cleans up even with killed members");
    }

    @Test
    void unsupportedSystemFailsClosed() {
        var provider = new LocalDockerProvider();
        assertThrows(UnsupportedOperationException.class,
                () -> provider.start(SystemUnderTest.KAFKA_ZK, 3));
    }

    // ---- P2.2a: KRaft single-node (the KafkaDriver's substrate) ----

    @Test
    void kraft_size1_commitsAnAcksAllProduce_thenStopLeavesNoContainers() throws Exception {
        var provider = new LocalDockerProvider();
        try {
            List<ClusterProvider.NodeHandle> nodes = provider.start(SystemUnderTest.KRAFT, 1);
            assertEquals(1, nodes.size());
            assertEquals("k1", nodes.get(0).privateIp(), "network alias must follow containerName()");
            String bootstrap = provider.clientEndpoints().get(0);
            assertFalse(bootstrap.contains("://"),
                    "endpoint must be a bare host:port bootstrap address, got " + bootstrap);

            // Committed produce with the same semantics the driver will use:
            // acks=all — the send future completes only on broker commit.
            var props = new java.util.Properties();
            props.put("bootstrap.servers", bootstrap);
            props.put("acks", "all");
            props.put("key.serializer",
                    org.apache.kafka.common.serialization.ByteArraySerializer.class.getName());
            props.put("value.serializer",
                    org.apache.kafka.common.serialization.ByteArraySerializer.class.getName());
            try (var p = new org.apache.kafka.clients.producer.KafkaProducer<byte[], byte[]>(props)) {
                p.send(new org.apache.kafka.clients.producer.ProducerRecord<>(
                        "p22a-smoke", "k".getBytes(), new byte[8])).get(30, TimeUnit.SECONDS);
            }
        } finally {
            provider.stop();
        }
        assertEquals(0, thesisContainersAlive(), "stop() must leave zero thesis-* containers");
    }

    @Test
    void kraftMultiNodeFailsClosedUntilTheCampaignProviderLands() {
        // Multi-broker KRaft needs the full listener/quorum wiring the remote
        // provider will own; claiming it now would be v6's ship-unverified sin.
        var provider = new LocalDockerProvider();
        assertThrows(UnsupportedOperationException.class,
                () -> provider.start(SystemUnderTest.KRAFT, 3));
    }
}
