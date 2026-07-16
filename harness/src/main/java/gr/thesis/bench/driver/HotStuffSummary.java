package gr.thesis.bench.driver;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HotStuff measurement boundary (P2.5 / M2.5): a typed view of the
 * SUMMARY block that asonnino/hotstuff's benchmark tooling prints
 * (benchmark/benchmark/logs.py result(), format captured 2026-07-16).
 * HotStuff exposes no Prometheus metrics and its client output is this
 * block — it is the system's ONLY metrics source, so parsing fails CLOSED:
 * a silently-defaulted field would fabricate a thesis number.
 *
 * Format facts encoded here: integers carry thousands separators
 * ("7,812 tx/s"), latencies are milliseconds, and one log must contain
 * exactly ONE block (an appended rerun log is ambiguous — refuse it).
 * {@code endToEndTps}/{@code endToEndLatencyMs} are the client-observed
 * primaries (the metric class every other driver reports);
 * {@code consensusTps}/{@code consensusLatencyMs} are protocol-internal.
 * The campaign requires {@code transactionSizeBytes == 1024} (the
 * cross-system value-size contract) — the campaign runner enforces it.
 */
public record HotStuffSummary(long faults,
                              long committeeSize,
                              long inputRateTxPerSec,
                              long transactionSizeBytes,
                              long executionTimeSecs,
                              long consensusTps,
                              long consensusLatencyMs,
                              long endToEndTps,
                              long endToEndLatencyMs) {

    private static final Pattern SUMMARY_MARK = Pattern.compile("SUMMARY:");

    public static HotStuffSummary parse(String log) {
        Matcher marks = SUMMARY_MARK.matcher(log);
        int blocks = 0;
        while (marks.find()) blocks++;
        if (blocks != 1) {
            throw new IllegalStateException(
                    "expected exactly one SUMMARY block, found " + blocks
                            + (blocks == 0 ? " — not a completed benchmark log"
                                           : " — ambiguous appended log, refuse to pick"));
        }
        return new HotStuffSummary(
                field(log, "Faults: ([\\d,]+) nodes", "Faults"),
                field(log, "Committee size: ([\\d,]+) nodes", "Committee size"),
                field(log, "Input rate: ([\\d,]+) tx/s", "Input rate"),
                field(log, "Transaction size: ([\\d,]+) B", "Transaction size"),
                field(log, "Execution time: ([\\d,]+) s", "Execution time"),
                field(log, "Consensus TPS: ([\\d,]+) tx/s", "Consensus TPS"),
                field(log, "Consensus latency: ([\\d,]+) ms", "Consensus latency"),
                field(log, "End-to-end TPS: ([\\d,]+) tx/s", "End-to-end TPS"),
                field(log, "End-to-end latency: ([\\d,]+) ms", "End-to-end latency"));
    }

    /** One field, thousands separators stripped; absent ⇒ fail loud with
     *  the FIELD NAME (a count with no cause is undebuggable — the same
     *  rule as the engine's firstError). Note: upstream prints '?' for an
     *  unknown committee size — that must fail here, not default. */
    private static long field(String log, String regex, String name) {
        Matcher m = Pattern.compile(regex).matcher(log);
        if (!m.find()) {
            throw new IllegalStateException(
                    "SUMMARY block is missing '" + name + "' — refusing to fabricate it");
        }
        return Long.parseLong(m.group(1).replace(",", ""));
    }
}
