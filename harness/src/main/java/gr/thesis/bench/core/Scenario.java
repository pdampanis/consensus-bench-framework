package gr.thesis.bench.core;

/** Fault scenarios. Restart semantics are part of the type, fixing v6's C4
 *  (fault runs reusing a corrupted cluster) by construction: the campaign
 *  runner MUST recycle the cluster when mutatesCluster() is true. */
public enum Scenario {
    BASELINE          (false),
    LEADER_KILL       (true),
    DOUBLE_KILL       (true),
    PACKET_LOSS       (true),
    NETWORK_PARTITION (true),
    SLOW_NODE         (true);

    private final boolean mutatesCluster;
    Scenario(boolean mutatesCluster) { this.mutatesCluster = mutatesCluster; }
    public boolean mutatesCluster()  { return mutatesCluster; }
}
