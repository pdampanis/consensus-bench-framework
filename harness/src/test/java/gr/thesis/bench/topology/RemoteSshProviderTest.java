package gr.thesis.bench.topology;

import gr.thesis.bench.core.SystemUnderTest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3.3b (M4.2, etcd first) — the golden-file dry run G2 requires: every
 * remote command the provider would run on billed VMs, compared verbatim
 * against a human-reviewable golden. No SSH, no Docker, no euro — the
 * RecordingSshExecutor IS the VM here, which is exactly the point (v6
 * shipped this layer unverified and it failed at first contact).
 */
class RemoteSshProviderTest {

    private static final List<String> IPS = List.of("10.0.0.11", "10.0.0.12", "10.0.0.13");
    private static final String HEALTHY = "{\"health\":\"true\",\"reason\":\"\"}";

    private static RecordingSshExecutor healthyRecorder() {
        var ssh = new RecordingSshExecutor();
        // One canned healthy answer serves every node (same command text).
        ssh.respondTo("curl -sf --max-time 2 http://127.0.0.1:2379/health",
                new SshExecutor.ExecResult(0, HEALTHY, ""));
        return ssh;
    }

    @Test
    void etcdSize3StartStopMatchesTheGoldenCommandSequence() throws Exception {
        var ssh = healthyRecorder();
        try (var provider = new RemoteSshProvider(ssh, IPS, Duration.ofSeconds(5))) {
            provider.start(SystemUnderTest.ETCD, 3);
            provider.stop();
        }

        Path golden = Path.of("src/test/resources/goldens/etcd-size3-start-stop.txt");
        List<String> expected = Files.readAllLines(golden).stream()
                .filter(l -> !l.startsWith("#") && !l.isBlank())
                .toList();
        assertEquals(expected, ssh.commands(),
                "the recorded remote sequence must match the reviewed golden verbatim");
    }

    @Test
    void handlesCarryRealPrivateIpsAndHostNetworkedEndpoints() throws Exception {
        var ssh = healthyRecorder();
        try (var provider = new RemoteSshProvider(ssh, IPS, Duration.ofSeconds(5))) {
            var nodes = provider.start(SystemUnderTest.ETCD, 3);

            // F20 RESOLVED here: privateIp is a routable address, never an
            // alias — the FaultInjector's netem/iptables act on it.
            assertEquals("10.0.0.12", nodes.get(1).privateIp());
            assertEquals("thesis-etcd2", nodes.get(1).containerName());
            assertEquals("10.0.0.12", nodes.get(1).host(), "SSH target = the private IP (loadgen-resident harness)");

            // Host networking: native ports on private IPs, nothing mapped.
            assertEquals(List.of(
                    "http://10.0.0.11:2379",
                    "http://10.0.0.12:2379",
                    "http://10.0.0.13:2379"),
                    provider.clientEndpoints());
        }
    }

    @Test
    void preCleanSweepsEveryProvisionedNodeForAnyThesisContainer() throws Exception {
        // F29: a size-1 cluster on 3 provisioned nodes must still pre-clean
        // ALL three — a D8 size-down run (7→5/3) leaves stale members on
        // nodes outside the new cluster, and a crashed earlier block may
        // have left a DIFFERENT system's containers behind. Per-name
        // removal of the new cluster's own containers covers neither.
        var ssh = healthyRecorder();
        try (var provider = new RemoteSshProvider(ssh, IPS, Duration.ofSeconds(5))) {
            provider.start(SystemUnderTest.ETCD, 1);
        }
        for (String ip : IPS) {
            assertTrue(ssh.commands().contains(
                    ip + ":22$ docker ps -aq --filter name=thesis- | xargs -r docker rm -f"),
                    "pre-clean must sweep thesis-* on " + ip);
        }
    }

    @Test
    void unsupportedSystemsAndOversizedClustersFailClosed() {
        var provider = new RemoteSshProvider(new RecordingSshExecutor(), IPS, Duration.ofSeconds(5));
        assertThrows(UnsupportedOperationException.class,
                () -> provider.start(SystemUnderTest.KAFKA_ZK, 3));
        assertThrows(IllegalArgumentException.class,
                () -> provider.start(SystemUnderTest.ETCD, 4),
                "cannot start more members than provisioned nodes");
    }

    // ---- P3.3d: Paxi (Paxos/EPaxos) remote substrate ----

    private static RecordingSshExecutor paxiReadyRecorder() {
        var ssh = new RecordingSshExecutor();
        // The probe write commits (paxi returns 200; curl -sf exit 0).
        ssh.respondTo("curl -sf --max-time 2 -X PUT --data-binary probeval http://10.0.0.11:8080/1",
                new SshExecutor.ExecResult(0, "", ""));
        return ssh;
    }

    @Test
    void paxosSize3StartStopMatchesTheGoldenCommandSequence() throws Exception {
        var ssh = paxiReadyRecorder();
        try (var provider = new RemoteSshProvider(ssh, IPS, Duration.ofSeconds(5))) {
            provider.start(SystemUnderTest.PAXOS, 3);
            provider.stop();
        }
        Path golden = Path.of("src/test/resources/goldens/paxos-size3-start-stop.txt");
        List<String> expected = Files.readAllLines(golden).stream()
                .filter(l -> !l.startsWith("#") && !l.isBlank())
                .toList();
        assertEquals(expected, ssh.commands(),
                "the recorded paxi sequence must match the reviewed golden verbatim");
    }

    @Test
    void epaxosSwapsOnlyTheAlgorithmFlagAndKeepsClientEndpoints() throws Exception {
        var ssh = paxiReadyRecorder();
        try (var provider = new RemoteSshProvider(ssh, IPS, Duration.ofSeconds(5))) {
            provider.start(SystemUnderTest.EPAXOS, 3);
            // Only the -algorithm token differs from paxos (same binary, D4).
            assertTrue(ssh.commands().stream().anyMatch(c -> c.endsWith("-id 1.1 -algorithm epaxos")),
                    "EPAXOS must run the same binary with -algorithm epaxos");
            assertTrue(ssh.commands().stream().noneMatch(c -> c.contains("-algorithm paxos")),
                    "no paxos algorithm token in an epaxos cluster");
            assertEquals(List.of(
                    "http://10.0.0.11:8080", "http://10.0.0.12:8080", "http://10.0.0.13:8080"),
                    provider.clientEndpoints());
        }
    }

    @Test
    void paxiLeaderKillIsAPlainDockerKillWithNoRecovery_theF26Wedge() throws Exception {
        // F26 (locked): stock paxi has no failure detector, so leader_kill is
        // just SIGKILL of the leader container — no netem, no heal, no
        // re-election command. The wedge is emergent; the injector must not
        // pretend otherwise.
        var ssh = paxiReadyRecorder();
        try (var provider = new RemoteSshProvider(ssh, IPS, Duration.ofSeconds(5))) {
            var nodes = provider.start(SystemUnderTest.PAXOS, 3);
            var rec = new RecordingSshExecutor();
            var inj = new SshFaultInjector(rec, nodes);
            inj.apply(gr.thesis.bench.core.Scenario.LEADER_KILL, nodes, 1, 0);
            inj.heal();
            assertEquals(List.of("10.0.0.12:22$ docker kill thesis-paxi2"), rec.commands(),
                    "paxi leader_kill = one docker kill, nothing to heal (the wedge)");
        }
    }

    // ---- P3.3d: KRaft remote substrate (wiring shape verified by
    //      KraftMultiBrokerFormationTest, 2026-07-17) ----

    private static final String KRAFT_QUORUM_CMD =
            "docker exec thesis-k1 /opt/kafka/bin/kafka-broker-api-versions.sh"
                    + " --bootstrap-server 10.0.0.11:9092";

    /** Started-gates (docker logs grep) succeed by default (canned exit 0);
     *  the quorum oracle answers with one "(id: N)" header per broker —
     *  the exact output shape the local formation test counted. */
    private static RecordingSshExecutor kraftReadyRecorder() {
        var ssh = new RecordingSshExecutor();
        ssh.respondTo(KRAFT_QUORUM_CMD, new SshExecutor.ExecResult(0,
                "10.0.0.11:9092 (id: 1 rack: null) -> (\n"
                        + "10.0.0.12:9092 (id: 2 rack: null) -> (\n"
                        + "10.0.0.13:9092 (id: 3 rack: null) -> (\n", ""));
        return ssh;
    }

    @Test
    void kraftSize3StartStopMatchesTheGoldenCommandSequence() throws Exception {
        var ssh = kraftReadyRecorder();
        try (var provider = new RemoteSshProvider(ssh, IPS, Duration.ofSeconds(5))) {
            provider.start(SystemUnderTest.KRAFT, 3);
            provider.stop();
        }
        Path golden = Path.of("src/test/resources/goldens/kraft-size3-start-stop.txt");
        List<String> expected = Files.readAllLines(golden).stream()
                .filter(l -> !l.startsWith("#") && !l.isBlank())
                .toList();
        assertEquals(expected, ssh.commands(),
                "the recorded KRaft sequence must match the reviewed golden verbatim");
    }

    @Test
    void kraftEndpointsAreBareHostPortAndHandlesCarryRealIps() throws Exception {
        var ssh = kraftReadyRecorder();
        try (var provider = new RemoteSshProvider(ssh, IPS, Duration.ofSeconds(5))) {
            var nodes = provider.start(SystemUnderTest.KRAFT, 3);
            // The Kafka bootstrap contract is BARE host:port — KafkaDriver
            // takes no scheme (the local provider strips PLAINTEXT:// for
            // the same reason).
            assertEquals(List.of("10.0.0.11:9092", "10.0.0.12:9092", "10.0.0.13:9092"),
                    provider.clientEndpoints());
            assertEquals("10.0.0.12", nodes.get(1).privateIp());
            assertEquals("thesis-k2", nodes.get(1).containerName());
        }
    }

    @Test
    void kraftQuorumGateFailsClosedNamingTheNodeAndTheObservedCount() {
        // Two brokers joined, one missing: acks=all would still work under
        // min-ISR 2, silently degraded — the gate must refuse the cluster.
        var ssh = new RecordingSshExecutor();
        ssh.respondTo(KRAFT_QUORUM_CMD, new SshExecutor.ExecResult(0,
                "10.0.0.11:9092 (id: 1 rack: null) -> (\n"
                        + "10.0.0.12:9092 (id: 2 rack: null) -> (\n", ""));
        var provider = new RemoteSshProvider(ssh, IPS, Duration.ofSeconds(1));
        var e = assertThrows(IllegalStateException.class,
                () -> provider.start(SystemUnderTest.KRAFT, 3));
        assertTrue(e.getMessage().contains("10.0.0.11"),
                "the gate node must be NAMED: " + e.getMessage());
        assertTrue(e.getMessage().contains("2") && e.getMessage().contains("3"),
                "the observed vs required broker count must be in the message: " + e.getMessage());
    }

    @Test
    void healthGateFailsClosedNamingTheUnhealthyNode() {
        var ssh = new RecordingSshExecutor();
        ssh.respondTo("curl -sf --max-time 2 http://127.0.0.1:2379/health",
                new SshExecutor.ExecResult(7, "", "connection refused"));

        var provider = new RemoteSshProvider(ssh, IPS, Duration.ofSeconds(1));
        var e = assertThrows(IllegalStateException.class,
                () -> provider.start(SystemUnderTest.ETCD, 3));
        assertTrue(e.getMessage().contains("10.0.0.11"),
                "the unhealthy node must be NAMED: " + e.getMessage());
    }
}
