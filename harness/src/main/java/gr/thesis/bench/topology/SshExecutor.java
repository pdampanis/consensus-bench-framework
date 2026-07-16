package gr.thesis.bench.topology;

/**
 * The single seam every remote orchestration action flows through (P3.3a,
 * M4.1). RemoteSshProvider and the remote FaultInjector never touch SSH
 * directly — they emit commands through this interface, so the SAME code
 * paths run against the real cluster (SshjExecutor) and against the
 * recorder the G2 golden tests read back (RecordingSshExecutor, test
 * scope). That equivalence is the whole point: the golden a human reviews
 * is byte-for-byte what a billed VM would have run — the check v6 never
 * had.
 *
 * Failure semantics, fail closed: {@link #exec} reports the exit code and
 * both streams (probes may legitimately inspect a non-zero exit);
 * {@link #execOrThrow} is for actions that must succeed — it throws with
 * the command AND the remote stderr in the message, because a swallowed
 * remote failure is v6's {@code || true} disease over a network.
 */
public interface SshExecutor extends AutoCloseable {

    /** One remote command's complete outcome — no stream ever discarded. */
    record ExecResult(int exitCode, String stdout, String stderr) {}

    /** Run {@code command} on {@code host:port}; never throws on a non-zero
     *  exit (that is a reportable outcome), only on transport failure. */
    ExecResult exec(String host, int port, String command) throws Exception;

    /** Run a command that MUST succeed; non-zero exit ⇒ IllegalStateException
     *  naming the command and carrying the remote stderr. */
    default String execOrThrow(String host, int port, String command) throws Exception {
        ExecResult r = exec(host, port, command);
        if (r.exitCode() != 0) {
            throw new IllegalStateException(
                    "remote command failed (exit " + r.exitCode() + ") on " + host + ": "
                            + command + " — stderr: " + r.stderr());
        }
        return r.stdout();
    }

    @Override void close();
}
