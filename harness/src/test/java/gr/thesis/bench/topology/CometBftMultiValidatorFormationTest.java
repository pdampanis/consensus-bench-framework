package gr.thesis.bench.topology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3.3d-cometbft PREREQUISITE — verifies the 4-validator CometBFT wiring the
 * campaign will use, on a user-defined Docker network (one alias per node,
 * mirroring one-node-per-VM), BEFORE any of it is written into an SSH golden
 * (the same verify-first step that de-risked the KRaft recipe).
 *
 * Deliberately, the recipe under test is the DISTRIBUTION-shaped one the
 * remote golden will ship — NOT `cometbft testnet`'s own generated
 * config.toml (60 KB of comments no human golden-review can audit):
 *  - `cometbft testnet` runs ONCE as the keygen (shared genesis with all 4
 *    validator pubkeys, per-node priv_validator_key/node_key);
 *  - each node gets only FOUR small JSON files (genesis, both keys, and
 *    data/priv_validator_state.json — init's FilePV loader REQUIRES the
 *    state file once the key is pre-placed);
 *  - `cometbft init` then fills in the default config around the pre-placed
 *    files (it keeps an existing genesis/keys — that behavior is exactly
 *    what this test pins by succeeding);
 *  - peer wiring rides the CLI flag `--p2p.persistent_peers=<id@host:26656>`
 *    (node ids from `cometbft show-node-id`), EXCLUDING self — so the
 *    remote golden never has to distribute or sed a peers line;
 *  - the two config edits that must be sed'ed (no CLI flag exists):
 *    rpc.max_subscription_clients 100→2000 (P2.3's measured fact: each
 *    concurrent broadcast_tx_commit holds one subscription) and
 *    p2p.addr_book_strict=false (both the local Docker net and the
 *    campaign's 10.0.0.0/24 are RFC1918 — strict mode refuses
 *    non-routable addresses).
 *
 * What a green run proves, in order: the testnet keygen contract; that a
 * node assembled from distributed files joins consensus; that all four
 * peers connect (n_peers=3 from node1); that a tx commits through REAL
 * BFT consensus (>2/3 = 3-of-4 precommits — check_tx.code and
 * tx_result.code both 0, the P2.3 commit contract); and that every
 * replica reaches the committed height (state machine replication
 * observable on all nodes).
 */
class CometBftMultiValidatorFormationTest {

    private static final DockerImageName COMETBFT =
            DockerImageName.parse(LocalDockerProvider.COMETBFT_IMAGE)
                    .asCompatibleSubstituteFor("cometbft/cometbft");
    private static final int N = 4;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void fourValidatorTestnetFormsQuorumAndCommitsThroughBftConsensus() throws Exception {
        // ---- 1. keygen: one testnet generation, material extracted ----
        String genesis;
        String[] privKey = new String[N], nodeKey = new String[N],
                 privState = new String[N], ids = new String[N];
        try (GenericContainer<?> gen = new GenericContainer<>(COMETBFT)
                .withCreateContainerCmdModifier(cmd -> cmd.withUser("root")
                        .withEntrypoint("sh", "-c")
                        .withCmd("cometbft testnet --v " + N + " --o /testnet"
                                + " --hostname tm1 --hostname tm2 --hostname tm3 --hostname tm4"
                                + " && sleep 600"))
                .waitingFor(Wait.forLogMessage(".*Successfully initialized.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(30)))) {
            gen.start();
            genesis = execOut(gen, "cat", "/testnet/node0/config/genesis.json");
            for (int i = 0; i < N; i++) {
                privKey[i] = execOut(gen, "cat", "/testnet/node" + i + "/config/priv_validator_key.json");
                nodeKey[i] = execOut(gen, "cat", "/testnet/node" + i + "/config/node_key.json");
                privState[i] = execOut(gen, "cat", "/testnet/node" + i + "/data/priv_validator_state.json");
                // CMTHOME wins over --home on this image (P2.3's probed fact,
                // reconfirmed here: --home fails "no such file", the env works).
                ids[i] = execOut(gen, "sh", "-c",
                        "CMTHOME=/testnet/node" + i + " cometbft show-node-id").trim();
            }
        }

        // ---- 2. four validators from DISTRIBUTED files, one alias each ----
        Network net = Network.newNetwork();
        List<GenericContainer<?>> nodes = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            StringBuilder peers = new StringBuilder();
            for (int j = 0; j < N; j++) {
                if (j == i) continue; // a self-entry is refused by the dialer
                if (peers.length() > 0) peers.append(',');
                peers.append(ids[j]).append("@tm").append(j + 1).append(":26656");
            }
            String script = "cometbft init"
                    + " && sed -i 's/^max_subscription_clients = .*/max_subscription_clients = 2000/'"
                    + " /cometbft/config/config.toml"
                    + " && sed -i 's/^addr_book_strict = .*/addr_book_strict = false/'"
                    + " /cometbft/config/config.toml"
                    + " && cometbft start --proxy_app=kvstore"
                    + " --rpc.laddr=tcp://0.0.0.0:26657"
                    + " --p2p.laddr=tcp://0.0.0.0:26656"
                    + " --p2p.persistent_peers=" + peers;
            nodes.add(new GenericContainer<>(COMETBFT)
                    .withNetwork(net)
                    .withNetworkAliases("tm" + (i + 1))
                    .withExposedPorts(26657)
                    .withCopyToContainer(Transferable.of(genesis), "/cometbft/config/genesis.json")
                    .withCopyToContainer(Transferable.of(privKey[i]), "/cometbft/config/priv_validator_key.json")
                    .withCopyToContainer(Transferable.of(nodeKey[i]), "/cometbft/config/node_key.json")
                    .withCopyToContainer(Transferable.of(privState[i]), "/cometbft/data/priv_validator_state.json")
                    .withCreateContainerCmdModifier(cmd -> cmd.withUser("root")
                            .withEntrypoint("sh", "-c").withCmd(script))
                    .waitingFor(Wait.forHttp("/health").forPort(26657).forStatusCode(200)
                            .withStartupTimeout(Duration.ofSeconds(60))));
        }

        try {
            Startables.deepStart(nodes).join();
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();
            String rpc1 = rpc(nodes.get(0));

            // 3. All three peers connect to node1 (persistent dial).
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            int nPeers = -1;
            while (System.nanoTime() < deadline) {
                nPeers = JSON.readTree(get(http, rpc1 + "/net_info"))
                        .path("result").path("n_peers").asInt(-1);
                if (nPeers == N - 1) break;
                Thread.sleep(500);
            }
            assertEquals(N - 1, nPeers, "node1 must connect to the other " + (N - 1) + " validators");

            // 4. A tx commits through REAL BFT consensus (3-of-4 precommits).
            String tx = HexFormat.of().formatHex(
                    ("bench=" + System.nanoTime()).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            JsonNode resp = JSON.readTree(get(http, rpc1 + "/broadcast_tx_commit?tx=0x" + tx));
            assertTrue(resp.path("error").isMissingNode(), "rpc error: " + resp);
            assertEquals(0, resp.path("result").path("check_tx").path("code").asInt(-1),
                    "CheckTx must accept: " + resp);
            assertEquals(0, resp.path("result").path("tx_result").path("code").asInt(-1),
                    "the tx must be in a committed block: " + resp);
            long committedAt = resp.path("result").path("height").asLong(-1);
            assertTrue(committedAt > 0, "committed height must be positive: " + resp);

            // 5. Every replica reaches the committed height — replication is
            //    observable on all four nodes, not just the RPC entry point.
            for (GenericContainer<?> node : nodes) {
                long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                long h = -1;
                while (System.nanoTime() < end) {
                    h = JSON.readTree(get(http, rpc(node) + "/status"))
                            .path("result").path("sync_info").path("latest_block_height").asLong(-1);
                    if (h >= committedAt) break;
                    Thread.sleep(500);
                }
                assertTrue(h >= committedAt, "replica " + node.getMappedPort(26657)
                        + " stuck at height " + h + " < committed " + committedAt);
            }
        } finally {
            nodes.forEach(GenericContainer::stop);
            net.close();
        }
    }

    private static String rpc(GenericContainer<?> node) {
        return "http://" + node.getHost() + ":" + node.getMappedPort(26657);
    }

    private static String get(HttpClient http, String url) throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), url + " -> HTTP " + r.statusCode());
        return r.body();
    }

    private static String execOut(GenericContainer<?> c, String... cmd) throws Exception {
        Container.ExecResult r = c.execInContainer(cmd);
        assertEquals(0, r.getExitCode(),
                String.join(" ", cmd) + " failed: " + r.getStderr());
        return r.getStdout();
    }
}
