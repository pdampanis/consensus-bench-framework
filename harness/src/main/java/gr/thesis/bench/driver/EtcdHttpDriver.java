package gr.thesis.bench.driver;

import gr.thesis.bench.core.SystemUnderTest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * etcd driver over the v3 gRPC-gateway JSON API (POST /v3/kv/put with
 * base64 key/value). Pure JDK - used for the M0 vertical slice and kept
 * as a dependency-free fallback; the production driver (M2.1) is jetcd,
 * whose native gRPC path avoids the gateway translation hop. A PUT 200
 * response means the entry passed the full Raft commit path (WAL fsync +
 * apply) - the response carries the raft_term to prove it.
 */
public final class EtcdHttpDriver implements ConsensusDriver {

    private static final Logger log = LoggerFactory.getLogger(EtcdHttpDriver.class);

    private final URI putUri;
    private HttpClient client;
    private String[] keyB64; // per-keyId base64 key, precomputed at connect()
    private static final Base64.Encoder B64 = Base64.getEncoder();

    public EtcdHttpDriver(String endpoint) {
        this.putUri = URI.create(endpoint + "/v3/kv/put");
    }

    /** etcd's native encoding of a workload keyId: a path-style string key. */
    static byte[] encodeKey(int keyId) {
        return ("bench/k" + keyId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override public SystemUnderTest system() { return SystemUnderTest.ETCD; }

    @Override public void connect() {
        log.debug("phase: connect — pooled HttpClient for {}", putUri);
        // Precompute all KEY_SPACE base64 keys: no string building per op.
        keyB64 = new String[KEY_SPACE];
        for (int i = 0; i < KEY_SPACE; i++) keyB64[i] = B64.encodeToString(encodeKey(i));
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)   // pooled keep-alive
                .build();
    }

    @Override public CompletionStage<Void> write(int keyId, byte[] value) {
        String body = "{\"key\":\"" + keyB64[keyId]
                + "\",\"value\":\"" + B64.encodeToString(value) + "\"}";
        HttpRequest req = HttpRequest.newBuilder(putUri)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenAccept(r -> {
                    if (r.statusCode() != 200)
                        throw new IllegalStateException("etcd status " + r.statusCode());
                });
    }

    @Override public Optional<Integer> currentLeaderIndex() {
        // M2.1: query /v3/maintenance/status per endpoint and match leader
        // member id to the node index. Single-node M0 slice: index 0.
        return Optional.of(0);
    }

    @Override public void close() {
        log.debug("phase: driver close");
        // JDK 21 HttpClient is AutoCloseable: close() releases the selector/
        // executor threads. It waits for in-flight requests, all of which are
        // bounded at 5 s here, so teardown cannot hang (F17 residual).
        if (client != null) client.close();
        client = null;
    }
}
