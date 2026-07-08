package gr.thesis.bench.topology;

import gr.thesis.bench.core.SystemUnderTest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * ClusterProvider on the local Docker daemon via Testcontainers (P0.2):
 * digest-pinned images (D2 — a "fresh installation" is byte-identical every
 * run), one Docker network per cluster, HTTP readiness gates, and teardown
 * guaranteed even on JVM crash (Ryuk).
 *
 * Cluster shape: members are network-aliased etcd1..etcdN and peer over the
 * cluster-private Docker network on :2380; clients reach each member from
 * the host through its mapped :2379. Containers are started IN PARALLEL
 * because an etcd member only answers /health once quorum exists — a
 * sequential start would deadlock on its own readiness gate.
 *
 * Local clusters are FUNCTIONAL substrate only (environment=local): drivers
 * and fault logic are developed against them; no performance number produced
 * here is thesis data.
 */
public final class LocalDockerProvider implements ClusterProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalDockerProvider.class);

    /** etcd v3.4.30 pinned by REGISTRY DIGEST, not tag (D2). */
    public static final String ETCD_IMAGE =
            "quay.io/coreos/etcd@sha256:5a65b4c67dea6e835c2812cf2fdea064f494c68b509fab939b12c0fa1fa423bb";

    private final List<GenericContainer<?>> containers = new ArrayList<>();
    private final List<String> endpoints = new ArrayList<>();
    private Network network;

    @Override
    public List<NodeHandle> start(SystemUnderTest system, int clusterSize) {
        if (system != SystemUnderTest.ETCD) {
            throw new UnsupportedOperationException(
                    "LocalDockerProvider supports ETCD only for now (P0.2); asked for " + system);
        }
        if (clusterSize < 1) {
            throw new IllegalArgumentException("clusterSize must be >= 1, got " + clusterSize);
        }
        if (network != null) {
            throw new IllegalStateException("provider already started — one cluster per instance");
        }

        // Short random suffix keeps names unique across runs while the
        // thesis- prefix keeps every container findable (and P0.3-cleanable).
        String suffix = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
        network = Network.newNetwork();
        String initialCluster = IntStream.rangeClosed(1, clusterSize)
                .mapToObj(i -> alias(system, i) + "=http://" + alias(system, i) + ":2380")
                .collect(Collectors.joining(","));

        List<NodeHandle> handles = new ArrayList<>(clusterSize);
        for (int i = 1; i <= clusterSize; i++) {
            String alias = alias(system, i);
            String containerName = "thesis-" + alias + "-" + suffix;
            GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(ETCD_IMAGE))
                    .withNetwork(network)
                    .withNetworkAliases(alias)
                    .withExposedPorts(2379)
                    .withCreateContainerCmdModifier(cmd -> cmd.withName(containerName))
                    .withCommand("etcd",
                            "--name", alias,
                            "--listen-client-urls", "http://0.0.0.0:2379",
                            "--advertise-client-urls", "http://" + alias + ":2379",
                            "--listen-peer-urls", "http://0.0.0.0:2380",
                            "--initial-advertise-peer-urls", "http://" + alias + ":2380",
                            "--initial-cluster", initialCluster,
                            "--initial-cluster-state", "new",
                            "--initial-cluster-token", "thesis-" + suffix)
                    // /health returns 200 only once the member sees quorum —
                    // this IS the cluster-formed gate, per container.
                    .waitingFor(Wait.forHttp("/health").forPort(2379).forStatusCode(200)
                            .withStartupTimeout(Duration.ofSeconds(60)));
            containers.add(c);
            handles.add(new NodeHandle(i - 1, containerName, "127.0.0.1", alias));
        }

        log.debug("phase: deploy — starting {} {} container(s) in parallel", clusterSize, system);
        Startables.deepStart(containers).join(); // parallel start; each waits on its /health gate
        for (GenericContainer<?> c : containers) {
            endpoints.add("http://" + c.getHost() + ":" + c.getMappedPort(2379));
        }
        log.debug("phase: wait-healthy done — endpoints {}", endpoints);
        return handles;
    }

    @Override
    public List<String> clientEndpoints() {
        if (endpoints.isEmpty()) {
            throw new IllegalStateException("cluster not started — no endpoints to hand out");
        }
        return List.copyOf(endpoints);
    }

    @Override
    public void stop() {
        log.debug("phase: teardown — stopping {} container(s)", containers.size());
        // stop() on a Testcontainers container stops AND removes it; killed
        // members are removed the same way. Ryuk backstops a JVM crash.
        for (GenericContainer<?> c : containers) {
            c.stop();
        }
        containers.clear();
        endpoints.clear();
        if (network != null) {
            network.close();
            network = null;
        }
    }

    private static String alias(SystemUnderTest system, int oneBasedIndex) {
        return system.containerName(oneBasedIndex); // e.g. etcd1..etcdN
    }

    /**
     * Force-remove every thesis-* container a crashed or aborted earlier run
     * left behind — the idempotent pre-clean local-run (P0.3) performs before
     * deploying. Loud on purpose: a leftover means a previous teardown did
     * not happen, which someone should know.
     *
     * @return how many containers were removed
     */
    public static int removeLeftovers() {
        var client = DockerClientFactory.instance().client();
        var leftovers = client.listContainersCmd().withShowAll(true).exec().stream()
                .filter(c -> java.util.Arrays.stream(c.getNames())
                        .anyMatch(n -> n.startsWith("/thesis-")))
                .toList();
        for (var c : leftovers) {
            log.warn("pre-clean: force-removing leftover container {} (state={})",
                    c.getNames()[0], c.getState());
            client.removeContainerCmd(c.getId()).withForce(true).exec();
        }
        return leftovers.size();
    }
}
