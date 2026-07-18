package gr.thesis.bench.driver;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of asonnino/hotstuff's benchmark LogParser
 * (benchmark/benchmark/logs.py, fetched VERBATIM at the pinned commit
 * dc01ac8, 2026-07-18) — the piece that turns raw {@code node -vv} and
 * {@code client} logs into the canonical SUMMARY block. On the campaign
 * there is no fab/Python tooling on any VM, so the harness must do what
 * logs.py does, with the SAME regexes and the SAME formulas, or HotStuff
 * has no metrics at all (its logs ARE its metrics — P2.5/P4.5).
 *
 * Faithful-port rules:
 *  - every regex is logs.py's, character for character;
 *  - timestamps: ISO-8601 'Z' strings to fractional epoch seconds;
 *  - proposals/commits merged across nodes keeping the EARLIEST timestamp;
 *  - batch sizes only count digests that actually committed;
 *  - end-to-end latency pairs client k with node-log k (logs.py's zip):
 *    with the campaign's single client, node log 0 MUST be the client's
 *    target node (the only node that assembles its sample batches);
 *  - the SUMMARY template is byte-identical to result()'s, so
 *    {@link HotStuffSummary#parse} consumes it unchanged — summarize()
 *    round-trips its own output through that strict parser before
 *    returning, so an unparseable SUMMARY can never leave this class.
 *
 * One deliberate DEVIATION, our house rule over logs.py's: a run with ZERO
 * committed batches throws instead of emitting an all-zero SUMMARY —
 * a fabricated-looking zero row is exactly what fail-closed exists to
 * prevent (the wedge/no-commit case is reported as a failed run, not data).
 */
public final class HotStuffLogAnalyzer {

    // ---- logs.py regexes, verbatim ----
    private static final Pattern CLIENT_ERROR = Pattern.compile("Error");
    private static final Pattern TX_SIZE = Pattern.compile("Transactions size: (\\d+)");
    private static final Pattern TX_RATE = Pattern.compile("Transactions rate: (\\d+)");
    private static final Pattern CLIENT_START = Pattern.compile("\\[(.*Z) .* Start ");
    private static final Pattern RATE_MISS = Pattern.compile("rate too high");
    private static final Pattern SAMPLE_SENT = Pattern.compile("\\[(.*Z) .* sample transaction (\\d+)");
    private static final Pattern NODE_PANIC = Pattern.compile("panic");
    private static final Pattern PROPOSAL = Pattern.compile("\\[(.*Z) .* Created B\\d+ -> ([^ ]+=)");
    private static final Pattern COMMIT = Pattern.compile("\\[(.*Z) .* Committed B\\d+ -> ([^ ]+=)");
    private static final Pattern BATCH_SIZE = Pattern.compile("Batch ([^ ]+) contains (\\d+) B");
    private static final Pattern SAMPLE_BATCH = Pattern.compile("Batch ([^ ]+) contains sample tx (\\d+)");
    private static final Pattern[] CONFIGS = {
            Pattern.compile("Timeout delay .* (\\d+)"),
            Pattern.compile("consensus.* Sync retry delay .* (\\d+)"),
            Pattern.compile("Garbage collection .* (\\d+)"),
            Pattern.compile("mempool.* Sync retry delay .* (\\d+)"),
            Pattern.compile("Sync retry nodes .* (\\d+)"),
            Pattern.compile("Batch size .* (\\d+)"),
            Pattern.compile("Max batch delay .* (\\d+)"),
    };
    private static final String[] CONFIG_NAMES = {
            "Timeout delay", "consensus Sync retry delay", "Garbage collection depth",
            "mempool Sync retry delay", "Sync retry nodes", "Batch size", "Max batch delay",
    };
    private static final String RULE = "-".repeat(41);

    private HotStuffLogAnalyzer() { }

    /**
     * @param clientLogs one entry per client (the campaign runs exactly one)
     * @param nodeLogs   one entry per node; index 0 MUST be the client's
     *                   target node (logs.py's zip pairing — see class doc)
     * @param faults     crash-fault count the run was configured with (0 on
     *                   baseline; committee size = nodes + faults)
     * @return the canonical SUMMARY block, already validated by
     *         {@link HotStuffSummary#parse}
     */
    public static String summarize(List<String> clientLogs, List<String> nodeLogs, int faults) {
        if (clientLogs.isEmpty() || nodeLogs.isEmpty()) {
            throw new IllegalArgumentException("need at least one client log and one node log");
        }

        // ---- clients (logs.py _parse_clients) ----
        long txSize = -1;
        long rateSum = 0;
        double startMin = Double.MAX_VALUE;
        List<Map<Long, Double>> sentSamples = new java.util.ArrayList<>();
        for (int i = 0; i < clientLogs.size(); i++) {
            String log = clientLogs.get(i);
            if (CLIENT_ERROR.matcher(log).find()) {
                throw new IllegalStateException("client log " + i
                        + " contains 'Error' — client panicked, run is void (logs.py contract)");
            }
            long size = requiredLong(log, TX_SIZE, "Transactions size", "client log " + i);
            if (txSize < 0) txSize = size;
            rateSum += requiredLong(log, TX_RATE, "Transactions rate", "client log " + i);
            startMin = Math.min(startMin,
                    toPosix(requiredGroup(log, CLIENT_START, "Start timestamp", "client log " + i)));
            Matcher m = SAMPLE_SENT.matcher(log);
            Map<Long, Double> samples = new HashMap<>();
            while (m.find()) samples.put(Long.parseLong(m.group(2)), toPosix(m.group(1)));
            sentSamples.add(samples);
        }

        // ---- nodes (logs.py _parse_nodes + _merge_results) ----
        Map<String, Double> proposals = new HashMap<>();
        Map<String, Double> commits = new HashMap<>();
        Map<String, Long> sizes = new HashMap<>();
        List<Map<Long, String>> receivedSamples = new java.util.ArrayList<>();
        long[] config = null;
        for (int i = 0; i < nodeLogs.size(); i++) {
            String log = nodeLogs.get(i);
            if (NODE_PANIC.matcher(log).find()) {
                throw new IllegalStateException("node log " + i
                        + " contains 'panic' — node crashed, run is void (logs.py contract)");
            }
            mergeEarliest(proposals, log, PROPOSAL);
            mergeEarliest(commits, log, COMMIT);
            Matcher s = BATCH_SIZE.matcher(log);
            while (s.find()) sizes.put(s.group(1), Long.parseLong(s.group(2)));
            Matcher b = SAMPLE_BATCH.matcher(log);
            Map<Long, String> received = new HashMap<>();
            while (b.find()) received.put(Long.parseLong(b.group(2)), b.group(1));
            receivedSamples.add(received);
            if (config == null) {
                config = new long[CONFIGS.length];
                for (int c = 0; c < CONFIGS.length; c++) {
                    config[c] = requiredLong(log, CONFIGS[c], CONFIG_NAMES[c], "node log " + i);
                }
            }
        }
        sizes.keySet().retainAll(commits.keySet()); // only committed batches count

        if (commits.isEmpty()) {
            // DEVIATION from logs.py (which emits zeros): an all-zero SUMMARY
            // reads like data. No commit = failed run, reported loudly.
            throw new IllegalStateException(
                    "no committed batch in any node log — refusing to synthesize a zero SUMMARY"
                            + " (wedged or dead cluster; report the run as failed)");
        }

        // ---- formulas, verbatim ----
        double consensusStart = proposals.values().stream().mapToDouble(d -> d).min().orElseThrow();
        double lastCommit = commits.values().stream().mapToDouble(d -> d).max().orElseThrow();
        long totalBytes = sizes.values().stream().mapToLong(l -> l).sum();
        double consensusDuration = lastCommit - consensusStart;
        double consensusBps = totalBytes / consensusDuration;
        double consensusTps = consensusBps / txSize;
        double consensusLatency = commits.entrySet().stream()
                .mapToDouble(e -> {
                    Double p = proposals.get(e.getKey());
                    if (p == null) {
                        throw new IllegalStateException("batch " + e.getKey()
                                + " committed but never proposed in any log — inconsistent logs");
                    }
                    return e.getValue() - p;
                }).average().orElse(0);

        double e2eDuration = lastCommit - startMin;
        double e2eBps = totalBytes / e2eDuration;
        double e2eTps = e2eBps / txSize;
        java.util.DoubleSummaryStatistics e2e = new java.util.DoubleSummaryStatistics();
        int pairs = Math.min(sentSamples.size(), receivedSamples.size()); // logs.py zip
        for (int i = 0; i < pairs; i++) {
            for (Map.Entry<Long, String> r : receivedSamples.get(i).entrySet()) {
                Double committed = commits.get(r.getValue());
                if (committed == null) continue; // sample batch never committed
                Double sent = sentSamples.get(i).get(r.getKey());
                if (sent == null) {
                    throw new IllegalStateException("sample tx " + r.getKey()
                            + " was batched but never sent by client " + i
                            + " — node/client logs are misaligned (logs.py's assert)");
                }
                e2e.accept(committed - sent);
            }
        }
        double e2eLatency = e2e.getCount() > 0 ? e2e.getAverage() : 0;

        String summary = template(faults, nodeLogs.size() + faults, rateSum, txSize,
                Math.round(e2eDuration), config,
                Math.round(consensusTps), Math.round(consensusBps),
                Math.round(consensusLatency * 1000),
                Math.round(e2eTps), Math.round(e2eBps), Math.round(e2eLatency * 1000));
        HotStuffSummary.parse(summary); // self-check: never emit the unparseable
        return summary;
    }

    /** result()'s f-string, byte-identical (Locale.US "%,d" = Python "{:,}"). */
    private static String template(int faults, long committee, long rate, long txSize,
                                   long durationSecs, long[] cfg,
                                   long cTps, long cBps, long cLatMs,
                                   long eTps, long eBps, long eLatMs) {
        return "\n" + RULE + "\n"
                + " SUMMARY:\n"
                + RULE + "\n"
                + " + CONFIG:\n"
                + " Faults: " + n(faults) + " nodes\n"
                + " Committee size: " + n(committee) + " nodes\n"
                + " Input rate: " + n(rate) + " tx/s\n"
                + " Transaction size: " + n(txSize) + " B\n"
                + " Execution time: " + n(durationSecs) + " s\n"
                + "\n"
                + " Consensus timeout delay: " + n(cfg[0]) + " ms\n"
                + " Consensus sync retry delay: " + n(cfg[1]) + " ms\n"
                + " Mempool GC depth: " + n(cfg[2]) + " rounds\n"
                + " Mempool sync retry delay: " + n(cfg[3]) + " ms\n"
                + " Mempool sync retry nodes: " + n(cfg[4]) + " nodes\n"
                + " Mempool batch size: " + n(cfg[5]) + " B\n"
                + " Mempool max batch delay: " + n(cfg[6]) + " ms\n"
                + "\n"
                + " + RESULTS:\n"
                + " Consensus TPS: " + n(cTps) + " tx/s\n"
                + " Consensus BPS: " + n(cBps) + " B/s\n"
                + " Consensus latency: " + n(cLatMs) + " ms\n"
                + "\n"
                + " End-to-end TPS: " + n(eTps) + " tx/s\n"
                + " End-to-end BPS: " + n(eBps) + " B/s\n"
                + " End-to-end latency: " + n(eLatMs) + " ms\n"
                + RULE + "\n";
    }

    private static String n(long v) {
        return String.format(java.util.Locale.US, "%,d", v);
    }

    /** logs.py _merge_results: keep the EARLIEST timestamp per digest. */
    private static void mergeEarliest(Map<String, Double> into, String log, Pattern p) {
        Matcher m = p.matcher(log);
        while (m.find()) {
            double t = toPosix(m.group(1));
            into.merge(m.group(2), t, Math::min);
        }
    }

    private static long requiredLong(String log, Pattern p, String name, String where) {
        return Long.parseLong(requiredGroup(log, p, name, where));
    }

    private static String requiredGroup(String log, Pattern p, String name, String where) {
        Matcher m = p.matcher(log);
        if (!m.find()) {
            throw new IllegalStateException("'" + name + "' not found in " + where
                    + " — the log is not a -vv benchmark log (refusing to fabricate)");
        }
        return m.group(m.groupCount());
    }

    /** logs.py _to_posix: ISO-8601 'Z' timestamp to fractional epoch seconds. */
    private static double toPosix(String iso) {
        Instant t = Instant.parse(iso);
        return t.getEpochSecond() + t.getNano() / 1e9;
    }
}
