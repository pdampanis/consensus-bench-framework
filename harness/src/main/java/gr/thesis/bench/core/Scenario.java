package gr.thesis.bench.core;

/**
 * Fault scenarios, each declaring whether it mutates the cluster — v6's C4
 * was fault runs reusing a corrupted cluster.
 *
 * <p>HONEST SCOPE (F72, 2026-08-14): this flag DOCUMENTS the requirement, it
 * does not enforce it — nothing in production reads it. The invariant holds
 * anyway, and more strongly than the flag asks: {@code RemoteRunner} builds a
 * fresh provider and calls {@code start()} for EVERY cell, mutating or not.
 * Kept because it states the rule at the point a reader meets the scenarios,
 * and because any future path that DOES reuse a cluster has to consult it.
 */
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
