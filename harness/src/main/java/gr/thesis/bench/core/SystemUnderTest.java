package gr.thesis.bench.core;

import java.util.List;

/**
 * The systems under test. A typed enum makes the v6 shell bug class
 * (deriving container names via string surgery like ${sys%%_*}1, which
 * produced nonexistent names "kraft1"/"tendermint1") impossible: every
 * system carries its real container naming scheme and cluster shape.
 */
public enum SystemUnderTest {
    KRAFT      ("k",    3, false),
    KAFKA_ZK   ("k",    3, false),
    ETCD       ("etcd", 3, false),
    TENDERMINT ("tm",   4, true),
    PAXOS      ("paxi", 3, false),
    EPAXOS     ("paxi", 3, false),
    HOTSTUFF   ("hs",   4, true);

    private final String containerPrefix;
    private final int defaultClusterSize;
    private final boolean byzantine;

    SystemUnderTest(String containerPrefix, int defaultClusterSize, boolean byzantine) {
        this.containerPrefix = containerPrefix;
        this.defaultClusterSize = defaultClusterSize;
        this.byzantine = byzantine;
    }

    public String containerName(int nodeIndex) {
        return containerPrefix + nodeIndex;
    }

    public int defaultClusterSize() { return defaultClusterSize; }
}
