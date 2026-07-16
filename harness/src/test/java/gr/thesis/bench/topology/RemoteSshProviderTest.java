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
    void unsupportedSystemsAndOversizedClustersFailClosed() {
        var provider = new RemoteSshProvider(new RecordingSshExecutor(), IPS, Duration.ofSeconds(5));
        assertThrows(UnsupportedOperationException.class,
                () -> provider.start(SystemUnderTest.KAFKA_ZK, 3));
        assertThrows(IllegalArgumentException.class,
                () -> provider.start(SystemUnderTest.ETCD, 4),
                "cannot start more members than provisioned nodes");
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
