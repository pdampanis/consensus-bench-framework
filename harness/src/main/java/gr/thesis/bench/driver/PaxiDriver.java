package gr.thesis.bench.driver;

import gr.thesis.bench.core.SystemUnderTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Paxos/EPaxos driver over the Paxi REST API - proves the SPI is
 * implementable with pure JDK (java.net.http). Two concrete fixes over
 * the Python probe it replaces:
 *
 *  1. HttpClient POOLS persistent connections; urllib.request opened a
 *     new TCP connection per PUT, so every latency sample included a
 *     handshake and the client throttled the system under test.
 *  2. sendAsync gives a real in-flight window: the WorkloadEngine can
 *     keep e.g. 200 operations outstanding, so throughput measures the
 *     protocol, not the client's thread count.
 *
 * EPaxos gets all replica endpoints (leaderless design exercised via
 * round-robin); Paxos gets one (leader-based - forwarding is internal).
 */
public final class PaxiDriver implements ConsensusDriver {

    private final SystemUnderTest system;
    private final List<URI> endpoints;
    private final AtomicLong rr = new AtomicLong();
    private HttpClient client;
    private URI[][] putUris; // [endpoint][keyId], precomputed at connect()

    public PaxiDriver(SystemUnderTest system, List<String> baseUrls) {
        if (system != SystemUnderTest.PAXOS && system != SystemUnderTest.EPAXOS)
            throw new IllegalArgumentException("PaxiDriver serves PAXOS/EPAXOS only");
        this.system = system;
        this.endpoints = baseUrls.stream().map(URI::create).toList();
    }

    /** Paxi's native encoding of a workload keyId: an INTEGER in the URL
     *  path — Paxi parses it with Atoi, so it must be numeric-only.
     *  (Re-verify against paxi/http.go when the P2.4 work lands.) */
    static String keyPath(int keyId) {
        return "/" + keyId;
    }

    @Override public SystemUnderTest system() { return system; }

    @Override public void connect() {
        // Precompute every (endpoint, keyId) URI: no parsing per op.
        putUris = new URI[endpoints.size()][KEY_SPACE];
        for (int e = 0; e < endpoints.size(); e++)
            for (int k = 0; k < KEY_SPACE; k++)
                putUris[e][k] = endpoints.get(e).resolve(keyPath(k));
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override public CompletionStage<Void> write(int keyId, byte[] value) {
        int e = (int) (rr.getAndIncrement() % endpoints.size());
        HttpRequest req = HttpRequest.newBuilder(putUris[e][keyId])
                .timeout(Duration.ofSeconds(5))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(value))
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenAccept(resp -> {
                    if (resp.statusCode() != 200)
                        throw new IllegalStateException("paxi status " + resp.statusCode());
                });
    }

    @Override public Optional<Integer> currentLeaderIndex() {
        // EPaxos: leaderless by design. Paxos: real build queries /state on
        // each replica and parses the ballot leader; omitted in skeleton.
        return system == SystemUnderTest.EPAXOS ? Optional.empty() : Optional.of(0);
    }

    @Override public void close() { client = null; }
}
