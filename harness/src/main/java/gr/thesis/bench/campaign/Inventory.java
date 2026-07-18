package gr.thesis.bench.campaign;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed view of {@code deploy/inventory.env} — the file Terraform GENERATES
 * on every apply (main.tf local_file.inventory), which is why it cannot
 * drift from reality (the structural fix for v6's fatal C1). The campaign
 * layer reads ONLY this file for topology; nothing is ever hardcoded or
 * guessed.
 *
 * Fail-closed: a key the runner needs but the file lacks throws naming the
 * key AND the file — a silently defaulted IP would point the harness (and
 * the fault injector) at the wrong machine.
 */
public record Inventory(List<String> nodePrivateIps, String loadgenPrivateIp, Path sshKey) {

    public static Inventory parse(Path file) throws IOException {
        Map<String, String> kv = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int eq = l.indexOf('=');
            if (eq > 0) kv.put(l.substring(0, eq), l.substring(eq + 1));
        }
        int count = Integer.parseInt(require(kv, "CONSENSUS_NODE_COUNT", file));
        List<String> ips = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            ips.add(require(kv, "PRIVATE_NODE" + i, file));
        }
        String key = require(kv, "SSH_KEY", file);
        if (key.startsWith("~/")) {
            key = System.getProperty("user.home") + key.substring(1);
        }
        return new Inventory(List.copyOf(ips), require(kv, "PRIVATE_LOADGEN", file), Path.of(key));
    }

    private static String require(Map<String, String> kv, String key, Path file) {
        String v = kv.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(
                    "inventory " + file + " is missing " + key
                            + " — regenerate it with `terraform apply` (it is a Terraform OUTPUT)");
        }
        return v;
    }
}
