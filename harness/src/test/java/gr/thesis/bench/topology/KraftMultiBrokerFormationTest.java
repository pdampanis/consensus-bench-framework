package gr.thesis.bench.topology;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3.3d-kafka PREREQUISITE — verifies the multi-broker KRaft wiring the
 * campaign will use, on a USER-DEFINED Docker network (each broker on its
 * own network alias, mirroring one-node-per-VM). This is the "execute,
 * don't assert" step that de-risks the remote Kafka golden: it proves the
 * exact env-var / quorum-voter / internal-topic-RF contract forms a real
 * 3-node cluster and commits an {@code acks=all} write under
 * {@code min.insync.replicas=2}, BEFORE any of it is written into an SSH
 * golden that would otherwise be unverified (the v6 trap).
 *
 * Why the commit is driven by an IN-CONTAINER producer (docker exec), not
 * the host KafkaDriver: the brokers advertise their network ALIAS
 * (k1/k2/k3), which the campaign loadgen — on the private network —
 * resolves, but the host JVM does not. This mirrors the campaign (loadgen
 * is on-network) and matches how KafkaPerfTestParityTest already drives
 * Kafka. The transferable knowledge captured here is the WIRING; the
 * remote golden swaps alias-advertised for {@code --network host} +
 * private-IP-advertised.
 *
 * The env-var contract was probed against apache/kafka 3.9.1 first
 * (2026-07-16): the image auto-formats storage from KAFKA_CLUSTER_ID and
 * starts a combined controller+broker from KAFKA_* vars alone; a
 * single-node came up in ~2 s, alias-advertised listeners resolve
 * in-network.
 */
class KraftMultiBrokerFormationTest {

    private static final DockerImageName KAFKA =
            DockerImageName.parse(LocalDockerProvider.KAFKA_IMAGE).asCompatibleSubstituteFor("apache/kafka");
    /** A fixed cluster id shared by every broker (KRaft requires identical
     *  formatting across the quorum). */
    private static final String CLUSTER_ID = "5L6g3nShT-eMCtK--X86s0";
    private static final int N = 3;

    @Test
    void threeBrokerKraftFormsQuorumAndCommitsAcksAllUnderMinIsr2() throws Exception {
        String voters = IntStream.rangeClosed(1, N)
                .mapToObj(i -> i + "@k" + i + ":9093")
                .reduce((a, b) -> a + "," + b).orElseThrow();

        Network net = Network.newNetwork();
        // The explicit type witness collapses the fluent chain's SELF type:
        // each withX returns the container's self-type, which the stream
        // would otherwise infer as List<SELF> and refuse to convert.
        List<GenericContainer<?>> brokers = IntStream.rangeClosed(1, N).<GenericContainer<?>>mapToObj(i ->
                new GenericContainer<>(KAFKA)
                        .withNetwork(net)
                        .withNetworkAliases("k" + i)
                        .withEnv("KAFKA_NODE_ID", Integer.toString(i))
                        .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                        .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", voters)
                        .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093")
                        .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://k" + i + ":9092")
                        .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                        .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT")
                        .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                                "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT")
                        .withEnv("KAFKA_CLUSTER_ID", CLUSTER_ID)
                        // Internal topics need RF=3/min-ISR=2 to form on a
                        // 3-node cluster — the campaign's F6 topology.
                        .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "3")
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "3")
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "2")
                        .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1)
                                .withStartupTimeout(Duration.ofSeconds(90))))
                .toList();

        try {
            // Parallel start: the controller quorum forms only when all three
            // are up, so a sequential start would deadlock on its own gate
            // (the same lesson as the etcd provider).
            Startables.deepStart(brokers).join();
            GenericContainer<?> k1 = brokers.get(0);

            // 1. All three brokers are in the cluster.
            Container.ExecResult apis = k1.execInContainer(
                    "/opt/kafka/bin/kafka-broker-api-versions.sh", "--bootstrap-server", "k1:9092");
            // Each broker prints one header line "host:port (id: N rack: …)";
            // "(id:" is that header's unique marker (API-list lines lack it).
            long brokerCount = apis.getStdout().lines().filter(l -> l.contains("(id:")).count();
            assertEquals(N, brokerCount, "all 3 brokers must join the KRaft cluster:\n" + apis.getStdout());

            // 2. Create the F6 topology topic (RF=3, min.insync.replicas=2).
            Container.ExecResult create = k1.execInContainer(
                    "/opt/kafka/bin/kafka-topics.sh", "--create", "--topic", "bench",
                    "--partitions", "6", "--replication-factor", "3",
                    "--config", "min.insync.replicas=2", "--bootstrap-server", "k1:9092");
            assertEquals(0, create.getExitCode(), "topic create failed:\n" + create.getStderr());

            // 3. acks=all produce must COMMIT under min.insync.replicas=2 —
            //    the real 3-node consensus path (a quorum of brokers fsynced).
            Container.ExecResult produce = k1.execInContainer(
                    "/opt/kafka/bin/kafka-producer-perf-test.sh", "--topic", "bench",
                    "--num-records", "2000", "--record-size", "1024", "--throughput", "-1",
                    "--producer-props", "bootstrap.servers=k1:9092", "acks=all");
            assertEquals(0, produce.getExitCode(),
                    "acks=all produce must commit on the 3-node cluster:\n"
                            + produce.getStdout() + produce.getStderr());
            assertTrue(produce.getStdout().contains("records sent"),
                    "perf-test must report records sent:\n" + produce.getStdout());

            // 4. Replication actually formed: partition 0 has 3 in-sync
            //    replicas (not a degraded 2 that acks=all would still accept).
            Container.ExecResult describe = k1.execInContainer(
                    "/opt/kafka/bin/kafka-topics.sh", "--describe", "--topic", "bench",
                    "--bootstrap-server", "k1:9092");
            String p0 = describe.getStdout().lines()
                    .filter(l -> l.contains("Partition: 0")).findFirst().orElseThrow();
            // Match only the Isr digit list — Kafka 3.7+ appends Elr /
            // LastKnownElr columns AFTER Isr, so a substring-to-end parse
            // would swallow them.
            var m = java.util.regex.Pattern.compile("Isr:\\s*([0-9,]+)").matcher(p0);
            assertTrue(m.find(), "no Isr field in describe line: " + p0);
            assertEquals(3, m.group(1).split(",").length,
                    "partition 0 must have all 3 replicas in-sync, got Isr: " + m.group(1)
                            + " (full line: " + p0 + ")");
        } finally {
            brokers.forEach(GenericContainer::stop);
            net.close();
        }
    }
}
