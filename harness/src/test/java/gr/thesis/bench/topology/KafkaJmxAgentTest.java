package gr.thesis.bench.topology;

import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5.2/P4.3 — the Kafka JMX exporter wiring, verified BY EXECUTION before
 * any golden ships it (the formation-test pattern): a REAL broker (the
 * digest-pinned image both Kafka modes run) started with
 * {@code -javaagent:jmx_prometheus_javaagent.jar=7071:kafka-jmx-rules.yml}
 * must serve the EXACT metric names export_queries.txt and the cb-kafka
 * dashboard consume. Until this test, those names were an unverified
 * guess — the dashboards said so ("pending P4.3").
 *
 * Cross-file contracts pinned here, so they cannot drift silently:
 *  - the agent jar is STAGED BY THE POM (dependency-plugin copy of the
 *    pinned Maven Central artifact) — a missing jar fails closed naming
 *    the fix, never skips;
 *  - cloud-init downloads the SAME pinned version to the SAME VM path the
 *    goldens bind-mount ({@code /opt/thesis/jmx_prometheus_javaagent.jar});
 *  - the rules the provider printf's onto each node are the repo rules
 *    file's functional lines, byte-for-byte (comments stripped).
 */
class KafkaJmxAgentTest {

    /** Pinned agent version — pom staging, cloud-init download, and this
     *  test must agree; the version-pin tests below enforce it. */
    static final String AGENT_VERSION = "1.0.1";

    private static final Path AGENT_JAR = Path.of("target/jmx/jmx_prometheus_javaagent.jar");
    private static final Path RULES_FILE = Path.of("../observability/kafka-jmx-rules.yml");
    private static final Path CLOUD_INIT = Path.of("../infra/cloud-init.yaml");

    @Test
    void agentServesTheExportQueryNamesOnARealBroker() throws Exception {
        requireStagedAgentJar();
        try (KafkaContainer kafka = new KafkaContainer(
                DockerImageName.parse(LocalDockerProvider.KAFKA_IMAGE)
                        .asCompatibleSubstituteFor("apache/kafka"))
                .withCopyFileToContainer(
                        MountableFile.forHostPath(AGENT_JAR.toAbsolutePath()),
                        "/opt/thesis/jmx_prometheus_javaagent.jar")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(RULES_FILE.toAbsolutePath()),
                        "/opt/thesis/kafka-jmx-rules.yml")
                // kafka-run-class.sh appends KAFKA_OPTS to the broker JVM —
                // the same mechanism in KRaft AND ZK mode (one wiring, F6).
                .withEnv("KAFKA_OPTS", "-javaagent:/opt/thesis/jmx_prometheus_javaagent.jar"
                        + "=7071:/opt/thesis/kafka-jmx-rules.yml")) {
            kafka.addExposedPorts(7071);
            kafka.start();

            HttpResponse<String> r = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build()
                    .send(HttpRequest.newBuilder(URI.create("http://" + kafka.getHost()
                                    + ":" + kafka.getMappedPort(7071) + "/metrics"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), "agent must serve /metrics on :7071");
            assertTrue(r.body().contains("kafka_server_replicamanager_underreplicatedpartitions"),
                    "URP gauge (validity gate 3's broker-fault witness) missing:\n"
                            + firstLines(r.body()));
            assertTrue(r.body().contains("kafka_server_replicamanager_isrshrinks_total"),
                    "ISR-shrink counter (the F6 failover series) missing:\n"
                            + firstLines(r.body()));
        }
    }

    @Test
    void cloudInitDownloadsTheSamePinnedAgentVersionToTheMountedPath() throws Exception {
        String cloudInit = Files.readString(CLOUD_INIT);
        assertTrue(cloudInit.contains(
                        "jmx_prometheus_javaagent/" + AGENT_VERSION
                                + "/jmx_prometheus_javaagent-" + AGENT_VERSION + ".jar"),
                "cloud-init must download the pinned agent version " + AGENT_VERSION);
        assertTrue(cloudInit.contains("/opt/thesis/jmx_prometheus_javaagent.jar"),
                "cloud-init must place the jar at the path the goldens bind-mount");
    }

    @Test
    void providerRulesLinesAreTheRepoRulesFileVerbatim() throws Exception {
        // The provider printf's the rules onto each node; the repo file is
        // what THIS suite verified against a real broker. They must be the
        // same functional document — comments stripped, nothing else.
        List<String> functional = Files.readAllLines(RULES_FILE).stream()
                .filter(l -> !l.strip().startsWith("#") && !l.isBlank())
                .toList();
        assertEquals(functional, RemoteSshProvider.KAFKA_JMX_RULES_LINES,
                "the provider ships different rules than the execution-verified repo file");
    }

    private static void requireStagedAgentJar() {
        if (!Files.exists(AGENT_JAR)) {
            throw new IllegalStateException("agent jar not staged at " + AGENT_JAR
                    + " — the pom's dependency-plugin copy (io.prometheus.jmx:"
                    + "jmx_prometheus_javaagent:" + AGENT_VERSION + ") must run first:"
                    + " mvn generate-test-resources");
        }
    }

    private static String firstLines(String body) {
        var lines = body.lines().limit(15).toList();
        return String.join("\n", lines);
    }
}
