package gr.thesis.bench.topology;

import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The production SshExecutor (P3.3a): sshj, key-authenticated, one pooled
 * client per host:port reused across commands — a fault run issues many
 * commands per node and per-command reconnects would add seconds of jitter
 * exactly when the measurement is most sensitive.
 *
 * Scope decisions, documented:
 *  - Host keys are NOT verified (PromiscuousVerifier). The harness talks
 *    loadgen → nodes over the campaign's PRIVATE network (10.0.0.0/24,
 *    MASTER_PLAN §2); a MITM inside that network is outside the threat
 *    model, and strict checking would add first-contact provisioning
 *    friction for zero measurement value.
 *  - Commands are BOUNDED at 30 s (the F18 spirit: nothing the harness
 *    waits on may hang a run); orchestration commands finish in
 *    milliseconds-to-seconds. Output is read AFTER join, which assumes it
 *    fits sshj's channel window (2 MB) — true for every orchestration
 *    command; bulk log collection (P4.5) gets a streaming path when built.
 */
public final class SshjExecutor implements SshExecutor {

    private static final Logger log = LoggerFactory.getLogger(SshjExecutor.class);
    private static final int COMMAND_TIMEOUT_SECS = 30;
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final String user;
    private final Path privateKey;
    private final Map<String, SSHClient> clients = new ConcurrentHashMap<>();

    public SshjExecutor(String user, Path privateKey) {
        this.user = user;
        this.privateKey = privateKey;
    }

    @Override
    public ExecResult exec(String host, int port, String command) throws IOException {
        SSHClient client = connectedClient(host, port);
        try (Session session = client.startSession()) {
            Session.Command cmd = session.exec(command);
            cmd.join(COMMAND_TIMEOUT_SECS, TimeUnit.SECONDS);
            String stdout = IOUtils.readFully(cmd.getInputStream()).toString();
            String stderr = IOUtils.readFully(cmd.getErrorStream()).toString();
            Integer status = cmd.getExitStatus();
            if (status == null) {
                // No exit status after join = the command outlived the bound
                // or the channel died — fail loud, never guess an outcome.
                throw new IllegalStateException("no exit status within "
                        + COMMAND_TIMEOUT_SECS + " s on " + host + ": " + command);
            }
            return new ExecResult(status, stdout, stderr);
        }
    }

    private SSHClient connectedClient(String host, int port) throws IOException {
        String key = host + ":" + port;
        SSHClient cached = clients.get(key);
        if (cached != null && cached.isConnected() && cached.isAuthenticated()) {
            return cached;
        }
        if (cached != null) {
            log.warn("ssh connection to {} lost — reconnecting", key);
            IOUtils.closeQuietly(cached);
            clients.remove(key, cached);
        }
        SSHClient client = new SSHClient(new DefaultConfig());
        client.addHostKeyVerifier(new PromiscuousVerifier()); // private net; see javadoc
        client.setConnectTimeout(CONNECT_TIMEOUT_MS);
        client.connect(host, port);
        client.authPublickey(user, client.loadKeys(privateKey.toString()));
        SSHClient raced = clients.putIfAbsent(key, client);
        if (raced != null && raced.isConnected()) { // another thread won the race
            IOUtils.closeQuietly(client);
            return raced;
        }
        log.debug("phase: ssh connect — {}@{}", user, key);
        return client;
    }

    @Override
    public void close() {
        for (SSHClient c : clients.values()) IOUtils.closeQuietly(c);
        clients.clear();
    }
}
