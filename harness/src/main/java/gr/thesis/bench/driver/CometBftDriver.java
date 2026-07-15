package gr.thesis.bench.driver;

import gr.thesis.bench.core.SystemUnderTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CometBFT/Tendermint driver (P2.3) — async `broadcast_tx_commit` over the
 * RPC HTTP API against the kvstore ABCI app. THE FLAW-A FIX: the retired
 * probe's 6 blocking threads capped measurable throughput at ~6 tx/s
 * (clients ÷ ~1 s block interval); this driver keeps a deep in-flight
 * window outstanding, so throughput measures the protocol.
 *
 * Every non-obvious rule below was PROBED against cometbft v0.38.17
 * (2026-07-15), not assumed:
 *  - A completed stage means the tx is in a COMMITTED block (>2/3
 *    precommits) and both codes are zero. HTTP 200 alone means nothing:
 *    a failed CheckTx returns 200 with result.check_tx.code != 0, and
 *    v0.38 names the DeliverTx result "tx_result". Duplicate txs return
 *    200 with a top-level JSON-RPC "error" object.
 *  - tx bytes must be UNIQUE (mempool cache rejects duplicates), so every
 *    tx carries a nonce seeded from nanoTime at connect() — unique within
 *    and ACROSS runs on the same chain. kvstore's CheckTx splits the tx on
 *    '=' and requires EXACTLY two parts (code 2 otherwise — measured), so
 *    the nonce rides as ASCII hex characters and the value bytes must not
 *    contain 0x3d (the engine's zeroed payload never does).
 *  - The 5 s request timeout implements the F18 bounded-completion
 *    contract; block-inclusion latency (~1 s cadence) sits well inside it.
 *
 * Latency semantics (methodology): CometBFT latency STRUCTURALLY includes
 * the wait for block inclusion — stated wherever its numbers appear.
 */
public final class CometBftDriver implements ConsensusDriver {

    private static final Logger log = LoggerFactory.getLogger(CometBftDriver.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    private final List<String> endpoints; // RPC base URLs, node order
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong rr = new AtomicLong(); // txs accepted by any node
    private long nonceBase;
    private HttpClient client;
    private String[] txPrefixHex; // hex("k<id>=") per keyId

    public CometBftDriver(List<String> rpcBaseUrls) {
        if (rpcBaseUrls.isEmpty()) throw new IllegalArgumentException("no endpoints");
        this.endpoints = List.copyOf(rpcBaseUrls);
    }

    /** kvstore's native key part: the tx is "k<id>=<value>". CheckTx splits
     *  on '=' and demands exactly two parts, so this is the ONLY '=' the tx
     *  may contain — keyId stays the contention key. */
    static byte[] txPrefix(int keyId) {
        return ("k" + keyId + "=").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override public SystemUnderTest system() { return SystemUnderTest.TENDERMINT; }

    @Override public void connect() throws Exception {
        log.debug("phase: connect — CometBFT RPC for {}", endpoints);
        txPrefixHex = new String[KEY_SPACE];
        for (int i = 0; i < KEY_SPACE; i++) txPrefixHex[i] = HEX.formatHex(txPrefix(i));
        nonceBase = System.nanoTime(); // tx uniqueness across runs on one chain
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        // Fail closed before any latency sample: /health answers 200 only
        // from a live RPC server.
        HttpResponse<Void> health = client.send(
                HttpRequest.newBuilder(URI.create(endpoints.get(0) + "/health")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        if (health.statusCode() != 200) {
            throw new IllegalStateException("cometbft /health returned " + health.statusCode());
        }
    }

    @Override public CompletionStage<Void> write(int keyId, byte[] value) {
        // tx = "k<id>=" + nonce-as-16-ASCII-HEX-CHARS + payload, all hex-
        // encoded for the URL. The nonce MUST ride as ascii hex characters,
        // not raw long bytes: kvstore's CheckTx splits the tx on '=' and
        // requires EXACTLY two parts, and a raw 8-byte nonce contains 0x3d
        // ('=') often enough to fail ~12% of txs with check_tx.code=2
        // (measured red). Hex chars never include '='; the engine's zeroed
        // payload cannot either. Any node accepts txs (gossip to the
        // proposer is the protocol's job) — round-robin.
        String nonceAscii = HexFormat.of().toHexDigits(nonceBase + seq.getAndIncrement());
        String tx = txPrefixHex[keyId]
                + HEX.formatHex(nonceAscii.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                + HEX.formatHex(value);
        String base = endpoints.get((int) (rr.getAndIncrement() % endpoints.size()));
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(base + "/broadcast_tx_commit?tx=0x" + tx))
                .timeout(Duration.ofSeconds(5)) // F18 bounded completion
                .GET()
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(CometBftDriver::requireCommitted);
    }

    /** Probed contract: success = 200 AND no JSON-RPC error AND
     *  check_tx.code == 0 AND tx_result.code == 0. Anything else throws —
     *  an accepted-but-uncommitted tx must never count as a commit. */
    private static void requireCommitted(HttpResponse<String> resp) {
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("cometbft HTTP " + resp.statusCode());
        }
        try {
            JsonNode root = JSON.readTree(resp.body());
            JsonNode error = root.get("error");
            if (error != null) {
                throw new IllegalStateException("cometbft rpc error: " + error.path("data").asText());
            }
            JsonNode result = root.path("result");
            int checkCode = result.path("check_tx").path("code").asInt(-1);
            int txCode = result.path("tx_result").path("code").asInt(-1);
            if (checkCode != 0 || txCode != 0) {
                throw new IllegalStateException(
                        "tx not committed: check_tx.code=" + checkCode + " tx_result.code=" + txCode);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("unparseable cometbft response", e);
        }
    }

    @Override public Optional<Integer> currentLeaderIndex() {
        // Tendermint's proposer ROTATES deterministically every height —
        // there is no stable leader to return. The proposer-kill fault
        // (which proposer, detected how) is preregistered at P3.3 with the
        // golden tests; claiming an index here would be the v6 bug.
        return Optional.empty();
    }

    @Override public void close() {
        log.debug("phase: driver close");
        if (client != null) client.close();
    }
}
