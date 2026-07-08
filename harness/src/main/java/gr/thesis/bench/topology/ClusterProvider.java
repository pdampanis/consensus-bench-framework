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
     *  throttle it without string surgery. */
    record NodeHandle(int index, String containerName, String host, String privateIp) {}

    /** Faults operate on handles, not derived names. */
    interface FaultInjector {
        void kill(NodeHandle node) throws Exception;
        void packetLoss(NodeHandle node, int percent) throws Exception;
        void partition(NodeHandle node, List<NodeHandle> from) throws Exception;
        void slowNode(NodeHandle node) throws Exception;
        void heal() throws Exception;
        default void apply(Scenario s, List<NodeHandle> cluster, int leaderIdx) throws Exception {
            switch (s) {
                case BASELINE -> {}
                case LEADER_KILL -> kill(cluster.get(leaderIdx));
                case DOUBLE_KILL -> { kill(cluster.get(0)); kill(cluster.get(1)); }
                case PACKET_LOSS -> packetLoss(cluster.get(leaderIdx), 5);
                case NETWORK_PARTITION -> partition(cluster.get(0), cluster.subList(1, cluster.size()));
                case SLOW_NODE -> slowNode(cluster.get(leaderIdx));
            }
        }
    }
}
