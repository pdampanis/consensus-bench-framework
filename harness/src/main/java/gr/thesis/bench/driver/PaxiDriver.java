package gr.thesis.bench.driver;

import gr.thesis.bench.core.SystemUnderTest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * PRODUCTION Paxos/EPaxos driver over the Paxi REST API (P2.4b). Pure JDK
 * (java.net.http). Two concrete fixes over the Python probe it replaces:
 *
 *  1. HttpClient POOLS persistent connections; urllib.request opened a
 *     new TCP connection per PUT, so every latency sample included a
 *     handshake and the client throttled the system under test.
 *  2. sendAsync gives a real in-flight window: the WorkloadEngine can
 *     keep e.g. 200 operations outstanding, so throughput measures the
 *     protocol, not the client's thread count.
 *
 * Facts verified against the pinned source (ailidani/paxi @ 6823d0b,
 * 2026-07-16 — PENDING_TASKS F22/F24/F26):
 *  - The key is Atoi-parsed from the URL path (http.go), so it must be
 *    numeric-only; a 200 reply means the command was committed AND
 *    executed (reply-on-EXECUTE default — same semantics as etcd's
 *    committed-and-applied).
 *  - There is NO /state endpoint. The leader is identified from the
 *    {@code Ballot} response header every executed paxos command returns
 *    ("n.zone.node"; the ID part IS the leader — paxi's own client parses
 *    leadership exactly this way).
 *  - Endpoint strategy (F24): the endpoint list is NODE-ORDERED for both
 *    systems (index identity for fault targeting). PAXOS pins every write
 *    to endpoint 0 — one client entry; a non-leader entry forwards
 *    internally (documented hop), though in practice the provider's
 *    probe-write gate makes node 0 the elected leader. EPAXOS round-robins
 *    all endpoints: leaderless, and single-endpoint traffic could never
 *    exercise it (the retired probe's exact mistake).
 */
public final class PaxiDriver implements ConsensusDriver {

    private static final Logger log = LoggerFactory.getLogger(PaxiDriver.class);

    /** Probe traffic (connect gate, leader detection) rides key 1 — a real
     *  workload key, but outside the measurement window; key 0 is the D7
     *  conflict key and stays exclusive to conflict traffic. */
    private static final int PROBE_KEY = 1;

    private final SystemUnderTest system;
    private final List<URI> endpoints;
    private final AtomicLong rr = new AtomicLong();
    private HttpClient client;
    private URI[][] putUris; // [endpoint][keyId], precomputed at connect()

    public PaxiDriver(SystemUnderTest system, List<String> baseUrls) {
        if (system != SystemUnderTest.PAXOS && system != SystemUnderTest.EPAXOS)
            throw new IllegalArgumentException("PaxiDriver serves PAXOS/EPAXOS only");
        if (baseUrls.isEmpty()) throw new IllegalArgumentException("no endpoints");
        this.system = system;
        this.endpoints = baseUrls.stream().map(URI::create).toList();
    }

    /** Paxi's native encoding of a workload keyId: an INTEGER in the URL
     *  path — paxi Atoi-parses it (http.go, source-verified F22). */
    static String keyPath(int keyId) {
        return "/" + keyId;
    }

    /**
     * F24 write-path strategy, pinned by test: PAXOS = one client entry
     * (endpoint 0, forwarding internal); EPAXOS = round-robin (leaderless).
     */
    static int endpointIndexFor(SystemUnderTest system, long seq, int endpointCount) {
        return system == SystemUnderTest.EPAXOS ? (int) (seq % endpointCount) : 0;
    }

    /**
     * Maps a paxi {@code Ballot} header ("n.zone.node") to the leader's
     * node index. Single-zone clusters only ("1.<node>", node 1-based →
     * index 0-based); anything else — malformed, zone != 1, node < 1 — is
     * a topology surprise and fails LOUD: acting on a misparsed leader
     * would kill the wrong node (the v6 bug class, same rule as
     * KafkaDriver's unmappable-leader throw).
     */
    static int leaderNodeIndexFromBallot(String ballot) {
        String[] parts = ballot == null ? new String[0] : ballot.split("\\.");
        try {
            if (parts.length != 3) throw new NumberFormatException("expected n.zone.node");
            Integer.parseInt(parts[0]); // ballot counter — must be numeric
            int zone = Integer.parseInt(parts[1]);
            int node = Integer.parseInt(parts[2]);
            if (zone != 1 || node < 1) {
                throw new IllegalStateException(
                        "ballot " + ballot + " is not from a single-zone cluster (zone "
                                + zone + ", node " + node + ") — refusing to map a leader");
            }
            return node - 1;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("unparseable paxi ballot header: <" + ballot + ">", e);
        }
    }

    @Override public SystemUnderTest system() { return system; }

    @Override public void connect() throws Exception {
        log.debug("phase: connect — pooled HttpClient for {}", endpoints);
        // Precompute every (endpoint, keyId) URI: no parsing per op.
        putUris = new URI[endpoints.size()][KEY_SPACE];
        for (int e = 0; e < endpoints.size(); e++)
            for (int k = 0; k < KEY_SPACE; k++)
                putUris[e][k] = endpoints.get(e).resolve(keyPath(k));
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        // Fail closed HERE, not at the first measured op — and warm the
        // pooled connection, so no latency sample ever includes a TCP
        // handshake (the flaw-B lesson).
        HttpResponse<Void> probe = probePut(0);
        if (probe.statusCode() != 200) {
            throw new IllegalStateException(
                    "paxi connect probe returned HTTP " + probe.statusCode()
                            + " from " + endpoints.get(0));
        }
    }

    @Override public CompletionStage<Void> write(int keyId, byte[] value) {
        int e = endpointIndexFor(system, rr.getAndIncrement(), endpoints.size());
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

    /**
     * PAXOS: one bounded probe write to endpoint 0 and the Ballot header of
     * the reply — the reply is built by the LEADER regardless of entry (a
     * non-leader forwards and relays the leader's reply), so the header is
     * authoritative from any healthy entry. A failed probe (non-200, IO,
     * timeout) yields empty — no committed write, no leader claim (an
     * election or a dead cluster; honest absence, like EtcdDriver). A
     * committed reply WITHOUT a parseable single-zone ballot fails loud.
     * EPAXOS: leaderless by design — always empty.
     */
    @Override public Optional<Integer> currentLeaderIndex() {
        if (system == SystemUnderTest.EPAXOS) return Optional.empty();
        HttpResponse<Void> resp;
        try {
            resp = probePut(0);
        } catch (Exception e) {
            log.debug("leader probe on {} failed: {}", endpoints.get(0), e.toString());
            return Optional.empty();
        }
        if (resp.statusCode() != 200) {
            log.debug("leader probe returned HTTP {} — no leader claim", resp.statusCode());
            return Optional.empty();
        }
        String ballot = resp.headers().firstValue("Ballot").orElseThrow(() ->
                new IllegalStateException("committed paxi reply carried no Ballot header — "
                        + "leader detection contract broken (paxi version drift?)"));
        return Optional.of(leaderNodeIndexFromBallot(ballot));
    }

    /** Synchronous bounded PUT of the probe key — shared by the connect
     *  gate and leader detection. Never called on the measured path. */
    private HttpResponse<Void> probePut(int endpointIdx) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(putUris[endpointIdx][PROBE_KEY])
                .timeout(Duration.ofSeconds(5))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[8]))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.discarding());
    }

    @Override public void close() {
        log.debug("phase: driver close");
        // JDK 21 HttpClient.close() releases the client's threads; every
        // request is bounded at 5 s, so this cannot hang (F17 residual).
        if (client != null) client.close();
        client = null;
    }
}
