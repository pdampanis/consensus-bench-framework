package gr.thesis.bench.topology;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3.3a (M4.1) acceptance (integration — Docker): the REAL sshj executor
 * against a real sshd, key-authenticated, exactly like loadgen → node on
 * the campaign's private network. Execute, don't assert: stdout, stderr,
 * exit codes and the fail-closed throw are all observed against a live
 * server, not mocked.
 */
class SshjExecutorTest {

    /** Test-only sshd (linuxserver/openssh-server), digest-pinned (D2). */
    private static final String SSHD_IMAGE =
            "linuxserver/openssh-server@sha256:204b64775f7dfb17761ed0644df348ba3cd811628334cc723aa69d61f4db893d";

    @Test
    void execsCommandsOverRealSshWithKeyAuth(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        // Fresh throwaway keypair — the same shape the campaign uses
        // (cluster keypair generated at setup, distributed to nodes).
        Path key = tmp.resolve("test_ed25519");
        Process keygen = new ProcessBuilder(
                "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", key.toString())
                .redirectErrorStream(true).start();
        assertEquals(0, keygen.waitFor(), "ssh-keygen must succeed");
        String pubKey = Files.readString(tmp.resolve("test_ed25519.pub")).trim();

        try (GenericContainer<?> sshd = new GenericContainer<>(DockerImageName.parse(SSHD_IMAGE))
                .withEnv("USER_NAME", "bench")
                .withEnv("PUBLIC_KEY", pubKey)
                .withExposedPorts(2222)
                .waitingFor(Wait.forLogMessage(".*\\[ls\\.io-init\\] done.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(60)))) {
            sshd.start();

            try (var ssh = new SshjExecutor("bench", key)) {
                String host = sshd.getHost();
                int port = sshd.getMappedPort(2222);

                // 1. stdout comes back verbatim; exit 0.
                var hello = ssh.exec(host, port, "echo hello-from-remote");
                assertEquals(0, hello.exitCode());
                assertEquals("hello-from-remote", hello.stdout().trim());

                // 2. Non-zero exit and stderr are surfaced, not swallowed.
                var failing = ssh.exec(host, port, "echo boom >&2; exit 7");
                assertEquals(7, failing.exitCode());
                assertTrue(failing.stderr().contains("boom"), failing.stderr());

                // 3. execOrThrow fails closed with command + stderr.
                var e = assertThrows(IllegalStateException.class,
                        () -> ssh.execOrThrow(host, port, "echo nope >&2; false"));
                assertTrue(e.getMessage().contains("nope"), e.getMessage());

                // 4. The per-host connection is REUSED (pooled client): a
                //    second command must not renegotiate — observable as
                //    plain success here, pinned as no-reconnect by the
                //    executor's cache (one client per host:port).
                assertEquals("second", ssh.exec(host, port, "echo second").stdout().trim());
            }
        }
    }
}
