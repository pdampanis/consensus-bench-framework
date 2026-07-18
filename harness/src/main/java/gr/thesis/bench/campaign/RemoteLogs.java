package gr.thesis.bench.campaign;

import gr.thesis.bench.topology.SshExecutor;

import java.util.Base64;

/**
 * Bounded remote-log retrieval (the P4.5 streaming path, done simply).
 * SshjExecutor reads command output only AFTER join, which assumes it fits
 * sshj's ~2 MB channel window — true for orchestration commands, FALSE for
 * a full `node -vv` benchmark log (whose HEAD carries the config lines the
 * analyzer fails closed without, so tail-truncation is not an option).
 *
 * Mechanics: snapshot the log to a remote file, then read it in 1 MiB
 * chunks via `dd | base64 -w0` (base64 inflates 4/3 → ~1.37 MB per command,
 * safely inside the window and the 30 s bound). Every step is an exact
 * command, so the sequence is golden-recordable like everything else.
 */
public final class RemoteLogs {

    private static final int SSH_PORT = 22;
    private static final long CHUNK = 1024 * 1024;

    private RemoteLogs() { }

    /** Full `docker logs` of a container (both streams), any size. */
    public static String dockerLogs(SshExecutor ssh, String host, String container)
            throws Exception {
        String remote = "/root/thesis-log-" + container;
        ssh.execOrThrow(host, SSH_PORT, "docker logs " + container + " > " + remote + " 2>&1");
        try {
            return readRemoteFile(ssh, host, remote);
        } finally {
            ssh.execOrThrow(host, SSH_PORT, "rm -f " + remote);
        }
    }

    /** Chunked read of an arbitrary remote file — bounded per command.
     *  Chunks are accumulated as BYTES and decoded once at the end: a
     *  chunk boundary may split a multi-byte character, so per-chunk
     *  string decoding would corrupt exactly at the seams. */
    public static String readRemoteFile(SshExecutor ssh, String host, String path)
            throws Exception {
        long size = Long.parseLong(
                ssh.execOrThrow(host, SSH_PORT, "stat -c %s " + path).strip());
        var buf = new java.io.ByteArrayOutputStream((int) Math.min(size, Integer.MAX_VALUE));
        for (long block = 0; block * CHUNK < size; block++) {
            String b64 = ssh.execOrThrow(host, SSH_PORT,
                    "dd if=" + path + " bs=1048576 skip=" + block + " count=1 status=none"
                            + " | base64 -w0").strip();
            buf.writeBytes(Base64.getDecoder().decode(b64));
        }
        if (buf.size() != size) {
            throw new IllegalStateException("remote file " + path + " on " + host
                    + " transferred " + buf.size() + " of " + size + " bytes — truncated");
        }
        return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
