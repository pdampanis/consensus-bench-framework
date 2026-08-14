package gr.thesis.bench.topology;

import gr.thesis.bench.core.Scenario;
import gr.thesis.bench.topology.ClusterProvider.FaultInjector;
import gr.thesis.bench.topology.ClusterProvider.NodeHandle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F19: pins the F13 preregistration into the ONE place every injector
 * inherits its targeting from — the {@code FaultInjector.apply} default.
 * Preregistered semantics (the findings ledger (docs/archive/) P3.3 notes, locked 2026-07-08):
 *  - NETWORK_PARTITION isolates the LEADER from everyone else — the
 *    default used to isolate node 0, which for etcd is "whoever won the
 *    first election" roughly a third of the time and a follower otherwise:
 *    half-follower data labeled "leader partition" (the v6 bug class).
 *  - PACKET_LOSS percent is a PARAMETER (the campaign may sweep it), and
 *    it targets the leader.
 *  - DOUBLE_KILL is deterministic nodes 0+1 — on CFT n=3 any two kills
 *    lose quorum; on BFT n=4 it is the intentional liveness-loss
 *    demonstration (2 > f=1). Not leader-targeted, and documented as such.
 * The P3.3 golden tests will pin the exact remote command strings; this
 * test pins WHICH NODE each scenario targets, which no golden can rescue
 * if the default is wrong.
 */
class FaultInjectorApplyTest {

    /** Records every fault call: "kind:targetIdx[:extra]". */
    private static final class RecordingInjector implements FaultInjector {
        final List<String> calls = new ArrayList<>();
        @Override public void kill(NodeHandle n) { calls.add("kill:" + n.index()); }
        @Override public void packetLoss(NodeHandle n, int percent) {
            calls.add("loss:" + n.index() + ":" + percent);
        }
        @Override public void partition(NodeHandle n, List<NodeHandle> from) {
            calls.add("partition:" + n.index() + ":from="
                    + from.stream().map(h -> Integer.toString(h.index()))
                          .reduce((a, b) -> a + "," + b).orElse(""));
        }
        @Override public void slowNode(NodeHandle n) { calls.add("slow:" + n.index()); }
        @Override public void heal() { calls.add("heal"); }
    }

    private static List<NodeHandle> cluster(int n) {
        List<NodeHandle> c = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            c.add(new NodeHandle(i, "thesis-etcd" + (i + 1), "127.0.0.1", "etcd" + (i + 1)));
        }
        return c;
    }

    @Test
    void partitionIsolatesTheLeaderNotNodeZero() throws Exception {
        var inj = new RecordingInjector();
        inj.apply(Scenario.NETWORK_PARTITION, cluster(3), 2, 5);
        assertEquals(List.of("partition:2:from=0,1"), inj.calls,
                "the LEADER (index 2) must be isolated from everyone else");
    }

    @Test
    void packetLossTargetsTheLeaderAtTheConfiguredPercent() throws Exception {
        var inj = new RecordingInjector();
        inj.apply(Scenario.PACKET_LOSS, cluster(3), 1, 7);
        assertEquals(List.of("loss:1:7"), inj.calls,
                "packet loss hits the leader at the CONFIGURED percent, not a hardcoded 5");
    }

    @Test
    void leaderKillKillsTheDetectedLeader() throws Exception {
        var inj = new RecordingInjector();
        inj.apply(Scenario.LEADER_KILL, cluster(3), 2, 5);
        assertEquals(List.of("kill:2"), inj.calls);
    }

    @Test
    void doubleKillIsDeterministicallyNodesZeroAndOne() throws Exception {
        var inj = new RecordingInjector();
        inj.apply(Scenario.DOUBLE_KILL, cluster(4), 3, 5);
        assertEquals(List.of("kill:0", "kill:1"), inj.calls,
                "DOUBLE_KILL is the preregistered liveness-loss demo: nodes 0+1, not leader-chasing");
    }

    @Test
    void slowNodeStressesTheLeader() throws Exception {
        var inj = new RecordingInjector();
        inj.apply(Scenario.SLOW_NODE, cluster(3), 0, 5);
        assertEquals(List.of("slow:0"), inj.calls);
    }

    @Test
    void baselineInjectsNothing() throws Exception {
        var inj = new RecordingInjector();
        inj.apply(Scenario.BASELINE, cluster(3), 1, 5);
        assertTrue(inj.calls.isEmpty(), "baseline must not touch the cluster");
    }
}
