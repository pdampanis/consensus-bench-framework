package gr.thesis.bench.driver;

import gr.thesis.bench.core.SystemUnderTest;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * PRODUCTION Kafka driver (P2.2) for KRaft and Kafka+ZK — the same client
 * data path serves both; which coordination machinery runs underneath is
 * the cluster provider's business (exactly the F6 comparison's point).
 *
 * Commit semantics: acks=all, so the send callback fires only when every
 * in-sync replica acknowledged the record (with RF=3/min.insync=2 on the
 * campaign topology, that is a majority-committed write; on the single-node
 * dev substrate it is the broker's own commit). The callback IS the commit
 * timestamp — this replaces the perf-test black box with the same
 * open-loop schedule every other system gets (the G1 flaw-B fix).
 *
 * Two hot-path guarantees the engine depends on:
 *  - send() never blocks the issuing thread in steady state: metadata is
 *    warmed at connect() (partitionsFor) and stays cached across broker
 *    death, so the fail path is asynchronous (records expire via
 *    delivery.timeout); buffer.memory (32 MB default) dwarfs any bounded
 *    in-flight window, so accumulator blocking cannot trigger.
 *  - completion is BOUNDED (F18 contract): delivery.timeout.ms = 5000 —
 *    a write on a dead/quorum-less cluster fails within ~5 s, matching
 *    the etcd and Paxi drivers, so the drain barrier always resolves.
 *
 * Leader detection: the leader of partition 0 of the bench topic, mapped
 * to a node index by matching the broker's advertised host:port against
 * the endpoint list (endpoint order = node order). NOTE for fault design
 * (P3.3): with 6 partitions spread over N brokers this is ONE partition's
 * leader — the Kafka leader_kill preregistration must say which leader is
 * meant; on a single broker they coincide.
 */
public final class KafkaDriver implements ConsensusDriver {

    private static final Logger log = LoggerFactory.getLogger(KafkaDriver.class);

    static final String TOPIC = "bench";
    static final int PARTITIONS = 6; // the retired probe's shape, kept for comparability

    private final SystemUnderTest system;
    private final List<String> bootstrap; // node order — index IS the node index
    private KafkaProducer<byte[], byte[]> producer;
    private byte[][] keys; // per-keyId record-key bytes, built at connect()

    public KafkaDriver(SystemUnderTest system, List<String> bootstrapServers) {
        if (system != SystemUnderTest.KRAFT && system != SystemUnderTest.KAFKA_ZK) {
            throw new IllegalArgumentException("KafkaDriver serves KRAFT/KAFKA_ZK only");
        }
        if (bootstrapServers.isEmpty()) throw new IllegalArgumentException("no endpoints");
        this.system = system;
        this.bootstrap = List.copyOf(bootstrapServers);
    }

    /** Kafka's native encoding of a workload keyId: UTF-8 integer string as
     *  record-key bytes — same keyId, same partition (murmur2), so
     *  contention is deterministic and comparable across systems. */
    static byte[] encodeKey(int keyId) {
        return Integer.toString(keyId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override public SystemUnderTest system() { return system; }

    @Override public void connect() throws Exception {
        log.debug("phase: connect — Kafka producer for {}", bootstrap);
        keys = new byte[KEY_SPACE][];
        for (int i = 0; i < KEY_SPACE; i++) keys[i] = encodeKey(i);

        String servers = String.join(",", bootstrap);
        // Topic first (idempotent), so the metadata warm-up below cannot race
        // topic auto-creation. RF follows the cluster size; the campaign's
        // RF=3 topology adds min.insync.replicas=2 (majority commit).
        short rf = (short) Math.min(3, bootstrap.size());
        NewTopic topic = new NewTopic(TOPIC, PARTITIONS, rf);
        if (rf >= 3) topic.configs(Map.of("min.insync.replicas", "2"));
        try (Admin admin = Admin.create(Map.<String, Object>of(
                org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, servers))) {
            try {
                admin.createTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof TopicExistsException)) throw e;
            }
        }

        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.CLIENT_ID_CONFIG, "consensus-bench");
        // F18 bounded-completion contract: callback within ~5 s, always.
        // delivery.timeout >= linger(0) + request.timeout must hold.
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000);
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 4_000);
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        producer = new KafkaProducer<>(p);
        // Fail closed HERE and warm the metadata cache: the per-op path must
        // never block on a metadata fetch (that would stall the open-loop
        // schedule itself, not just one op).
        producer.partitionsFor(TOPIC);
    }

    @Override public CompletionStage<Void> write(int keyId, byte[] value) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        producer.send(new ProducerRecord<>(TOPIC, keys[keyId], value), (md, ex) -> {
            if (ex == null) f.complete(null); else f.completeExceptionally(ex);
        });
        return f;
    }

    @Override public Optional<Integer> currentLeaderIndex() throws Exception {
        List<PartitionInfo> parts = producer.partitionsFor(TOPIC);
        Node leader = parts.get(0).leader();
        if (leader == null) return Optional.empty(); // election in progress
        String addr = leader.host() + ":" + leader.port();
        for (int i = 0; i < bootstrap.size(); i++) {
            if (bootstrap.get(i).equals(addr)) return Optional.of(i);
        }
        // A leader we cannot map is a topology surprise, not an absent
        // leader — failing loud beats killing the wrong node (the v6 bug).
        throw new IllegalStateException(
                "leader " + addr + " not in endpoint list " + bootstrap);
    }

    @Override public void close() {
        log.debug("phase: driver close");
        if (producer != null) producer.close(Duration.ofSeconds(5));
    }
}
