package gr.thesis.bench.driver;

import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.topology.ClusterProvider;
import gr.thesis.bench.topology.LocalDockerProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2.1 acceptance (integration — Docker): the PRODUCTION etcd driver.
 *
 * Leader detection is verified against an INDEPENDENT client stack: jetcd
 * speaks gRPC, the ground truth below asks the same question through the
 * HTTP/JSON gateway (plain JDK HttpClient). Two stacks agreeing is real
 * corroboration; one stack agreeing with itself would be circular — the
 * exact trap the v6 review taught us to avoid.
 *
 * Then the point of leader detection at all: kill the detected leader and
 * the driver must (a) detect a DIFFERENT leader and (b) keep committing on
 * the 2/3 quorum — this is the primitive every leader_kill cell uses.
 */
class EtcdDriverTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Ground truth via the HTTP gateway: index of the endpoint whose own
     *  member id equals the leader id it reports. */
    private static int leaderIndexViaHttpGateway(List<String> endpoints) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        for (int i = 0; i < endpoints.size(); i++) {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(endpoints.get(i) + "/v3/maintenance/status"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) continue; // dead member: not the leader
            JsonNode n = JSON.readTree(resp.body());
            if (n.path("header").path("member_id").asText()
                    .equals(n.path("leader").asText())) {
                return i;
            }
        }
        throw new AssertionError("no member reports itself as leader");
    }

    @Test
    void commitsWritesDetectsLeaderAndSurvivesLeaderKill() throws Exception {
        try (var provider = new LocalDockerProvider()) {
            List<ClusterProvider.NodeHandle> nodes = provider.start(SystemUnderTest.ETCD, 3);
            List<String> eps = provider.clientEndpoints();

            try (var driver = new EtcdDriver(eps)) {
                driver.connect();

                // 1. Committed write through the native gRPC path.
                driver.write(7, new byte[64]).toCompletableFuture().get(10, TimeUnit.SECONDS);

                // 2. Leader detection agrees with the independent stack.
                int detected = driver.currentLeaderIndex().orElseThrow();
                assertEquals(leaderIndexViaHttpGateway(eps), detected,
                        "gRPC-detected leader must match the HTTP-gateway ground truth");

                // 3. Kill the DETECTED leader (not "node 0 and hope" — the
                //    v6 regression this API exists to prevent).
                DockerClientFactory.instance().client()
                        .killContainerCmd(nodes.get(detected).containerName()).exec();

                // 4. Within an election timeout: a new, different leader,
                //    and writes keep committing on the 2/3 majority.
                int newLeader = -1;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline) {
                    try {
                        var idx = driver.currentLeaderIndex();
                        if (idx.isPresent() && idx.get() != detected) {
                            newLeader = idx.get();
                            break;
                        }
                    } catch (Exception retry) { /* election in progress */ }
                    Thread.sleep(500);
                }
                assertTrue(newLeader >= 0, "a new leader must be elected within 30 s");
                assertNotEquals(detected, newLeader);

                driver.write(8, new byte[64]).toCompletableFuture().get(15, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void lostQuorumWritesFailWithinTheDriverDeadlineInsteadOfHanging() throws Exception {
        // The engine's drain barrier (inFlight.acquire(maxInFlight)) waits for
        // EVERY issued write to complete. The HTTP drivers bound completion at
        // 5 s per request; a jetcd put with no deadline can outlive the run on
        // a quorum-lost cluster (DOUBLE_KILL, NETWORK_PARTITION) — hanging the
        // whole fault run instead of failing closed. This pins the bound: the
        // write must complete EXCEPTIONALLY within the driver deadline + slack,
        // never commit, never hang.
        try (var provider = new LocalDockerProvider()) {
            List<ClusterProvider.NodeHandle> nodes = provider.start(SystemUnderTest.ETCD, 3);
            try (var driver = new EtcdDriver(provider.clientEndpoints())) {
                driver.connect();
                driver.write(1, new byte[8]).toCompletableFuture().get(10, TimeUnit.SECONDS);

                var docker = DockerClientFactory.instance().client();
                docker.killContainerCmd(nodes.get(1).containerName()).exec();
                docker.killContainerCmd(nodes.get(2).containerName()).exec();

                long t0 = System.nanoTime();
                var f = driver.write(2, new byte[8]).toCompletableFuture();
                assertThrows(ExecutionException.class, () -> f.get(15, TimeUnit.SECONDS),
                        "1/3 has no quorum: the write must fail, not commit or hang");
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                assertTrue(elapsedMs < 6_500,
                        "must fail within the 5 s driver deadline (+slack), took "
                                + elapsedMs + " ms");
            }
        }
    }

    @Test
    void keyEncodingIsIdenticalToTheFallbackDriver() {
        // Both etcd drivers MUST write the same keys: G3 cross-validates
        // them against each other on the same cluster, and a diverging key
        // contract would silently compare different workloads.
        for (int id : new int[]{0, 42, 999}) {
            assertEquals(new String(EtcdHttpDriver.encodeKey(id)),
                         new String(EtcdDriver.encodeKey(id)));
        }
    }
}
