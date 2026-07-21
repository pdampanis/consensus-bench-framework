package gr.thesis.bench.campaign;

import gr.thesis.bench.topology.SshExecutor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chunked log path (P4.5 streaming, done simply): full logs of any
 * size must arrive intact through 1 MiB `dd | base64` reads — sshj's
 * output-after-join pattern caps a single command's output at the ~2 MB
 * channel window, and a truncated -vv log loses exactly the head lines
 * the HotStuff analyzer fails closed without.
 */
class RemoteLogsTest {

    /** Minimal canned executor (RecordingSshExecutor is topology-package
     *  private on purpose — the golden substrate stays where goldens live). */
    private static final class Canned implements SshExecutor {
        final List<String> commands = new ArrayList<>();
        final Map<String, ExecResult> responses = new HashMap<>();

        @Override public ExecResult exec(String host, int port, String command) {
            commands.add(host + ":" + port + "$ " + command);
            return responses.getOrDefault(command, new ExecResult(0, "", ""));
        }
        @Override public void close() { }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void dockerLogsSnapshotsChunksAndCleansUp() throws Exception {
        var ssh = new Canned();
        String content = "line one\nline two\n";
        ssh.responses.put("stat -c %s /tmp/thesis-log-thesis-hs1",
                new SshExecutor.ExecResult(0, content.length() + "\n", ""));
        ssh.responses.put("dd if=/tmp/thesis-log-thesis-hs1 bs=1048576 skip=0 count=1 status=none"
                        + " | base64 -w0",
                new SshExecutor.ExecResult(0, b64(content.getBytes()), ""));

        String got = RemoteLogs.dockerLogs(ssh, "10.0.0.11", "thesis-hs1");

        assertEquals(content, got);
        assertEquals(List.of(
                "10.0.0.11:22$ docker logs thesis-hs1 > /tmp/thesis-log-thesis-hs1 2>&1",
                "10.0.0.11:22$ stat -c %s /tmp/thesis-log-thesis-hs1",
                "10.0.0.11:22$ dd if=/tmp/thesis-log-thesis-hs1 bs=1048576 skip=0 count=1"
                        + " status=none | base64 -w0",
                "10.0.0.11:22$ rm -f /tmp/thesis-log-thesis-hs1"),
                ssh.commands, "snapshot -> size -> chunk(s) -> cleanup, exactly");
    }

    @Test
    void filesLargerThanOneChunkArriveIntact() throws Exception {
        // 1 MiB + a tail: two dd reads, byte-accumulated, decoded once (a
        // chunk boundary may split a multi-byte char — pinned by the µ).
        byte[] first = new byte[1024 * 1024];
        java.util.Arrays.fill(first, (byte) 'a');
        first[first.length - 1] = (byte) 0xC2; // first byte of µ, split at the seam
        byte[] second = {(byte) 0xB5, 't', 'a', 'i', 'l'};
        var ssh = new Canned();
        ssh.responses.put("stat -c %s /f",
                new SshExecutor.ExecResult(0, String.valueOf(first.length + second.length), ""));
        ssh.responses.put("dd if=/f bs=1048576 skip=0 count=1 status=none | base64 -w0",
                new SshExecutor.ExecResult(0, b64(first), ""));
        ssh.responses.put("dd if=/f bs=1048576 skip=1 count=1 status=none | base64 -w0",
                new SshExecutor.ExecResult(0, b64(second), ""));

        String got = RemoteLogs.readRemoteFile(ssh, "10.0.0.11", "/f");
        assertTrue(got.endsWith("µtail"), "the split multi-byte char must survive the seam");
        assertEquals(1024 * 1024 - 1 + 1 + 4, got.length());
    }

    @Test
    void shortTransferFailsLoudNotSilent() {
        var ssh = new Canned();
        ssh.responses.put("stat -c %s /f", new SshExecutor.ExecResult(0, "10", ""));
        ssh.responses.put("dd if=/f bs=1048576 skip=0 count=1 status=none | base64 -w0",
                new SshExecutor.ExecResult(0, b64("four".getBytes()), ""));
        var e = assertThrows(IllegalStateException.class,
                () -> RemoteLogs.readRemoteFile(ssh, "10.0.0.11", "/f"));
        assertTrue(e.getMessage().contains("truncated"), e.getMessage());
    }
}
