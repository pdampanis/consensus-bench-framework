package gr.thesis.bench.topology;

import gr.thesis.bench.core.Scenario;
import gr.thesis.bench.core.SystemUnderTest;

import java.util.List;

/**
 * Cluster lifecycle SPI. Two planned implementations:
 *
 *  LocalDockerProvider  - Testcontainers/docker-java on one host: typed
 *                         container definitions, wait strategies, and
 *                         guaranteed teardown replace compose + bash glue.
 *  RemoteSshProvider    - one node per VM over sshj. Honestly flagged as
 *                         the hard 30%: Java types cannot catch semantic
 *                         orchestration errors (wrong interface for netem,
 *                         missing key distribution) - only a dry-run
 *                         harness and a smoke test can.
 */
public interface ClusterProvider extends AutoCloseable {

    /** Start a fresh cluster. MUST be a clean slate - the campaign runner
     *  calls this before every run of a Scenario.mutatesCluster() cell. */
    List<NodeHandle> start(SystemUnderTest system, int clusterSize) throws Exception;

    /** Client-facing endpoints (bootstrap servers / RPC URLs), in node order. */
    List<String> clientEndpoints();

    void stop() throws Exception;

    @Override default void close() {
        try {
            stop();
        } catch (Exception e) {
            // Teardown keeps going, but NEVER silently (v6's `|| true`
            // disease): a failed stop() means leaked containers or billed
            // VMs that someone must see and act on.
            org.slf4j.LoggerFactory.getLogger(ClusterProvider.class)
                    .warn("cluster stop() failed during close(): {}", e.toString());
        }
    }

    /** A single consensus node: enough identity to kill, partition, or
     *  throttle it without string surgery. {@code privateIp} is the address
     *  peers, clients, and faults act on — a REAL private IP on the campaign
     *  (RemoteSshProvider, F20) and the Docker network alias on the local
     *  substrate (where the alias IS that address). {@code host} is where
     *  management reaches the node: the same private IP remotely (the
     *  harness runs on the loadgen), 127.0.0.1 locally. */
    record NodeHandle(int index, String containerName, String host, String privateIp) {}

    /** Faults operate on handles, not derived names. */
    interface FaultInjector {
        void kill(NodeHandle node) throws Exception;
        void packetLoss(NodeHandle node, int percent) throws Exception;
        void partition(NodeHandle node, List<NodeHandle> from) throws Exception;
        void slowNode(NodeHandle node) throws Exception;
        void heal() throws Exception;
        /**
         * F13-preregistered targeting (F19 pins it by test): every
         * leader-sensitive fault hits the DETECTED leader — the old default
         * isolated node 0, which for etcd is the leader only when node 0
         * happened to win the first election (the v6 "kill node1 and hope"
         * class). DOUBLE_KILL stays deterministic nodes 0+1: any two kills
         * lose a 3-node CFT quorum, and on BFT n=4 it is the intentional
         * liveness-loss demonstration (2 > f=1). Heal-in-finally is the
         * injector implementation's contract (P3.3), not apply()'s.
         */
        default void apply(Scenario s, List<NodeHandle> cluster, int leaderIdx,
                           int packetLossPercent) throws Exception {
            switch (s) {
                case BASELINE -> {}
                case LEADER_KILL -> kill(cluster.get(leaderIdx));
                case DOUBLE_KILL -> { kill(cluster.get(0)); kill(cluster.get(1)); }
                case PACKET_LOSS -> packetLoss(cluster.get(leaderIdx), packetLossPercent);
                case NETWORK_PARTITION -> {
                    List<NodeHandle> others = new java.util.ArrayList<>(cluster);
                    others.remove(leaderIdx);
                    partition(cluster.get(leaderIdx), others);
                }
                case SLOW_NODE -> slowNode(cluster.get(leaderIdx));
            }
        }
    }
}
