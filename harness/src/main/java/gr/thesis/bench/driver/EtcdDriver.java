package gr.thesis.bench.driver;

import gr.thesis.bench.core.SystemUnderTest;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.maintenance.StatusResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * PRODUCTION etcd driver (M2.1): jetcd's native async gRPC path — no
 * JSON-gateway translation hop inside the latency samples (EtcdHttpDriver
 * remains as the dependency-free fallback and as G3's independent
 * cross-check on the same cluster; both write the SAME keys).
 *
 * A completed put() future means the entry passed the full Raft commit
 * path (leader replication to majority + WAL fsync + apply) — identical
 * commit semantics to the HTTP driver's 200.
 *
 * Leader detection: every endpoint is asked for its maintenance status;
 * the endpoint whose OWN member id equals the leader id it reports is the
 * leader, and its position in the endpoints list is the node index (the
 * list comes from ClusterProvider.clientEndpoints() in node order). This
 * is first-class because v6 regressed to "kill node1 and hope" — half of
 * those leader_kill cells would have measured follower loss.
 */
public final class EtcdDriver implements ConsensusDriver {

    private static final Logger log = LoggerFactory.getLogger(EtcdDriver.class);

    private final List<String> endpoints; // node order — index IS the node index
    private Client client;
    private KV kv;
    private ByteSequence[] keys;          // per-keyId encodings, built at connect()

    public EtcdDriver(List<String> endpoints) {
        if (endpoints.isEmpty()) throw new IllegalArgumentException("no endpoints");
        this.endpoints = List.copyOf(endpoints);
    }

    /** SAME key contract as EtcdHttpDriver — G3 cross-validates the two
     *  drivers on one cluster, so they must address one keyspace. */
    static byte[] encodeKey(int keyId) {
        return EtcdHttpDriver.encodeKey(keyId);
    }

    @Override public SystemUnderTest system() { return SystemUnderTest.ETCD; }

    @Override public void connect() throws Exception {
        log.debug("phase: connect — jetcd client for {}", endpoints);
        // Precompute every key encoding: zero string/byte building per op.
        keys = new ByteSequence[KEY_SPACE];
        for (int i = 0; i < KEY_SPACE; i++) keys[i] = ByteSequence.from(encodeKey(i));
        client = Client.builder()
                .endpoints(endpoints.toArray(String[]::new))
                .build();
        kv = client.getKVClient();
        // Fail closed HERE, not at the first measured op: a status probe
        // proves the cluster answers before any latency sample is taken.
        client.getMaintenanceClient()
                .statusMember(endpoints.get(0))
                .get(10, TimeUnit.SECONDS);
    }

    @Override public CompletionStage<Void> write(int keyId, byte[] value) {
        return kv.put(keys[keyId], ByteSequence.from(value)).thenApply(r -> null);
    }

    @Override public Optional<Integer> currentLeaderIndex() throws Exception {
        for (int i = 0; i < endpoints.size(); i++) {
            try {
                StatusResponse s = client.getMaintenanceClient()
                        .statusMember(endpoints.get(i))
                        .get(5, TimeUnit.SECONDS);
                if (s.getHeader().getMemberId() == s.getLeader()) {
                    return Optional.of(i);
                }
            } catch (Exception e) {
                // A dead/partitioned member cannot be the leader; keep
                // scanning the rest — but say so, silence hides faults.
                log.debug("statusMember({}) failed: {}", endpoints.get(i), e.toString());
            }
        }
        return Optional.empty(); // no member claims leadership (election in progress)
    }

    @Override public void close() {
        log.debug("phase: driver close");
        if (client != null) client.close();
    }
}
