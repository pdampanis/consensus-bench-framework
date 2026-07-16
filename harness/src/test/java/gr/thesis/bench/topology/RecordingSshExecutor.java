package gr.thesis.bench.topology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The golden-file substrate (M4.1/M4.4): records every command verbatim,
 * in order, addressed as {@code host:port$ command}, and returns canned
 * results so provider/injector logic that branches on remote output (e.g.
 * `ip -o route get` interface resolution) can be driven deterministically.
 * Default response is a clean success with empty output. Failures are
 * recorded too — a golden must show what was TRIED, not only what worked.
 */
final class RecordingSshExecutor implements SshExecutor {

    private final List<String> commands = new ArrayList<>();
    private final Map<String, ExecResult> canned = new HashMap<>();

    /** Program the result for an exact command string (any host). */
    void respondTo(String command, ExecResult result) {
        canned.put(command, result);
    }

    List<String> commands() {
        return List.copyOf(commands);
    }

    @Override
    public ExecResult exec(String host, int port, String command) {
        commands.add(host + ":" + port + "$ " + command);
        return canned.getOrDefault(command, new ExecResult(0, "", ""));
    }

    @Override public void close() { }
}
