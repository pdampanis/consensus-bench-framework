package gr.thesis.bench.driver;

import gr.thesis.bench.core.SystemUnderTest;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * The single most important abstraction: one async write contract for every
 * system. This fixes the deepest methodological flaw of the current probes,
 * where each system was measured by a different client stack with different
 * concurrency semantics:
 *
 *   - Kafka:      kafka-producer-perf-test, thousands of pipelined in-flight
 *                 records (async batching)
 *   - CometBFT:   6 BLOCKING Python threads on broadcast_tx_commit; with a
 *                 ~1s block interval the measurable ceiling is ~6 tx/s while
 *                 the system can do thousands (PaxiBFT measured Tendermint at
 *                 ~1750 tx/s using 90 clients)
 *   - Paxi/etcd:  6 blocking threads, and urllib opens a NEW TCP connection
 *                 per request, so every latency sample includes a handshake
 *
 * Comparing those numbers is apples-to-oranges: the load model, not the
 * consensus protocol, dominates the differences. With this SPI, the
 * WorkloadEngine drives EVERY system with the same open-loop schedule and
 * the same bounded in-flight window; only the transport differs per driver.
 *
 * write() must complete its stage only when the operation is COMMITTED by
 * the consensus mechanism (acks=all ack, Raft majority ack, block inclusion,
 * quorum HTTP 200) - never on mere submission.
 */
public interface ConsensusDriver extends AutoCloseable {

    /**
     * The workload keyspace: keyId ∈ [0, KEY_SPACE), REUSED across the whole
     * run (Paxi Table 3: K = 1000 reused keys). The key is a typed integer —
     * not a byte blob — because every system encodes it differently (etcd: a
     * path-style key; Paxi: an integer in the URL path; Kafka: record key
     * bytes; CometBFT: a k=v tx). Each driver owns its encoding, so the SAME
     * keyId stream drives every system and contention is comparable.
     */
    int KEY_SPACE = 1000;

    SystemUnderTest system();

    /** Establish pooled, persistent connections (and precompute per-keyId
     *  encodings — the per-op path must not build strings). Called once
     *  before warmup. */
    void connect() throws Exception;

    /** Async committed write of keyId ∈ [0, KEY_SPACE). The returned stage
     *  completes on consensus commitment, exceptionally on failure/timeout.
     *  Implementations must reuse connections (Kafka producer, jetcd
     *  channel, pooled HttpClient) — and must BOUND completion time (5 s
     *  per op is the established bound): the engine's drain barrier waits
     *  for every issued write, so one unbounded op on a quorum-lost cluster
     *  hangs the whole fault run instead of failing closed. */
    CompletionStage<Void> write(int keyId, byte[] value);

    /**
     * Identify the current leader/proposer, if the protocol has one.
     * A first-class SPI method because v6's shell layer regressed to
     * "kill node1 and hope": etcd's leader is whichever node won the
     * first election, so half of those leader_kill cells would have
     * measured follower loss. EPaxos returns empty (leaderless).
     */
    Optional<Integer> currentLeaderIndex() throws Exception;

    @Override void close();
}
