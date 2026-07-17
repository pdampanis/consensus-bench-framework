package gr.thesis.bench.topology;

import gr.thesis.bench.topology.ClusterProvider.FaultInjector;
import gr.thesis.bench.topology.ClusterProvider.NodeHandle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The remote FaultInjector (P3.3c, M4.3) — every fault primitive as an SSH
 * command through {@link SshExecutor}, so the golden tests review the exact
 * sequence a billed VM would run (the check v6 never had). Targeting
 * (which node) is the {@link FaultInjector#apply} default, pinned by
 * FaultInjectorApplyTest (F13/F19); this class is the mechanics.
 *
 * Network invariants (the PENDING_TASKS P3.3 remote-deltas preregistration,
 * and why laptop-Docker faults would be WRONG on the servers):
 *  - netem shapes the interface carrying PRIVATE traffic, resolved at
 *    runtime from {@code ip -o route get <peer_private_ip>} — never an
 *    assumed eth0. On Hetzner the public/private NICs differ; shaping the
 *    wrong one is a silent no-op fault.
 *  - partition is PAIRWISE node-IP DROP rules (leader ↔ each other node),
 *    never a subnet block, so the loadgen→leader path survives — observing
 *    the isolated leader IS the measurement.
 *  - every applied fault registers its undo; {@link #heal} (called from the
 *    campaign runner's finally) replays them LIFO, best-effort but LOUD: a
 *    non-zero undo is WARNed, never silently swallowed (undo commands
 *    legitimately return non-zero when there is nothing to undo, and
 *    tc/iptables/pkill can't distinguish that from a real error at the CLI).
 */
public final class SshFaultInjector implements FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(SshFaultInjector.class);
    private static final int SSH_PORT = 22;

    private final SshExecutor ssh;
    private final List<NodeHandle> cluster;
    /** Undo commands, addressed as (ip, command), replayed LIFO by heal(). */
    private final Deque<String[]> undo = new ArrayDeque<>();

    public SshFaultInjector(SshExecutor ssh, List<NodeHandle> cluster) {
        this.ssh = ssh;
        this.cluster = List.copyOf(cluster);
    }

    @Override
    public void kill(NodeHandle node) throws Exception {
        // Process death, no undo (a fresh cluster is spun for the next run —
        // Scenario.mutatesCluster()). docker kill, not stop: an abrupt
        // SIGKILL is the fault we mean, not a graceful shutdown.
        ssh.execOrThrow(node.privateIp(), SSH_PORT, "docker kill " + node.containerName());
    }

    @Override
    public void packetLoss(NodeHandle node, int percent) throws Exception {
        String iface = privateIface(node);
        // Register the undo BEFORE applying: if the add half-succeeds we
        // must still be able to remove the qdisc.
        undo.push(new String[]{node.privateIp(), "sudo tc qdisc del dev " + iface + " root"});
        ssh.execOrThrow(node.privateIp(), SSH_PORT,
                "sudo tc qdisc add dev " + iface + " root netem loss " + percent + "%");
    }

    @Override
    public void partition(NodeHandle node, List<NodeHandle> from) throws Exception {
        // Pairwise IP DROP, both directions, per peer — NEVER a subnet rule,
        // so 10.0.0.0/24 (incl. the loadgen) is not blanket-blocked.
        for (NodeHandle peer : from) {
            String ip = peer.privateIp();
            for (String chain : new String[]{"INPUT -s", "OUTPUT -d"}) {
                String c = chain.split(" ")[0]; // INPUT / OUTPUT
                String flag = chain.split(" ")[1]; // -s / -d
                undo.push(new String[]{node.privateIp(),
                        "sudo iptables -D " + c + " " + flag + " " + ip + " -j DROP"});
                ssh.execOrThrow(node.privateIp(), SSH_PORT,
                        "sudo iptables -A " + c + " " + flag + " " + ip + " -j DROP");
            }
        }
    }

    @Override
    public void slowNode(NodeHandle node) throws Exception {
        // HOST stress-ng (cloud-init installs it): the consensus container
        // shares the host CPU, so this throttles the node without touching
        // the data path. Hard --timeout so an aborted run can't pin the VM;
        // heal pkills it explicitly (the pattern still matches — nohup
        // execs, so the process cmdline stays "stress-ng --cpu 2 ...").
        undo.push(new String[]{node.privateIp(), "pkill -f 'stress-ng --cpu 2'"});
        ssh.execOrThrow(node.privateIp(), SSH_PORT,
                backgrounded("stress-ng --cpu 2 --timeout 120s"));
    }

    /** How a long-running fault process is left behind on the node while
     *  the SSH command returns immediately. Package-private so the real-
     *  sshd acceptance test pins the SHAPE itself (F28): without the
     *  stream redirect, the backgrounded process inherits the exec
     *  channel's stdout/stderr and sshd holds the channel open until IT
     *  exits — measured red as a 30 s join timeout against a real sshd,
     *  i.e. the fault injection itself would stall and abort. nohup
     *  additionally shields the process from a SIGHUP at session close. */
    static String backgrounded(String command) {
        return "nohup " + command + " >/dev/null 2>&1 & echo started";
    }

    @Override
    public void heal() {
        while (!undo.isEmpty()) {
            String[] cmd = undo.pop();
            try {
                SshExecutor.ExecResult r = ssh.exec(cmd[0], SSH_PORT, cmd[1]);
                if (r.exitCode() != 0) {
                    // Loud, not silent: a non-zero undo may just mean
                    // "nothing to undo", but a human/validity check must see
                    // it — this is the opposite of v6's `|| true`.
                    log.warn("heal non-zero (exit {}) on {}: {} — stderr: {}",
                            r.exitCode(), cmd[0], cmd[1], r.stderr());
                }
            } catch (Exception e) {
                // A transport failure mid-heal must not abandon the rest of
                // the undo stack — log and keep unwinding.
                log.warn("heal transport failure on {}: {} — {}", cmd[0], cmd[1], e.toString());
            }
        }
    }

    /** Resolve the interface that routes toward a PEER's private IP —
     *  `ip -o route get <peer>` prints "... dev <iface> ...". Fails loud if
     *  the output has no dev field (we will not guess eth0). */
    private String privateIface(NodeHandle node) throws Exception {
        NodeHandle peer = firstOtherNode(node);
        String out = ssh.execOrThrow(node.privateIp(), SSH_PORT,
                "ip -o route get " + peer.privateIp());
        String[] toks = out.trim().split("\\s+");
        for (int i = 0; i < toks.length - 1; i++) {
            if (toks[i].equals("dev")) return toks[i + 1];
        }
        throw new IllegalStateException(
                "could not resolve private iface on " + node.privateIp()
                        + " from `ip -o route get`: <" + out + ">");
    }

    private NodeHandle firstOtherNode(NodeHandle node) {
        for (NodeHandle n : cluster) {
            if (n.index() != node.index()) return n;
        }
        throw new IllegalStateException("single-node cluster has no peer to route toward");
    }
}
