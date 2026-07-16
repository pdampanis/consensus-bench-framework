package gr.thesis.bench.topology;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3.3a (M4.1) — the SPI every remote orchestration action flows through,
 * exercised via the RECORDING implementation the G2 golden tests are built
 * on. The recorder's contract IS the golden-file contract: every command,
 * verbatim, in order, addressed as {@code host:port$ command} — so a human
 * read-through of a golden sees exactly what a billed VM would have run
 * (the verification v6 never had).
 */
class SshExecutorContractTest {

    @Test
    void recordsEveryCommandVerbatimInOrderWithItsAddress() throws Exception {
        var ssh = new RecordingSshExecutor();
        ssh.exec("10.0.0.11", 22, "docker kill thesis-etcd1");
        ssh.exec("10.0.0.12", 22, "ip -o route get 10.0.0.11");

        assertEquals(List.of(
                "10.0.0.11:22$ docker kill thesis-etcd1",
                "10.0.0.12:22$ ip -o route get 10.0.0.11"),
                ssh.commands());
    }

    @Test
    void cannedResponsesAreReturnedAndDefaultIsCleanSuccess() throws Exception {
        var ssh = new RecordingSshExecutor();
        ssh.respondTo("ip -o route get 10.0.0.11",
                new SshExecutor.ExecResult(0, "10.0.0.11 dev enp7s0 src 10.0.0.12", ""));

        var canned = ssh.exec("10.0.0.12", 22, "ip -o route get 10.0.0.11");
        assertEquals("10.0.0.11 dev enp7s0 src 10.0.0.12", canned.stdout());

        var dflt = ssh.exec("10.0.0.12", 22, "anything else");
        assertEquals(0, dflt.exitCode());
        assertEquals("", dflt.stdout());
    }

    @Test
    void execOrThrowFailsClosedNamingCommandAndStderr() {
        var ssh = new RecordingSshExecutor();
        ssh.respondTo("docker start ghost",
                new SshExecutor.ExecResult(1, "", "No such container: ghost"));

        var e = assertThrows(IllegalStateException.class,
                () -> ssh.execOrThrow("10.0.0.11", 22, "docker start ghost"));
        assertTrue(e.getMessage().contains("docker start ghost"),
                "the failing COMMAND must be in the error: " + e.getMessage());
        assertTrue(e.getMessage().contains("No such container"),
                "the remote STDERR must be in the error: " + e.getMessage());
        // The failure is still recorded — a golden must show what was tried.
        assertEquals(List.of("10.0.0.11:22$ docker start ghost"), ssh.commands());
    }
}
