package gr.thesis.bench.topology;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3.3d-kafka_zk PREREQUISITE — verifies the D10 colocated Kafka+ZooKeeper
 * wiring BY EXECUTION before any of it becomes an SSH golden (the same
 * verify-first step that de-risked KRaft and CometBFT). Locally the
 * "colocation" is conceptual: pair i (zk<i> + b<i>) maps to ONE VM on the
 * campaign — the remote golden runs both containers on the same host.
 *
 * The load-bearing facts this encodes were PROBED FIRST (2026-07-17):
 *  - The apache/kafka image's entrypoint REFUSES ZooKeeper mode ("The
 *    kafka configuration file appears to be for a legacy cluster.
 *    Formatting is only supported for clusters in KRaft mode") — its
 *    startup unconditionally runs kafka-storage format. The SAME
 *    digest-pinned image still carries full ZK-mode binaries
 *    (zookeeper-3.8.4 client on the classpath), so the recipe bypasses
 *    the entrypoint: write server.properties, run kafka-server-start.sh.
 *    THIS is what makes F6 (ZAB→Raft) the clean comparison: identical
 *    Kafka 3.9.1 bits in both cells, only the coordination differs.
 *    ZK mode logs "[KafkaServer id=N] started (kafka.server.KafkaServer)"
 *    — a DIFFERENT line than KRaft's KafkaRaftServer (wait regex below).
 *  - The zookeeper:3.9 image (digest-pinned) takes the ensemble via
 *    ZOO_MY_ID + ZOO_SERVERS (3.5+ syntax, client port after ';') and
 *    extra config via ZOO_CFG_EXTRA — probed: the
 *    PrometheusMetricsProvider initializes with httpPort=7000, the
 *    endpoint the campaign's :7000 scrape job (D10) and P4.3's
 *    metric-name verification depend on.
 */
class KafkaZkColocatedFormationTest {

    private static final DockerImageName KAFKA =
            DockerImageName.parse(LocalDockerProvider.KAFKA_IMAGE)
                    .asCompatibleSubstituteFor("apache/kafka");
    private static final DockerImageName ZOOKEEPER =
            DockerImageName.parse(LocalDockerProvider.ZOOKEEPER_IMAGE)
                    .asCompatibleSubstituteFor("zookeeper");
    private static final int N = 3;

    @Test
    void colocatedZkEnsembleAndBrokersCommitAcksAllUnderMinIsr2() throws Exception {
        Network net = Network.newNetwork();
        String zooServers = IntStream.rangeClosed(1, N)
                .mapToObj(i -> "server." + i + "=zk" + i + ":2888:3888;2181")
                .reduce((a, b) -> a + " " + b).orElseThrow();
        String zkConnect = IntStream.rangeClosed(1, N)
                .mapToObj(i -> "zk" + i + ":2181")
                .reduce((a, b) -> a + "," + b).orElseThrow();

        List<GenericContainer<?>> zks = new ArrayList<>(N);
        for (int i = 1; i <= N; i++) {
            GenericContainer<?> zk = new GenericContainer<>(ZOOKEEPER)
                    .withNetwork(net)
                    .withNetworkAliases("zk" + i)
                    .withEnv("ZOO_MY_ID", Integer.toString(i))
                    .withEnv("ZOO_SERVERS", zooServers)
                    // The campaign scrapes every ensemble member on :7000.
                    .withEnv("ZOO_CFG_EXTRA",
                            "metricsProvider.className=org.apache.zookeeper.metrics.prometheus.PrometheusMetricsProvider"
                                    + " metricsProvider.httpPort=7000")
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
            if (i == 1) zk.withExposedPorts(2181, 7000); // metrics assertion below
            zks.add(zk);
        }

        List<GenericContainer<?>> brokers = new ArrayList<>(N);
        for (int i = 1; i <= N; i++) {
            // Same properties the remote golden will printf — the entrypoint
            // bypass IS the recipe (see class javadoc).
            String script = "printf '%s\\n'"
                    + " 'broker.id=" + i + "'"
                    + " 'zookeeper.connect=" + zkConnect + "'"
                    + " 'listeners=PLAINTEXT://:9092'"
                    + " 'advertised.listeners=PLAINTEXT://b" + i + ":9092'"
                    + " 'offsets.topic.replication.factor=3'"
                    + " 'transaction.state.log.replication.factor=3'"
                    + " 'transaction.state.log.min.isr=2'"
                    + " 'log.dirs=/tmp/kafka-logs'"
                    + " > /tmp/server.properties"
                    + " && /opt/kafka/bin/kafka-server-start.sh /tmp/server.properties";
            brokers.add(new GenericContainer<>(KAFKA)
                    .withNetwork(net)
                    .withNetworkAliases("b" + i)
                    .withCreateContainerCmdModifier(cmd -> cmd
                            .withEntrypoint("sh", "-c").withCmd(script))
                    .waitingFor(Wait.forLogMessage(".*\\[KafkaServer id=\\d+\\] started.*", 1)
                            .withStartupTimeout(Duration.ofSeconds(90))));
        }

        try {
            // ZK ensemble first (parallel — quorum needs 2 of 3), then the
            // brokers (parallel; they retry ZK until the ensemble answers).
            Startables.deepStart(zks).join();
            Startables.deepStart(brokers).join();
            GenericContainer<?> b1 = brokers.get(0);

            // 1. All three brokers registered through ZooKeeper.
            Container.ExecResult apis = b1.execInContainer(
                    "/opt/kafka/bin/kafka-broker-api-versions.sh", "--bootstrap-server", "b1:9092");
            long brokerCount = apis.getStdout().lines().filter(l -> l.contains("(id:")).count();
            assertEquals(N, brokerCount,
                    "all 3 brokers must join via the ZK ensemble:\n" + apis.getStdout());

            // 2. The F6 topology topic (RF=3, min.insync.replicas=2).
            Container.ExecResult create = b1.execInContainer(
                    "/opt/kafka/bin/kafka-topics.sh", "--create", "--topic", "bench",
                    "--partitions", "6", "--replication-factor", "3",
                    "--config", "min.insync.replicas=2", "--bootstrap-server", "b1:9092");
            assertEquals(0, create.getExitCode(), "topic create failed:\n" + create.getStderr());

            // 3. acks=all commits under min-ISR 2 — the ZAB-coordinated
            //    cluster's real replication path.
            Container.ExecResult produce = b1.execInContainer(
                    "/opt/kafka/bin/kafka-producer-perf-test.sh", "--topic", "bench",
                    "--num-records", "2000", "--record-size", "1024", "--throughput", "-1",
                    "--producer-props", "bootstrap.servers=b1:9092", "acks=all");
            assertEquals(0, produce.getExitCode(),
                    "acks=all produce must commit:\n" + produce.getStdout() + produce.getStderr());
            assertTrue(produce.getStdout().contains("records sent"),
                    "perf-test must report records sent:\n" + produce.getStdout());

            // 4. Full replication: partition 0 shows all 3 in-sync (the
            //    Elr-safe regex — the KRaft formation test's lesson).
            Container.ExecResult describe = b1.execInContainer(
                    "/opt/kafka/bin/kafka-topics.sh", "--describe", "--topic", "bench",
                    "--bootstrap-server", "b1:9092");
            String p0 = describe.getStdout().lines()
                    .filter(l -> l.contains("Partition: 0")).findFirst().orElseThrow();
            var m = java.util.regex.Pattern.compile("Isr:\\s*([0-9,]+)").matcher(p0);
            assertTrue(m.find(), "no Isr field in describe line: " + p0);
            assertEquals(3, m.group(1).split(",").length,
                    "partition 0 must have all 3 replicas in-sync, got: " + p0);

            // 5. The :7000 Prometheus endpoint answers with real ZK metrics
            //    (P4.3's metric-name source; the D10 scrape target).
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> metrics = http.send(
                    HttpRequest.newBuilder(URI.create("http://" + zks.get(0).getHost()
                                    + ":" + zks.get(0).getMappedPort(7000) + "/metrics"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metrics.statusCode(), "ZK :7000/metrics must answer");
            assertTrue(metrics.body().contains("znode_count"),
                    "expected a native ZK metric (znode_count) in:\n"
                            + metrics.body().lines().limit(20).reduce("", (a, b) -> a + b + "\n"));
        } finally {
            brokers.forEach(GenericContainer::stop);
            zks.forEach(GenericContainer::stop);
            net.close();
        }
    }
}
