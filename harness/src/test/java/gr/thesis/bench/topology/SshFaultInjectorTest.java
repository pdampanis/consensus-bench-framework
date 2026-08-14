package gr.thesis.bench.topology;

import gr.thesis.bench.core.Scenario;
import gr.thesis.bench.topology.ClusterProvider.NodeHandle;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3.3c (M4.3) — the remote FaultInjector's golden dry run (G2). Every
 * scenario's exact remote command sequence is compared verbatim against a
 * reviewed golden block ({@code etcd-size3-faults.txt}). The network
 * invariants a human must confirm are IN the golden header; these tests
 * pin that the code emits exactly what the golden says, plus the two
 * properties a golden can't show on its own: heal-in-finally, and the
 * runtime interface resolved from `ip -o route get` output (not assumed).
 */
class SshFaultInjectorTest {

    /**
     * Records commands in COMPLETION order (not start order — the F51 race is
     * about a command being issued while another is still in flight), and
     * holds whichever command starts with {@code blockOn} until released, so
     * the interleaving under test is deterministic rather than raced.
     */
    private static final class BlockingRecorder implements SshExecutor {
        private final List<String> done =
                java.util.Collections.synchronizedList(new ArrayList<>());
        private final String blockOn;
        private final java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);

        BlockingRecorder(String blockOn) { this.blockOn = blockOn; }

        @Override public ExecResult exec(String host, int port, String command) throws Exception {
            if (command.startsWith(blockOn)) {
                entered.countDown();
                release.await();
            }
            done.add(command);
            return command.startsWith("ip -o route get")
                    ? new ExecResult(0, "10.0.0.12 dev eth1 src 10.0.0.11 uid 0", "")
                    : new ExecResult(0, "", "");
        }

        @Override public void close() { }

        int indexOf(String prefix) {
            synchronized (done) {
                for (int i = 0; i < done.size(); i++) {
                    if (done.get(i).startsWith(prefix)) return i;
                }
            }
            return -1;
        }
    }

    @Test
    void healMustNotUndoAFaultThatHasNotBeenAppliedYet() throws Exception {
        // F51: undo entries are pushed by the FAULT thread and popped by the
        // MAIN thread in heal(), with nothing coordinating them, and
        // RemoteRunner heals unconditionally after a join that CAN time out
        // (partition issues five SSH commands, each bounded at 30 s by
        // SshjExecutor, so apply() can legitimately outlive the 30 s join).
        // Interleaved, heal() deletes the netem qdisc BEFORE tc has added it —
        // the delete fails as "nothing to undo" (a WARN, by design) and the
        // rule then SURVIVES on the node. Nothing catches it afterwards: the
        // F29 pre-clean sweeps thesis-* CONTAINERS, never host tc/iptables
        // state, so the leaked rule silently shapes every later run on that VM.
        var ssh = new BlockingRecorder("sudo tc qdisc add");
        var injector = new SshFaultInjector(ssh, CLUSTER, 270);

        Thread injecting = new Thread(() -> {
            try {
                injector.packetLoss(CLUSTER.get(LEADER), 5);
            } catch (Exception e) {
                throw new AssertionError("injection failed", e);
            }
        }, "test-fault-injector");
        injecting.start();
        assertTrue(ssh.entered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "the tc add command should be in flight");

        Thread healing = new Thread(injector::heal, "test-healer");
        healing.start();
        healing.join(1_500);          // unsynchronised, heal races ahead and finishes here
        ssh.release.countDown();      // let the in-flight add complete
        injecting.join(5_000);
        healing.join(5_000);

        int add = ssh.indexOf("sudo tc qdisc add");
        int del = ssh.indexOf("sudo tc qdisc del");
        assertTrue(add >= 0 && del >= 0, "both tc commands must run: " + ssh.done);
        assertTrue(add < del,
                "heal undid the qdisc before it was applied — the netem rule survives on the"
                        + " node and poisons every later run (order was " + ssh.done + ")");
    }

    private static final List<NodeHandle> CLUSTER = List.of(
            new NodeHandle(0, "thesis-etcd1", "10.0.0.11", "10.0.0.11"),
            new NodeHandle(1, "thesis-etcd2", "10.0.0.12", "10.0.0.12"),
            new NodeHandle(2, "thesis-etcd3", "10.0.0.13", "10.0.0.13"));
    private static final int LEADER = 1;

    /** The golden's [[SCENARIO]] blocks, comment/heal-label lines stripped,
     *  PRIVATE_IFACE left as the literal token (resolved at runtime). */
    private static Map<String, List<String>> goldenBlocks() throws IOException {
        Map<String, List<String>> blocks = new LinkedHashMap<>();
        String current = null;
        for (String raw : Files.readAllLines(
                Path.of("src/test/resources/goldens/etcd-size3-faults.txt"))) {
            String line = raw.strip();
            if (line.startsWith("[[") && line.endsWith("]]")) {
                current = line.substring(2, line.length() - 2);
                blocks.put(current, new ArrayList<>());
            } else if (current != null && !line.isEmpty() && !line.startsWith("#")) {
                blocks.get(current).add(line);
            }
        }
        return blocks;
    }

    /** A recorder that answers `ip -o route get <ip>` so iface resolution
     *  is deterministic; the golden carries the literal PRIVATE_IFACE, so
     *  we substitute the same token to compare. */
    private static RecordingSshExecutor ifaceAwareRecorder() {
        var ssh = new RecordingSshExecutor();
        for (NodeHandle n : CLUSTER) {
            ssh.respondTo("ip -o route get " + n.privateIp(), new SshExecutor.ExecResult(
                    0, n.privateIp() + " dev enp7s0 src 10.0.0.99 uid 0", ""));
        }
        return ssh;
    }

    private static List<String> recordedFor(Scenario scenario, RecordingSshExecutor ssh)
            throws Exception {
        // The golden shows the STANDARD block: duration 480, fault at 240,
        // +30 s slack = 270 s (D15.3). A failover block derives 150 s.
        var injector = new SshFaultInjector(ssh, CLUSTER, 270);
        try {
            injector.apply(scenario, CLUSTER, LEADER, 30);
        } finally {
            injector.heal();
        }
        // Normalize the resolved iface back to the golden's token so the
        // comparison is about STRUCTURE, and separately assert resolution.
        return ssh.commands().stream()
                .map(c -> c.replace("enp7s0", "PRIVATE_IFACE"))
                .toList();
    }

    @Test
    void leaderKillMatchesGolden() throws Exception {
        assertEquals(goldenBlocks().get("LEADER_KILL"),
                recordedFor(Scenario.LEADER_KILL, new RecordingSshExecutor()));
    }

    @Test
    void packetLossResolvesIfaceAndMatchesGoldenWithHeal() throws Exception {
        var ssh = ifaceAwareRecorder();
        List<String> recorded = recordedFor(Scenario.PACKET_LOSS, ssh);
        assertEquals(goldenBlocks().get("PACKET_LOSS"), recorded);
        // The iface was RESOLVED, not assumed: the real command carried the
        // parsed device, and no command mentions a hardcoded eth0.
        assertTrue(ssh.commands().stream().anyMatch(c -> c.contains("dev enp7s0")),
                "netem must shape the RESOLVED private iface");
        assertTrue(ssh.commands().stream().noneMatch(c -> c.contains("eth0")),
                "no assumed eth0 anywhere");
    }

    @Test
    void partitionIsolatesLeaderFromOtherNodesPreservingLoadgenPath() throws Exception {
        List<String> recorded = recordedFor(Scenario.NETWORK_PARTITION, new RecordingSshExecutor());
        assertEquals(goldenBlocks().get("NETWORK_PARTITION"), recorded);
        // Only the two OTHER node IPs are dropped — never the /24 subnet,
        // so the loadgen (10.0.0.20) -> leader path survives.
        assertTrue(recorded.stream().noneMatch(c -> c.contains("10.0.0.0/24")),
                "must be pairwise node IPs, never a subnet block");
        assertTrue(recorded.stream().noneMatch(c -> c.contains("10.0.0.20")),
                "the loadgen path must be untouched");
    }

    @Test
    void slowNodeAndDoubleKillMatchGolden() throws Exception {
        assertEquals(goldenBlocks().get("SLOW_NODE"),
                recordedFor(Scenario.SLOW_NODE, new RecordingSshExecutor()));
        assertEquals(goldenBlocks().get("DOUBLE_KILL"),
                recordedFor(Scenario.DOUBLE_KILL, new RecordingSshExecutor()));
    }

    @Test
    void healIsAlwaysEmittedEvenWhenInjectionThrows() throws Exception {
        // A partition whose FIRST iptables rule fails must still emit the
        // heal for whatever was applied — a persisted rule corrupts every
        // subsequent run's cluster. heal() in the caller's finally is the
        // contract; the injector tracks applied rules and undoes them.
        var ssh = new RecordingSshExecutor();
        ssh.respondTo("sudo iptables -A INPUT -s 10.0.0.11 -j DROP",
                new SshExecutor.ExecResult(1, "", "iptables: Permission denied"));
        var injector = new SshFaultInjector(ssh, CLUSTER, 270);

        assertThrows(IllegalStateException.class,
                () -> injector.partition(CLUSTER.get(LEADER),
                        List.of(CLUSTER.get(0), CLUSTER.get(2))));
        injector.heal(); // must not throw, and must undo nothing-was-applied cleanly

        // No DROP rule survived un-deleted: every applied rule has its -D.
        long applied = ssh.commands().stream().filter(c -> c.contains("-A INPUT -s 10.0.0.11")).count();
        long healed = ssh.commands().stream().filter(c -> c.contains("-D INPUT -s 10.0.0.11")).count();
        assertEquals(applied, healed, "every applied rule must be healed");
    }
}
