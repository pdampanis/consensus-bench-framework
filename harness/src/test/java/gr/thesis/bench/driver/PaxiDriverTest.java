package gr.thesis.bench.driver;

import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.topology.ClusterProvider;
import gr.thesis.bench.topology.LocalDockerProvider;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2.4b acceptance (integration — Docker, source-pinned paxi:6823d0b):
 * the production Paxos driver on a real 3-node cluster.
 *
 *  1. A completed write() means the command was committed AND executed by
 *     the majority-quorum path (reply-on-EXECUTE default — in-memory
 *     store, D6 documented).
 *  2. Leader detection via the Ballot response header (F22 — /state does
 *     not exist), CORROBORATED through an independent path: a raw JDK
 *     HTTP client writing to a DIFFERENT (non-leader) entry must see the
 *     same leader's ballot, because a forwarding entry relays the
 *     leader's reply. Two entries agreeing through two client stacks is
 *     real corroboration (the EtcdDriverTest pattern).
 *  3. Kill a FOLLOWER → writes keep committing on the 2/3 majority.
 *     (Leader-kill is NOT tested here: stock paxi has no failure detector
 *     — F26 — and the paxi leader_kill design is preregistered at P3.3.)
 */
class PaxiDriverTest {

    @Test
    void commitsWritesDetectsLeaderViaBallotAndSurvivesFollowerKill() throws Exception {
        try (var provider = new LocalDockerProvider()) {
            List<ClusterProvider.NodeHandle> nodes = provider.start(SystemUnderTest.PAXOS, 3);
            List<String> eps = provider.clientEndpoints();

            try (var driver = new PaxiDriver(SystemUnderTest.PAXOS, eps)) {
                driver.connect();

                // 1. Committed write through the production path.
                driver.write(7, new byte[64]).toCompletableFuture().get(10, TimeUnit.SECONDS);

                // 2. Ballot-header leader detection...
                int leader = driver.currentLeaderIndex().orElseThrow();
                assertTrue(leader >= 0 && leader < 3, "leader index in range, got " + leader);

                // ...corroborated via an independent stack AND entry: raw
                // JDK HTTP PUT to a non-leader endpoint; the forwarded
                // reply must carry the SAME leader's ballot ID ("1.<node>").
                int followerEntry = (leader + 1) % 3;
                HttpResponse<Void> resp = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(eps.get(followerEntry) + "/2"))
                                .timeout(Duration.ofSeconds(5))
                                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[8]))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
                assertEquals(200, resp.statusCode(), "write via the follower entry must commit");
                String ballot = resp.headers().firstValue("Ballot").orElseThrow();
                String[] parts = ballot.split("\\.");
                assertEquals(3, parts.length, "ballot format n.zone.node, got " + ballot);
                assertEquals(1, Integer.parseInt(parts[1]), "single-zone cluster");
                assertEquals(leader + 1, Integer.parseInt(parts[2]),
                        "the follower entry's forwarded reply must name the SAME leader "
                                + "(driver said node " + (leader + 1) + ", ballot " + ballot + ")");

                // 3. Kill a follower ((leader+1)%3 is never the leader):
                //    majority (leader + one follower) keeps committing.
                DockerClientFactory.instance().client()
                        .killContainerCmd(nodes.get(followerEntry).containerName()).exec();
                driver.write(8, new byte[64]).toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }
}
