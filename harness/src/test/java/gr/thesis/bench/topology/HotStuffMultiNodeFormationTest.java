package gr.thesis.bench.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3.3d-hotstuff FORMATION — verifies a 4-node HotStuff cluster BY
 * EXECUTION before its golden is written (the KRaft/CometBFT pattern).
 * Runs the DISTRIBUTION-shaped recipe the remote golden will ship: one
 * keygen pass, a committee.json built from the generated pubkeys, one
 * node container per address, and the upstream client as the load
 * generator (the SUT's OWN client is the documented measurement boundary
 * for this system — its logs ARE its metrics).
 *
 * Facts this encodes (source-verified at the pinned commit dc01ac8,
 * probed live 2026-07-17):
 *  - Committee addresses are Rust {@code SocketAddr}s — IPs ONLY, a
 *    hostname/alias will not parse. The test therefore assigns STATIC
 *    container IPs on a dedicated subnet (172.29.5.0/24, nodes .11-.14,
 *    client .20) — which also makes the local shape mirror the
 *    campaign's private-IP committee exactly (F20's spirit).
 *  - THREE ports per node in the committee: consensus :26000,
 *    transactions :26001 (the client's target), mempool :26002; each
 *    authority entry carries name/stake(1)/address(es), epoch 1 — the
 *    exact JSON fab's config.py emits (the redundant 'name' field is
 *    tolerated by serde and kept for byte-parity with upstream).
 *  - `node -vv run` is the log level whose benchmark-feature lines feed
 *    logs.py: the formation assertions below are logs.py's OWN parse
 *    targets ("Committed B\d+ -> <digest>=", "Batch ... contains sample
 *    tx", the client's "Start" line; a "panic" match fails its parse) —
 *    so a green here means a real fab-style SUMMARY is derivable from
 *    these logs, which is what the campaign runner will do (P4.5).
 *  - The client waits for every --nodes address to accept connections
 *    before sending — it doubles as the cluster-up gate.
 */
class HotStuffMultiNodeFormationTest {

    static final String HOTSTUFF_IMAGE = LocalDockerProvider.HOTSTUFF_IMAGE;
    private static final int N = 4;
    private static final String SUBNET = "172.29.5.0/24";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String ip(int oneBased) {
        return "172.29.5.1" + oneBased; // .11-.14, the campaign's shape
    }

    @Test
    void fourNodesCommitClientTrafficThroughHotStuffConsensus() throws Exception {
        requireLocalImage();

        // ---- 1. keygen: four keypairs from the pinned binary ----
        String[] keyJson = new String[N];
        String[] pubKey = new String[N];
        try (GenericContainer<?> gen = new GenericContainer<>(DockerImageName.parse(HOTSTUFF_IMAGE))
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "-c").withCmd(
                        "mkdir -p /keys"
                                + " && node keys --filename /keys/node1.json"
                                + " && node keys --filename /keys/node2.json"
                                + " && node keys --filename /keys/node3.json"
                                + " && node keys --filename /keys/node4.json"
                                + " && echo KEYS_READY && sleep 300"))
                .waitingFor(Wait.forLogMessage(".*KEYS_READY.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(30)))) {
            gen.start();
            for (int i = 0; i < N; i++) {
                var r = gen.execInContainer("cat", "/keys/node" + (i + 1) + ".json");
                assertEquals(0, r.getExitCode(), r.getStderr());
                keyJson[i] = r.getStdout();
                pubKey[i] = JSON.readTree(keyJson[i]).get("name").asText();
            }
        }

        // ---- 2. committee.json — fab config.py's exact shape, real IPs ----
        String committee = committeeJson(pubKey);

        Network net = Network.builder()
                .createNetworkCmdModifier(cmd -> cmd.withIpam(
                        new com.github.dockerjava.api.model.Network.Ipam().withConfig(
                                new com.github.dockerjava.api.model.Network.Ipam.Config()
                                        .withSubnet(SUBNET))))
                .build();
        List<GenericContainer<?>> nodes = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            String myIp = ip(i + 1);
            nodes.add(new GenericContainer<>(DockerImageName.parse(HOTSTUFF_IMAGE))
                    .withNetwork(net)
                    .withCreateContainerCmdModifier(cmd -> cmd.withIpv4Address(myIp))
                    .withCopyToContainer(Transferable.of(keyJson[i]), "/node.json")
                    .withCopyToContainer(Transferable.of(committee), "/committee.json")
                    .withCommand("node", "-vv", "run",
                            "--keys", "/node.json",
                            "--committee", "/committee.json",
                            "--store", "/store")
                    .waitingFor(Wait.forLogMessage(".*successfully booted.*", 1)
                            .withStartupTimeout(Duration.ofSeconds(30))));
        }

        GenericContainer<?> client = new GenericContainer<>(DockerImageName.parse(HOTSTUFF_IMAGE))
                .withNetwork(net)
                .withCreateContainerCmdModifier(cmd -> cmd.withIpv4Address("172.29.5.20"))
                .withCommand(buildClientCommand())
                .waitingFor(Wait.forLogMessage(".*Start sending transactions.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(60)));

        try {
            Startables.deepStart(nodes).join();
            client.start(); // waits for all --nodes addresses, then sends
            Thread.sleep(8_000); // a bounded burst window

            // 3. Every replica commits — logs.py's OWN commit regex, on all
            //    four nodes: the BFT quorum is live and replicating.
            Pattern committed = Pattern.compile("Committed B\\d+ -> [^ ]+=");
            for (int i = 0; i < N; i++) {
                String log = nodes.get(i).getLogs();
                assertTrue(committed.matcher(log).find(),
                        "node" + (i + 1) + " must log commits (logs.py's parse target); got:\n"
                                + tail(log));
                assertFalse(log.contains("panic"),
                        "a panic fails logs.py's parse; node" + (i + 1) + ":\n" + tail(log));
            }
            // 4. The end-to-end sample path exists: some node batched a
            //    sample tx (what logs.py's e2e latency is computed from).
            boolean sampleSeen = nodes.stream()
                    .anyMatch(n -> n.getLogs().contains("contains sample tx"));
            assertTrue(sampleSeen, "no 'contains sample tx' line on any node — "
                    + "logs.py could not compute end-to-end latency");
            // 5. The client actually drove the configured load shape.
            String clientLog = client.getLogs();
            assertTrue(clientLog.contains("Transactions rate: 200 tx/s"), tail(clientLog));
            assertFalse(clientLog.contains("Error"), "client panicked:\n" + tail(clientLog));
        } finally {
            client.stop();
            nodes.forEach(GenericContainer::stop);
            net.close();
        }
    }

    private static String committeeJson(String[] pubKey) {
        ObjectNode consensusAuth = JSON.createObjectNode();
        ObjectNode mempoolAuth = JSON.createObjectNode();
        for (int i = 0; i < N; i++) {
            String addr = ip(i + 1);
            ObjectNode c = JSON.createObjectNode();
            c.put("name", pubKey[i]);
            c.put("stake", 1);
            c.put("address", addr + ":26000");
            consensusAuth.set(pubKey[i], c);
            ObjectNode m = JSON.createObjectNode();
            m.put("name", pubKey[i]);
            m.put("stake", 1);
            m.put("transactions_address", addr + ":26001");
            m.put("mempool_address", addr + ":26002");
            mempoolAuth.set(pubKey[i], m);
        }
        ObjectNode consensus = JSON.createObjectNode();
        consensus.set("authorities", consensusAuth);
        consensus.put("epoch", 1);
        ObjectNode mempool = JSON.createObjectNode();
        mempool.set("authorities", mempoolAuth);
        mempool.put("epoch", 1);
        ObjectNode root = JSON.createObjectNode();
        root.set("consensus", consensus);
        root.set("mempool", mempool);
        return root.toString();
    }

    private static String[] buildClientCommand() {
        List<String> cmd = new ArrayList<>(List.of(
                "client", ip(1) + ":26001",
                "--timeout", "5000", "--size", "512", "--rate", "200", "--nodes"));
        for (int i = 1; i <= N; i++) cmd.add(ip(i) + ":26001");
        return cmd.toArray(String[]::new);
    }

    private static void requireLocalImage() {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(HOTSTUFF_IMAGE).exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            throw new IllegalStateException("image " + HOTSTUFF_IMAGE
                    + " is not built locally — run: docker build -t " + HOTSTUFF_IMAGE
                    + " infra/hotstuff", e);
        }
    }

    private static String tail(String log) {
        var lines = log.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 12), lines.size()));
    }
}
