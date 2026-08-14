package gr.thesis.bench.topology;

import gr.thesis.bench.topology.ClusterProvider.NodeHandle;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Do the faults actually DO anything? Goldens pin the command TEXT and the
 * unit tests pin the SEQUENCE, but neither can tell whether a netem qdisc
 * ever shaped a packet — and F28 is the standing proof that this gap is real,
 * not theoretical: a green golden hid a backgrounding bug that would have
 * stalled every slow_node injection for 30 s against a real sshd.
 *
 * <p>This is the last cheap thing standing between the goldens and the P3.4
 * canary, which is the highest-uncertainty item in the queue precisely
 * because nobody has watched these commands take effect. Two real containers
 * on ubuntu-24.04 (the image {@code infra/main.tf} pins) with {@code
 * NET_ADMIN}, driven through the REAL {@link SshFaultInjector} so the
 * commands under test are the ones production builds — not a transcription
 * of them.
 *
 * <p>Scope, stated so the test is not mistaken for more than it is: the SSH
 * TRANSPORT is covered by {@link SshjExecutorTest} against a real sshd, and
 * is deliberately not re-tested here. What is new here is EFFECT — that the
 * rule appears, that traffic actually stops, and that heal puts it back.
 */
class HostFaultEffectTest {

    private static Network net;
    private static GenericContainer<?> n1;
    private static GenericContainer<?> n2;

    /** ubuntu-24.04 + exactly the tools cloud-init installs on a campaign
     *  node. Built once and cached by Testcontainers. */
    private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile()
            .withDockerfileFromBuilder(b -> b
                    .from("ubuntu:24.04")
                    .run("apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install "
                            + "-y -qq iproute2 iptables stress-ng procps iputils-ping "
                            + "&& rm -rf /var/lib/apt/lists/*")
                    .cmd("sleep", "infinity")
                    .build());

    @BeforeAll
    static void up() {
        net = Network.newNetwork();
        n1 = node("n1");
        n2 = node("n2");
        n1.start();
        n2.start();
    }

    @AfterAll
    static void down() {
        if (n1 != null) n1.stop();
        if (n2 != null) n2.stop();
        if (net != null) net.close();
    }

    private static GenericContainer<?> node(String alias) {
        return new GenericContainer<>(IMAGE)
                .withNetwork(net).withNetworkAliases(alias)
                .withCreateContainerCmdModifier(c -> c.getHostConfig()
                        .withCapAdd(com.github.dockerjava.api.model.Capability.NET_ADMIN));
    }

    private static String ip(GenericContainer<?> c) {
        return c.getContainerInfo().getNetworkSettings().getNetworks()
                .values().iterator().next().getIpAddress();
    }

    /** An SshExecutor that reaches the containers by {@code docker exec}. The
     *  point is to run the injector's OWN command strings against a real
     *  kernel; the ssh hop itself is SshjExecutorTest's subject. */
    private static SshExecutor dockerExec() {
        return new SshExecutor() {
            @Override public ExecResult exec(String host, int port, String command)
                    throws Exception {
                GenericContainer<?> target = host.equals(ip(n1)) ? n1 : n2;
                // sudo is absent in the image and unnecessary as root; the
                // campaign's cloud-init user has it. Strip it so the COMMAND
                // under test is otherwise byte-identical to production's.
                var r = target.execInContainer("sh", "-c", command.replace("sudo ", ""));
                return new ExecResult(r.getExitCode(), r.getStdout(), r.getStderr());
            }
            @Override public void close() { }
        };
    }

    private static List<NodeHandle> cluster() {
        return List.of(new NodeHandle(0, "thesis-n1", ip(n1), ip(n1)),
                new NodeHandle(1, "thesis-n2", ip(n2), ip(n2)));
    }

    private static String on(GenericContainer<?> c, String cmd) throws Exception {
        var r = c.execInContainer("sh", "-c", cmd);
        return r.getStdout() + r.getStderr();
    }

    @Test
    void packetLossActuallyInstallsAndRemovesANetemQdisc() throws Exception {
        var injector = new SshFaultInjector(dockerExec(), cluster(), 30);
        injector.packetLoss(cluster().get(0), 30);

        // The iface was RESOLVED from the peer route, never assumed eth0 —
        // this asserts the resolution landed on an interface that exists.
        assertTrue(on(n1, "tc qdisc show").contains("netem"),
                "netem must be installed on the resolved private iface");
        assertTrue(on(n1, "tc qdisc show").contains("loss 30%"), on(n1, "tc qdisc show"));

        injector.heal();
        assertFalse(on(n1, "tc qdisc show").contains("netem"),
                "heal must remove the qdisc — a survivor shapes every later run (F69)");
    }

    @Test
    void partitionActuallyStopsTrafficAndHealRestoresIt() throws Exception {
        // Baseline: the peer is reachable.
        assertEquals(0, n1.execInContainer("ping", "-c1", "-W2", ip(n2)).getExitCode(),
                "peers must be reachable before the fault");

        var injector = new SshFaultInjector(dockerExec(), cluster(), 120);
        injector.partition(cluster().get(0), List.of(cluster().get(1)));

        assertNotEquals(0, n1.execInContainer("ping", "-c1", "-W2", ip(n2)).getExitCode(),
                "the DROP rules must actually stop packets — this is the assertion no"
                        + " golden can make, and the whole reason this test exists");

        injector.heal();
        assertEquals(0, n1.execInContainer("ping", "-c1", "-W2", ip(n2)).getExitCode(),
                "heal must restore connectivity");
        assertFalse(on(n1, "iptables -S INPUT").contains("DROP"),
                "no harness DROP rule may survive heal");
    }

    @Test
    void slowNodeStartsRealLoadAndHealKillsIt() throws Exception {
        var injector = new SshFaultInjector(dockerExec(), cluster(), 120);
        injector.slowNode(cluster().get(0));

        // F28: the backgrounding must return immediately AND leave the
        // process running. Both halves matter — the golden can see neither.
        assertTrue(on(n1, "pgrep -af stress-ng").contains("--cpu 2"),
                "stress-ng must actually be running after injection");

        injector.heal();
        Thread.sleep(500);
        assertFalse(on(n1, "pgrep -af 'stress-n[g] --cpu 2'").contains("--cpu 2"),
                "heal's pkill must reach the real process");
    }

    @Test
    void theF69SweepClearsStateAFaultInjectorWouldHaveLeaked() throws Exception {
        // Simulate a campaign JVM killed between inject and heal: apply the
        // faults and DO NOT heal. This is exactly the F69 scenario, and the
        // sweep is what the next run relies on.
        var injector = new SshFaultInjector(dockerExec(), cluster(), 120);
        injector.packetLoss(cluster().get(0), 30);
        injector.partition(cluster().get(0), List.of(cluster().get(1)));
        assertTrue(on(n1, "tc qdisc show").contains("netem"));

        var ssh = dockerExec();
        String iface = "$(ip -o route get " + ip(n2)
                + " | awk '{for(i=1;i<=NF;i++) if($i==\"dev\") print $(i+1)}')";
        // Exactly the provider's sweep commands, and the MEASURED contract:
        // exit 0 proves state was there (infra/probes/).
        assertEquals(0, ssh.exec(ip(n1), 22, "tc qdisc del dev " + iface + " root").exitCode(),
                "a zero exit is the leak alarm: state really was present");
        assertEquals(0, ssh.exec(ip(n1), 22,
                "iptables -D INPUT -s " + ip(n2) + " -j DROP").exitCode());
        assertEquals(0, ssh.exec(ip(n1), 22,
                "iptables -D OUTPUT -d " + ip(n2) + " -j DROP").exitCode());

        assertFalse(on(n1, "tc qdisc show").contains("netem"), "the sweep must leave it clean");
        assertEquals(0, n1.execInContainer("ping", "-c1", "-W2", ip(n2)).getExitCode());

        // And on the now-clean box every undo is non-zero — which is why the
        // sweep is SILENT in the normal case instead of crying wolf (F31).
        assertNotEquals(0, ssh.exec(ip(n1), 22, "tc qdisc del dev " + iface + " root").exitCode());
        assertNotEquals(0, ssh.exec(ip(n1), 22,
                "iptables -D INPUT -s " + ip(n2) + " -j DROP").exitCode());
    }
}
