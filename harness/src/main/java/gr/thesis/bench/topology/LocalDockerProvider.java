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

    /** apache/kafka 3.9.1 pinned by REGISTRY DIGEST (D2) — KRaft combined
     *  controller+broker mode, the KafkaDriver's substrate (P2.2a). */
    public static final String KAFKA_IMAGE =
            "apache/kafka@sha256:4ceccc577f03f51f6af8dbfda55194d0d892f4fa7913ffbded567ce3895622ed";

    /** cometbft v0.38.17 pinned by REGISTRY DIGEST (D2) — single-validator
     *  chain with the in-process kvstore ABCI app (P2.3a). */
    public static final String COMETBFT_IMAGE =
            "cometbft/cometbft@sha256:22c2ac018f40665e5c113e485d81531d8421f4fc76f9c8b013fbb6c0c16e150d";

    /** zookeeper 3.9 pinned by REGISTRY DIGEST (D2) — the KAFKA_ZK (D10)
     *  ensemble; ZK 3.6+ ships the PrometheusMetricsProvider the :7000
     *  scrape job expects (enabled via ZOO_CFG_EXTRA, probed 2026-07-17). */
    public static final String ZOOKEEPER_IMAGE =
            "zookeeper@sha256:4c6f15fbd5491a3e01b0108c046891125553329a4956848ba3014cedff5386ee";

    /** Paxi (Paxos/EPaxos, SIGMOD'19) — built from PINNED SOURCE because the
     *  project publishes no image. A local build has no registry digest, so
     *  the D2 pin is the source commit baked into infra/paxi/Dockerfile
     *  (6823d0b). Not pullable: build it once with
     *  {@code docker build -t paxi:6823d0b infra/paxi} — start() fails
     *  closed with that instruction when the image is absent. */
    public static final String PAXI_IMAGE = "paxi:6823d0b";

    /** HotStuff (asonnino/hotstuff, PODC'19 research implementation) — built
     *  from PINNED SOURCE (infra/hotstuff/Dockerfile, commit dc01ac8);
     *  upstream ships no Cargo.lock, so the recorded image id (8501e107d4bf)
     *  is the reproducibility anchor. Not pullable: build once with
     *  {@code docker build -t hotstuff:dc01ac8 infra/hotstuff}. */
    public static final String HOTSTUFF_IMAGE = "hotstuff:dc01ac8";

    private final List<GenericContainer<?>> containers = new ArrayList<>();
    private final List<String> endpoints = new ArrayList<>();
    private Network network;

    @Override
    public List<NodeHandle> start(SystemUnderTest system, int clusterSize) {
        if (system != SystemUnderTest.ETCD && system != SystemUnderTest.KRAFT
                && system != SystemUnderTest.TENDERMINT
                && system != SystemUnderTest.PAXOS && system != SystemUnderTest.EPAXOS) {
            throw new UnsupportedOperationException(
                    "LocalDockerProvider supports ETCD/KRAFT/TENDERMINT/PAXOS/EPAXOS for now; asked for "
                            + system);
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
        if (system == SystemUnderTest.KRAFT) {
            return startKraft(clusterSize, suffix);
        }
        if (system == SystemUnderTest.TENDERMINT) {
            return startCometBft(clusterSize, suffix);
        }
        if (system == SystemUnderTest.PAXOS || system == SystemUnderTest.EPAXOS) {
            return startPaxi(system, clusterSize, suffix);
        }
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

    /**
     * Single-node KRaft (combined controller+broker) via the Testcontainers
     * kafka module — it owns the advertised-listener/mapped-port bootstrap
     * dance (the broker must advertise the host-mapped port, which is only
     * known after start; hand-rolling that is exactly the fiddly glue this
     * provider exists to avoid). Multi-broker KRaft needs full listener and
     * controller-quorum wiring per node and lands with the remote provider
     * work — claiming it now would be v6's ship-unverified sin, so it fails
     * closed instead.
     */
    private List<NodeHandle> startKraft(int clusterSize, String suffix) {
        if (clusterSize != 1) {
            throw new UnsupportedOperationException(
                    "KRAFT supports size 1 only for now (P2.2a); asked for " + clusterSize);
        }
        String alias = alias(SystemUnderTest.KRAFT, 1); // "k1"
        String containerName = "thesis-" + alias + "-" + suffix;
        org.testcontainers.kafka.KafkaContainer c =
                new org.testcontainers.kafka.KafkaContainer(DockerImageName.parse(KAFKA_IMAGE)
                        .asCompatibleSubstituteFor("apache/kafka"))
                        .withNetwork(network)
                        .withNetworkAliases(alias)
                        .withCreateContainerCmdModifier(cmd -> cmd.withName(containerName));
        containers.add(c);
        log.debug("phase: deploy — starting 1 KRAFT container");
        c.start();
        // Normalize to bare host:port — the drivers' endpoint contract.
        String bootstrap = c.getBootstrapServers();
        int proto = bootstrap.indexOf("://");
        endpoints.add(proto >= 0 ? bootstrap.substring(proto + 3) : bootstrap);
        log.debug("phase: wait-healthy done — endpoints {}", endpoints);
        return List.of(new NodeHandle(0, containerName, "127.0.0.1", alias));
    }

    /**
     * Single-validator CometBFT chain with the in-process kvstore ABCI app.
     * Facts probed against the real image (2026-07-15, v0.38.17):
     *  - `--home` is NOT honored (CMTHOME=/cometbft wins) — init and start
     *    both use the image default, so the config edit targets
     *    /cometbft/config/config.toml;
     *  - rpc.max_subscription_clients defaults to 100 and each concurrent
     *    broadcast_tx_commit holds one subscription: measured 99/250
     *    committed at the default, 250/250 at 2000 — the window >=200 load
     *    model REQUIRES the raise (the plan's "CometBFT RPC at 200
     *    in-flight" risk, now measured and closed).
     * Multi-validator testnets need per-node keys/genesis wiring and land
     * with the campaign provider — fails closed until then.
     */
    private List<NodeHandle> startCometBft(int clusterSize, String suffix) {
        if (clusterSize != 1) {
            throw new UnsupportedOperationException(
                    "TENDERMINT supports size 1 only for now (P2.3a); asked for " + clusterSize);
        }
        String alias = alias(SystemUnderTest.TENDERMINT, 1); // "tm1"
        String containerName = "thesis-" + alias + "-" + suffix;
        String script = "cometbft init"
                + " && sed -i 's/^max_subscription_clients = .*/max_subscription_clients = 2000/'"
                + " /cometbft/config/config.toml"
                + " && cometbft start --proxy_app=kvstore --rpc.laddr=tcp://0.0.0.0:26657";
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(COMETBFT_IMAGE))
                .withNetwork(network)
                .withNetworkAliases(alias)
                .withExposedPorts(26657)
                .withCreateContainerCmdModifier(cmd -> cmd.withName(containerName)
                        .withUser("root")               // default home is root-owned
                        .withEntrypoint("sh", "-c")
                        .withCmd(script))
                .waitingFor(Wait.forHttp("/health").forPort(26657).forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(60)));
        containers.add(c);
        log.debug("phase: deploy — starting 1 TENDERMINT container (kvstore app)");
        c.start();
        endpoints.add("http://" + c.getHost() + ":" + c.getMappedPort(26657));
        log.debug("phase: wait-healthy done — endpoints {}", endpoints);
        return List.of(new NodeHandle(0, containerName, "127.0.0.1", alias));
    }

    /**
     * 3-node Paxi (P2.4a) — one binary serves both PAXOS and EPAXOS via
     * {@code -algorithm}. Facts verified against the pinned source
     * (2026-07-16, commit 6823d0b — see PENDING_TASKS F22):
     *  - config.json needs only the two address maps; Config.Load() decodes
     *    into a defaults-prefilled singleton (config.go). IDs are Zone.Node
     *    ("1.1".."1.3"); http binds ":"+port of its URL, host part is
     *    name-resolution only.
     *  - Leader election is LAZY: the first client request triggers Phase-1a
     *    over the peer mesh. There is no /health; the only honest readiness
     *    gate is a COMMITTED PROBE WRITE — an ungated start would hand out a
     *    cluster whose first (measured!) requests hang in election.
     *  - A send to a not-yet-reachable peer retries its dial 100x50 ms (~5 s,
     *    socket.go) before panicking, so parallel start + probe-retry is safe.
     *  - Default flags are the thesis semantics: -adaptive=true (stable
     *    leader, internal forwarding) and reply-on-EXECUTE (commit + apply,
     *    matching etcd's semantics) — neither is overridden.
     */
    private List<NodeHandle> startPaxi(SystemUnderTest system, int clusterSize, String suffix) {
        if (clusterSize != 3) {
            throw new UnsupportedOperationException(
                    system + " supports the thesis shape of 3 only (P2.4a); asked for " + clusterSize);
        }
        requireLocalImage(PAXI_IMAGE, "docker build -t " + PAXI_IMAGE + " infra/paxi");

        StringBuilder addrs = new StringBuilder();
        StringBuilder https = new StringBuilder();
        for (int i = 1; i <= clusterSize; i++) {
            if (i > 1) { addrs.append(", "); https.append(", "); }
            addrs.append("\"1.").append(i).append("\": \"tcp://").append(alias(system, i)).append(":1735\"");
            https.append("\"1.").append(i).append("\": \"http://").append(alias(system, i)).append(":8080\"");
        }
        String config = "{ \"address\": {" + addrs + "}, \"http_address\": {" + https + "} }";
        String algorithm = system == SystemUnderTest.EPAXOS ? "epaxos" : "paxos";

        List<NodeHandle> handles = new ArrayList<>(clusterSize);
        for (int i = 1; i <= clusterSize; i++) {
            String alias = alias(system, i);
            String containerName = "thesis-" + alias + "-" + suffix;
            GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(PAXI_IMAGE))
                    .withNetwork(network)
                    .withNetworkAliases(alias)
                    .withExposedPorts(8080)
                    .withCreateContainerCmdModifier(cmd -> cmd.withName(containerName))
                    .withCopyToContainer(
                            org.testcontainers.images.builder.Transferable.of(config), "/config.json")
                    .withCommand("-id", "1." + i, "-algorithm", algorithm)
                    // Listening ports are all a per-container gate can see
                    // (no /health, election is lazy); the cluster-level gate
                    // is the probe write below.
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(30)));
            containers.add(c);
            handles.add(new NodeHandle(i - 1, containerName, "127.0.0.1", alias));
        }

        log.debug("phase: deploy — starting {} {} container(s) in parallel", clusterSize, system);
        Startables.deepStart(containers).join();
        for (GenericContainer<?> c : containers) {
            endpoints.add("http://" + c.getHost() + ":" + c.getMappedPort(8080));
        }
        probeCommittedWrite(endpoints.get(0), Duration.ofSeconds(30));
        log.debug("phase: wait-healthy done (probe write committed) — endpoints {}", endpoints);
        return handles;
    }

    /** Fail closed, with the fix in the message, when a build-from-source
     *  image is absent — Testcontainers would otherwise fail trying to PULL
     *  an image that exists in no registry. */
    private static void requireLocalImage(String image, String buildCommand) {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(image).exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            throw new IllegalStateException(
                    "image " + image + " is not built locally — run: " + buildCommand, e);
        }
    }

    /** The Paxi quorum gate: retry one bounded write until it commits (200).
     *  First success proves leader elected + majority mesh up. */
    private static void probeCommittedWrite(String endpoint, Duration deadline) {
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(endpoint + "/1"))
                .timeout(Duration.ofSeconds(5))
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(new byte[8]))
                .build();
        long end = System.nanoTime() + deadline.toNanos();
        Exception last = null;
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            while (System.nanoTime() < end) {
                try {
                    var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
                    if (resp.statusCode() == 200) return;
                    last = new IllegalStateException("probe write returned HTTP " + resp.statusCode());
                } catch (Exception e) {
                    last = e;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException(
                "paxi cluster failed its probe-write quorum gate within " + deadline, last);
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
