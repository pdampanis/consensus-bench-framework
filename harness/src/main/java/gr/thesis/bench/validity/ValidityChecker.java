package gr.thesis.bench.validity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ValidityChecker (M5.5) — the methodology §4 gates, evaluated per run into
 * {@code validity.json} beside the CSVs. A run is thesis data only if it is
 * VALID; the analysis (analyse.py) excludes the rest and lists why (the
 * no-silent-outlier-removal rule).
 *
 * Three gate states, kept distinct on purpose:
 *  - PASS / FAIL — the gate was EVALUATED against real inputs.
 *  - SKIP        — the gate's input FEATURE is not present (e.g. no
 *                  {@code metrics/} dir because PrometheusExporter (M5.4)
 *                  has not run yet, or the durability probe (P2.6) is
 *                  undecided). SKIP is loud (listed with a reason), never a
 *                  silent pass.
 *
 * The §4 META-RULE is honored precisely: if {@code metrics/} EXISTS (M5.4
 * ran) but a series a gate needs is empty/missing, that gate FAILS — an
 * empty series means a broken retrieval path, exactly when a gate must not
 * wave a run through. Only the whole-feature-absent case is SKIP.
 *
 * A run is {@code valid} iff NO evaluated gate FAILed. SKIP/NA do not fail
 * it, but a run with metric gates still SKIP is only "valid as far as
 * evaluated" — the report says so, and post-M5.4 campaign runs must have
 * those gates actually evaluated before their numbers enter a figure.
 *
 * The metric CSV contract this reads (defined here FIRST, the goldens-style
 * discipline; PrometheusExporter M5.4 must emit exactly it): one file per
 * query name at {@code metrics/<name>.csv}, header row, then data rows whose
 * FIRST column is {@code t_unix} and LAST column is {@code value}; an
 * {@code instance} column, if present, is currently IGNORED — the gates are
 * whole-cluster today; per-node checks would need it and do not exist. Extra
 * label columns between are ignored. This is the runbook §5 shape
 * ({@code t_unix,t_iso,labels…,value}) read tolerantly.
 *
 * Thresholds are PROVISIONAL until the M6.2 pilot pins them numerically
 * (methodology §4/§1); each is a named constant with that caveat.
 */
public final class ValidityChecker {

    private static final Logger log = LoggerFactory.getLogger(ValidityChecker.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Gate 1: loadgen busy fraction must stay below this (the instrument is
     *  not the bottleneck). */
    static final double LOADGEN_CPU_MAX = 0.70;
    /** Gates 1 & 4: CPU steal must be ~0 on dedicated vCPU (D11 loadgen,
     *  consensus nodes). 1% is the stationarity ceiling (§4.4). */
    static final double STEAL_MAX = 0.01;
    /** Gate 1: fixed-rate runs must achieve ≥99% of target (§4.1). */
    static final double RATE_ADHERENCE_MIN = 0.99;
    /** Gate 1d (F52/D15): a BASELINE run's whole-run failure fraction ceiling.
     *  PROVISIONAL until M6.2, and chosen from measured evidence rather than
     *  taste — M0 recorded 0 errors and the CometBFT G1 acceptance ran under
     *  1%. Fault scenarios are exempt BY DESIGN; see {@link
     *  #gateBaselineErrorRate}. */
    static final double BASELINE_ERROR_RATE_MAX = 0.01;
    /** Gate 5: warmup-tail vs measurement-head throughput agreement
     *  (PROVISIONAL — M6.2 fixes it from pilot variance). */
    static final double CONVERGENCE_MAX_REL_DIFF = 0.20;
    /** Gate 6: chrony offset ceiling (§4.6). */
    static final double CLOCK_OFFSET_MAX_S = 0.005;
    /** Gate 3: corroboration window around the fault mark (§4.3, ±60 s). */
    static final long FAULT_WINDOW_MS = 60_000;

    /** Every metrics/<name>.csv this checker reads. Pinned by test against
     *  export_queries.txt (the file M5.4 executes verbatim): a gate that
     *  consults a name no query produces would FAIL every run via the
     *  meta-rule with a FALSE "broken retrieval" diagnosis (F40). */
    static final List<String> CONSULTED_METRICS = List.of(
            "loadgen_cpu", "loadgen_cpu_steal", "node_cpu_steal", "clock_offset",
            "node_up", "etcd_leader_chg", "cmt_rounds", "kafka_urp");

    /**
     * The RULE behind a gate, written into every {@code validity.json}
     * alongside the verdict (S2.1a/S2.2). Two things a bare PASS/FAIL cannot
     * tell a reader months later, with the cluster destroyed:
     *
     * <p><b>Which threshold judged this run.</b> M6.2's job is to fix every
     * PROVISIONAL threshold numerically from pilot variance, so runs from
     * before and after that retune are judged by different numbers. If the
     * verdict does not carry the value, no one can tell which.
     *
     * <p><b>What would make this verdict a FALSE POSITIVE.</b> That knowledge
     * exists in OBSERVABILITY_AND_EXPECTATIONS.md and was, until now,
     * unreachable from any verdict — so a red gate said "threshold exceeded"
     * and the reader had to remember where the catalogue lived. The
     * change-point-detection literature is blunt about this: a large share of
     * automatically detected regressions are not actionable, so an automated
     * verdict that ships without triage guidance trains its reader to ignore
     * it.
     *
     * @param threshold the value in force, or null where the gate is not
     *                  numeric
     * @param reference the methodology section that justifies it
     * @param falsePositives benign causes to rule out BEFORE believing a FAIL
     */
    public record GateSpec(String threshold, String reference, String falsePositives) { }

    /** Every gate's rule, in one reviewable place. Pinned by test against the
     *  gates the checker actually emits — a rule without a gate, or a gate
     *  without a rule, is the F40 drift class in a new costume. */
    static final java.util.Map<String, GateSpec> GATE_SPECS = java.util.Map.ofEntries(
            java.util.Map.entry("rate_adherence", new GateSpec(
                    "achieved/target >= " + RATE_ADHERENCE_MIN, "§4.1",
                    "a short warmup or a cold JVM depresses the early window; check that"
                            + " the run's own convergence gate passed before blaming the"
                            + " client")),
            java.util.Map.entry("baseline_error_rate", new GateSpec(
                    "error_rate < " + BASELINE_ERROR_RATE_MAX + " (PROVISIONAL until M6.2)",
                    "§4.1 / F52",
                    "errors concentrated at the very start are connect-time, not"
                            + " steady-state — check firstError in the run log")),
            java.util.Map.entry("window_headroom", new GateSpec(
                    "in-flight window not pinned at its ceiling", "§4.1",
                    "a window-bound run looks like a slow SYSTEM but is a client ceiling"
                            + " (Little's Law) — compare window/latency against achieved")),
            java.util.Map.entry("convergence", new GateSpec(
                    "|head-tail|/tail <= " + CONVERGENCE_MAX_REL_DIFF + " (PROVISIONAL until M6.2)",
                    "§4.5",
                    "a single stalled second in either window skews the comparison; look"
                            + " at throughput.csv before concluding the run never settled")),
            java.util.Map.entry("loadgen_cpu", new GateSpec(
                    "peak < " + LOADGEN_CPU_MAX, "§4.1 / D11",
                    "a co-tenant on the loadgen (a forgotten collector, an ssh session)"
                            + " raises this without the instrument being the bottleneck")),
            java.util.Map.entry("loadgen_steal", new GateSpec(
                    "peak steal < " + STEAL_MAX, "§4.1 / D11",
                    "steal on a DEDICATED vCPU means a platform problem, not a benchmark"
                            + " one — it is grounds to rerun, not to adjust the harness")),
            java.util.Map.entry("node_cpu_steal", new GateSpec(
                    "peak steal < " + STEAL_MAX, "§4.4",
                    "same as loadgen_steal: a hypervisor-side fact about the hour, so"
                            + " rerun before reading anything into the numbers")),
            java.util.Map.entry("container_restarts", new GateSpec(
                    "no unexpected restarts", "§4.4",
                    "a fault scenario's OWN kill is an expected restart — the audit must"
                            + " be read against the run's scenario, never in isolation")),
            java.util.Map.entry("clock_discipline", new GateSpec(
                    "max |offset| < " + CLOCK_OFFSET_MAX_S + " s", "§4.6",
                    "chrony steps hard after a VM resumes; a single spike at boot does"
                            + " not invalidate a window that is otherwise disciplined")),
            java.util.Map.entry("fault_ground_truth", new GateSpec(
                    "witness moves within ±" + (FAULT_WINDOW_MS / 1000) + " s of the mark",
                    "§4.3 / F41",
                    "node_up cannot witness a `docker kill` — the host node_exporter"
                            + " survives it. An unmoved witness on paxi/hotstuff is the"
                            + " documented no-server-metrics limitation, not a targeting"
                            + " bug; on etcd/kafka/cometbft it IS a reclassify")),
            java.util.Map.entry("durability", new GateSpec(
                    "per-system correctness probe passes", "§4.2 / P2.6",
                    "not implemented — the SKIP is the honest state, not a pass")));

    public enum State { PASS, FAIL, SKIP }

    public record GateResult(String gate, State state, String detail) {
        static GateResult pass(String g, String d) { return new GateResult(g, State.PASS, d); }
        static GateResult fail(String g, String d) { return new GateResult(g, State.FAIL, d); }
        static GateResult skip(String g, String d) { return new GateResult(g, State.SKIP, d); }
    }

    public record Report(boolean valid, List<GateResult> gates) {
        long count(State s) { return gates.stream().filter(g -> g.state() == s).count(); }
    }

    private ValidityChecker() { }

    /** Evaluate a single run directory and WRITE its validity.json. */
    public static Report check(Path runDir) throws IOException {
        JsonNode manifest = JSON.readTree(Files.readString(runDir.resolve("manifest.json")));
        String system = manifest.path("system").asText("");
        boolean hasMetrics = Files.isDirectory(runDir.resolve("metrics"));
        List<GateResult> gates = new ArrayList<>();

        gates.add(gateRateAdherence(runDir, manifest, system));
        gates.add(gateBaselineErrorRate(manifest));
        // Gate 1's window-ceiling half needs the harness's OWN inflight
        // gauge (bench_inflight_current, M5.3) — metrics/ presence cannot
        // decide it, because an empty harness series before M5.3 exists
        // means "not exported yet", not "broken retrieval". Loud SKIP
        // until M5.3 lands; then this becomes an evaluated gate.
        gates.add(GateResult.skip("window_headroom",
                "in-flight-window occupancy needs harness self-metrics"
                        + " (bench_inflight_current, M5.3) — not yet exported"));
        gates.add(gateConvergence(runDir, manifest, system));
        gates.add(gateLoadgenCpu(runDir, hasMetrics));
        gates.add(gateSteal("loadgen_steal", "loadgen_cpu_steal", runDir, hasMetrics));
        gates.add(gateSteal("node_cpu_steal", "node_cpu_steal", runDir, hasMetrics));
        // Gate 4's second half per methodology §4.4 — no collector yet.
        gates.add(GateResult.skip("container_restarts",
                "docker-events restart audit not yet collected (P4.5 open half)"));
        gates.add(gateClock(runDir, hasMetrics));
        gates.add(gateFaultGroundTruth(runDir, manifest, system, hasMetrics));
        gates.add(GateResult.skip("durability",
                "per-system correctness probe not implemented (P2.6 scope undecided)"));

        boolean valid = gates.stream().noneMatch(g -> g.state() == State.FAIL);
        Report report = new Report(valid, gates);
        writeReport(runDir, report);
        return report;
    }

    /** Walk a tree, check every run, return how many were valid. An
     *  uncheckable run (corrupt manifest, malformed CSV) is recorded as
     *  not-valid and the walk CONTINUES — one bad dir must never hide the
     *  verdicts of the runs after it (the analyse.py exclusion discipline). */
    public static int checkTree(Path root) throws IOException {
        int valid = 0, total = 0;
        try (var walk = Files.walk(root)) {
            for (Path m : (Iterable<Path>) walk.filter(p -> p.endsWith("manifest.json"))::iterator) {
                total++;
                try {
                    Report r = check(m.getParent());
                    if (r.valid()) valid++;
                    log.info("{}: {} ({} pass, {} fail, {} skip)", m.getParent(),
                            r.valid() ? "VALID" : "INVALID",
                            r.count(State.PASS), r.count(State.FAIL), r.count(State.SKIP));
                } catch (Exception e) {
                    log.error("{}: UNCHECKABLE — {} (counted as not valid)", m.getParent(), e.toString());
                }
            }
        }
        log.info("{} of {} runs valid", valid, total);
        return valid;
    }

    // ---- gates evaluable from the harness's OWN output (work today) ----

    /** HotStuff's run dir carries summary.txt + raw logs, no per-second
     *  throughput.csv or latency.csv — its client SUMMARY is the metrics
     *  source (P2.5, methodology §2). The throughput-derived gates have no
     *  input BY DESIGN there, not by breakage: SKIP with the reason, while
     *  a MISSING throughput.csv for any driver system stays a FAIL (F42). */
    private static boolean hasNoThroughputSeries(String system) {
        return "hotstuff".equals(system);
    }

    /** Gate 1a (§4.1): fixed-rate runs must achieve ≥99% of target over the
     *  measurement window. Saturation runs (rate 0) have no target → N/A. */
    private static GateResult gateRateAdherence(Path dir, JsonNode m, String system) throws IOException {
        if (hasNoThroughputSeries(system)) {
            return GateResult.skip("rate_adherence",
                    "hotstuff has no throughput.csv — rate adherence is judged from its"
                            + " SUMMARY (input rate vs end-to-end TPS) at analysis time");
        }
        long target = m.path("rate_ops_s").asLong(0);
        if (target <= 0) {
            return GateResult.skip("rate_adherence", "saturation run (no target rate) — N/A");
        }
        int warmup = m.path("warmup_secs").asInt(0);
        int duration = m.path("duration_secs").asInt(Integer.MAX_VALUE);
        double[] window = throughputWindow(dir, warmup, duration);
        if (window.length == 0) {
            return GateResult.fail("rate_adherence", "throughput.csv has no measurement-window rows");
        }
        double mean = mean(window);
        double ratio = mean / target;
        return ratio >= RATE_ADHERENCE_MIN
                ? GateResult.pass("rate_adherence",
                        String.format("achieved %.1f/%d ops/s (%.1f%%)", mean, target, ratio * 100))
                : GateResult.fail("rate_adherence",
                        String.format("achieved only %.1f%% of target (%.1f/%d) — client-bound?",
                                ratio * 100, mean, target));
    }

    /**
     * Gate 1d (F52): the manifest's own `error_rate`, gated at last. The
     * writer computed it from the first commit onward and NOTHING consulted
     * it — `status` tolerates up to 50% failures and `rate_adherence` SKIPs
     * for saturation runs, so a baseline that failed 49% of its operations
     * reported `valid: true`.
     *
     * <p>BASELINE ONLY, by decision (D15/F52). Fault scenarios are SUPPOSED
     * to error: the preregistered paxi wedge (F26) fails every write at the
     * 5 s driver bound by design, and DOUBLE_KILL is an intentional
     * liveness-loss demonstration. Gating those on error rate would throw
     * away precisely the evidence they exist to produce (ledger note N1).
     * The fault-side question — are the errors CONCENTRATED around the fault
     * mark, or spread across the run like a broken client? — is a better
     * gate and a different one; it needs EventLog analysis and is not in
     * scope here.
     *
     * <p>A missing `error_rate` FAILs rather than passes: an unmeasured
     * error rate is not a zero one. That is the §4 meta-rule applied to a
     * manifest field, and the same absent-is-not-zero discipline the writer
     * follows for the fault fields.
     */
    private static GateResult gateBaselineErrorRate(JsonNode m) {
        String scenario = m.path("scenario").asText("");
        if (!"baseline".equals(scenario)) {
            return GateResult.skip("baseline_error_rate",
                    "'" + scenario + "' is a fault scenario — errors are the expected"
                            + " observation, not a defect (D15/F52)");
        }
        JsonNode er = m.get("error_rate");
        if (er == null || er.isNull() || !er.isNumber()) {
            return GateResult.fail("baseline_error_rate",
                    "manifest carries no numeric error_rate — an unmeasured error rate is"
                            + " not a zero one (§4 meta-rule)");
        }
        double rate = er.asDouble();
        return rate < BASELINE_ERROR_RATE_MAX
                ? GateResult.pass("baseline_error_rate",
                        String.format("%.2f%% of ops failed (< %.0f%%)",
                                rate * 100, BASELINE_ERROR_RATE_MAX * 100))
                : GateResult.fail("baseline_error_rate",
                        String.format("%.2f%% of ops failed on a BASELINE run (>= %.0f%%) —"
                                        + " this is not a steady-state measurement",
                                rate * 100, BASELINE_ERROR_RATE_MAX * 100));
    }

    /** Gate 5 (§4.5): warmup-tail vs measurement-head throughput must agree
     *  within the (provisional) threshold — a run still ramping when
     *  measurement began is not at steady state. */
    private static GateResult gateConvergence(Path dir, JsonNode m, String system) throws IOException {
        if (hasNoThroughputSeries(system)) {
            return GateResult.skip("convergence",
                    "hotstuff has no per-second series — the analyzer's warmup window"
                            + " (NEXT-4b) is the equivalent discard");
        }
        int warmup = m.path("warmup_secs").asInt(0);
        int duration = m.path("duration_secs").asInt(Integer.MAX_VALUE);
        if (warmup < 1) {
            return GateResult.skip("convergence", "no warmup window to converge against");
        }
        // Last up-to-60 s of warmup vs first up-to-60 s of measurement.
        double tail = mean(throughputWindow(dir, Math.max(0, warmup - 60), warmup));
        double head = mean(throughputWindow(dir, warmup, Math.min(duration, warmup + 60)));
        if (tail <= 0) {
            return GateResult.fail("convergence",
                    "warmup-tail throughput is zero — the system had not started committing");
        }
        double rel = Math.abs(head - tail) / tail;
        return rel <= CONVERGENCE_MAX_REL_DIFF
                ? GateResult.pass("convergence",
                        String.format("tail %.1f vs head %.1f ops/s (%.1f%% diff)", tail, head, rel * 100))
                : GateResult.fail("convergence",
                        String.format("tail %.1f vs head %.1f ops/s (%.1f%% > %.0f%%) — still ramping",
                                tail, head, rel * 100, CONVERGENCE_MAX_REL_DIFF * 100));
    }

    // ---- gates that need metrics/ (SKIP until M5.4; FAIL on empty series) ----

    private static GateResult gateLoadgenCpu(Path dir, boolean hasMetrics) throws IOException {
        if (!hasMetrics) return GateResult.skip("loadgen_cpu", "no metrics/ dir (PrometheusExporter M5.4 not run)");
        List<double[]> s = metricValues(dir, "loadgen_cpu");
        if (s.isEmpty()) return GateResult.fail("loadgen_cpu",
                "metrics/ present but loadgen_cpu series empty — broken retrieval (§4 meta-rule)");
        double max = s.stream().mapToDouble(v -> v[1]).max().orElse(0);
        return max < LOADGEN_CPU_MAX
                ? GateResult.pass("loadgen_cpu", String.format("peak %.1f%% < %.0f%%", max * 100, LOADGEN_CPU_MAX * 100))
                : GateResult.fail("loadgen_cpu",
                        String.format("peak %.1f%% ≥ %.0f%% — the instrument was the bottleneck",
                                max * 100, LOADGEN_CPU_MAX * 100));
    }

    private static GateResult gateSteal(String gate, String metric, Path dir, boolean hasMetrics)
            throws IOException {
        if (!hasMetrics) return GateResult.skip(gate, "no metrics/ dir (M5.4 not run)");
        List<double[]> s = metricValues(dir, metric);
        if (s.isEmpty()) return GateResult.fail(gate,
                "metrics/ present but " + metric + " series empty — broken retrieval (§4 meta-rule)");
        double max = s.stream().mapToDouble(v -> v[1]).max().orElse(0);
        return max < STEAL_MAX
                ? GateResult.pass(gate, String.format("peak steal %.2f%% < %.0f%%", max * 100, STEAL_MAX * 100))
                : GateResult.fail(gate,
                        String.format("peak steal %.2f%% ≥ %.0f%% — CPU steal (dedicated vCPU violated)",
                                max * 100, STEAL_MAX * 100));
    }

    private static GateResult gateClock(Path dir, boolean hasMetrics) throws IOException {
        if (!hasMetrics) return GateResult.skip("clock_discipline", "no metrics/ dir (M5.4 not run)");
        List<double[]> s = metricValues(dir, "clock_offset");
        if (s.isEmpty()) return GateResult.fail("clock_discipline",
                "metrics/ present but clock_offset series empty — broken retrieval (§4 meta-rule)");
        double max = s.stream().mapToDouble(v -> Math.abs(v[1])).max().orElse(0);
        return max < CLOCK_OFFSET_MAX_S
                ? GateResult.pass("clock_discipline", String.format("max |offset| %.1f ms < 5 ms", max * 1000))
                : GateResult.fail("clock_discipline",
                        String.format("max |offset| %.1f ms ≥ 5 ms — cross-node alignment suspect", max * 1000));
    }

    /**
     * Gate 3 (§4.3): for a fault run, a corroborating series must move
     * within ±60 s of the fault mark. The corroboration source is
     * PER-SYSTEM (F41) — the same window rule, different witness:
     *  - etcd: etcd_leader_chg counter increments (a real re-election);
     *  - tendermint: cmt_rounds increases (a height needed extra rounds —
     *    a healthy chain sits at round 0);
     *  - kraft/kafka_zk: kafka_urp rises above zero (replication degraded —
     *    the broker-fault signature the F6 dashboards read);
     *  - paxos/epaxos/hotstuff: NO server-side metric exists (documented
     *    §2/§7 limitation; the preregistered F26 wedge changes no leader BY
     *    DESIGN) → honest SKIP naming the future source (docker-events,
     *    P4.5) — an evaluated FAIL would misdiagnose the instrumentation
     *    gap as a fault-targeting bug.
     * node_up (node_exporter liveness) is kept as a generic extra witness,
     * but it only moves for VM-level faults — a `docker kill` of the SUT
     * container never silences the host's node_exporter.
     * Baseline runs: N/A. A fault run with a present-but-unmoved witness is
     * the run analyse.py must reclassify, never average in.
     */
    private static GateResult gateFaultGroundTruth(Path dir, JsonNode m, String system,
                                                   boolean hasMetrics) throws IOException {
        JsonNode mark = m.get("fault_injected_at_ms");
        if (mark == null || mark.isNull()) {
            // F50: an absent mark means one of two OPPOSITE things, and only
            // the scenario tells them apart — a baseline run has nothing to
            // corroborate, while a FAULT run with no mark is one whose
            // injection never fired (it threw, or stalled past the runner's
            // join). Judging by the mark alone reported the second as
            // "baseline run — N/A" and waved it through as valid. Anything
            // not explicitly baseline fails closed here.
            String scenario = m.path("scenario").asText("");
            if ("baseline".equals(scenario)) {
                return GateResult.skip("fault_ground_truth",
                        "baseline run (no fault injected) — N/A");
            }
            return GateResult.fail("fault_ground_truth",
                    "'" + scenario + "' run carries NO fault mark — the injection never fired"
                            + " (or stalled); the run is void as a fault cell, not a baseline");
        }
        String witness = switch (system) {
            case "etcd" -> "etcd_leader_chg";
            case "tendermint" -> "cmt_rounds";
            case "kraft", "kafka_zk" -> "kafka_urp";
            case "paxos", "epaxos", "hotstuff" -> null;
            default -> "";
        };
        if (witness == null) {
            return GateResult.skip("fault_ground_truth",
                    system + " exposes no server-side metrics (documented limitation);"
                            + " kill corroboration awaits the docker-events audit (P4.5)");
        }
        if (witness.isEmpty()) {
            return GateResult.fail("fault_ground_truth",
                    "unknown system '" + system + "' — no corroboration source defined");
        }
        if (!hasMetrics) return GateResult.skip("fault_ground_truth", "no metrics/ dir (M5.4 not run)");
        // The mark is ms since RUN START; metric t_unix is epoch seconds
        // (runbook §5). Align them via the manifest's started_at.
        long faultEpochSec;
        try {
            faultEpochSec = java.time.Instant.parse(m.path("started_at").asText())
                    .plusMillis(mark.asLong()).getEpochSecond();
        } catch (Exception e) {
            return GateResult.fail("fault_ground_truth",
                    "cannot align fault mark: unreadable started_at (" + e.getMessage() + ")");
        }
        boolean witnessMoved = witness.equals("kafka_urp")
                ? seriesExceedsZeroInWindow(dir, witness, faultEpochSec)
                : counterIncreasesInWindow(dir, witness, faultEpochSec);
        boolean nodeDropped = seriesHitsZeroInWindow(dir, "node_up", faultEpochSec);
        if (witnessMoved || nodeDropped) {
            return GateResult.pass("fault_ground_truth",
                    "corroborated (" + (witnessMoved ? witness + " moved" : "")
                            + (witnessMoved && nodeDropped ? " + " : "")
                            + (nodeDropped ? "node_up drop" : "") + " within ±60 s)");
        }
        // If neither witness series is even present, that's the meta-rule
        // empty-series FAIL; if present but unmoved, it's a real reclassify.
        boolean anyPresent = !metricValues(dir, witness).isEmpty()
                || !metricValues(dir, "node_up").isEmpty();
        return GateResult.fail("fault_ground_truth", anyPresent
                ? "no " + witness + " movement and no node_up drop within ±60 s of the mark — "
                        + "reclassify (the fault may not have hit the leader)"
                : "neither " + witness + " nor node_up present in metrics/ — broken retrieval"
                        + " (§4 meta-rule)");
    }

    // ---- IO helpers ----

    /** throughput.csv rows with warmup ≤ t < end, as ops values. */
    private static double[] throughputWindow(Path dir, int fromSec, int toSec) throws IOException {
        Path f = dir.resolve("throughput.csv");
        if (!Files.exists(f)) return new double[0];
        List<Double> vals = new ArrayList<>();
        for (String line : Files.readAllLines(f)) {
            line = line.strip();
            if (line.isEmpty()) continue;
            int comma = line.indexOf(',');
            if (comma < 0) continue;
            int t = Integer.parseInt(line.substring(0, comma).strip());
            if (t >= fromSec && t < toSec) {
                vals.add(Double.parseDouble(line.substring(comma + 1).strip()));
            }
        }
        return vals.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** metrics/&lt;name&gt;.csv → list of (t_unix, value); [] if file absent/empty.
     *  Header located; value = last column, t_unix = first.
     *  Public so the PrometheusExporter's own test can assert that what the
     *  exporter WRITES is what this checker READS — pinning the two halves
     *  of that contract separately is exactly how they drift apart. */
    public static List<double[]> metricValues(Path dir, String name) throws IOException {
        Path f = dir.resolve("metrics").resolve(name + ".csv");
        if (!Files.exists(f)) return List.of();
        List<String> lines = Files.readAllLines(f);
        List<double[]> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) { // skip header
            String line = lines.get(i).strip();
            if (line.isEmpty()) continue;
            String[] cols = line.split(",");
            if (cols.length < 2) continue;
            try {
                double t = Double.parseDouble(cols[0].strip());
                double v = Double.parseDouble(cols[cols.length - 1].strip());
                out.add(new double[]{t, v});
            } catch (NumberFormatException ignore) {
                // a non-numeric row (stray label) is not a sample; skip it.
            }
        }
        return out;
    }

    private static boolean counterIncreasesInWindow(Path dir, String name, long faultEpochSec)
            throws IOException {
        List<double[]> s = inWindow(metricValues(dir, name), faultEpochSec);
        if (s.size() < 2) return false;
        double first = s.get(0)[1], last = s.get(s.size() - 1)[1];
        return last > first;
    }

    private static boolean seriesHitsZeroInWindow(Path dir, String name, long faultEpochSec)
            throws IOException {
        return inWindow(metricValues(dir, name), faultEpochSec).stream().anyMatch(v -> v[1] == 0.0);
    }

    /** Gauge witness (kafka_urp): any sample above zero inside the window —
     *  under-replicated partitions exist only while replication is degraded. */
    private static boolean seriesExceedsZeroInWindow(Path dir, String name, long faultEpochSec)
            throws IOException {
        return inWindow(metricValues(dir, name), faultEpochSec).stream().anyMatch(v -> v[1] > 0.0);
    }

    /** Samples whose epoch-second timestamp falls within ±60 s of the fault. */
    private static List<double[]> inWindow(List<double[]> series, long faultEpochSec) {
        double lo = faultEpochSec - FAULT_WINDOW_MS / 1000.0;
        double hi = faultEpochSec + FAULT_WINDOW_MS / 1000.0;
        List<double[]> out = new ArrayList<>();
        for (double[] v : series) if (v[0] >= lo && v[0] <= hi) out.add(v);
        return out;
    }

    private static double mean(double[] xs) {
        if (xs.length == 0) return 0;
        double s = 0;
        for (double x : xs) s += x;
        return s / xs.length;
    }

    /** Minimal JSON string escaping — the report is assembled by hand like
     *  the manifest, and a quote in a gate detail would otherwise emit
     *  broken JSON that every downstream reader then fails on (F21's class). */
    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    private static void writeReport(Path dir, Report r) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"valid\": ").append(r.valid()).append(",\n");
        sb.append("  \"passed\": ").append(r.count(State.PASS)).append(",\n");
        sb.append("  \"failed\": ").append(r.count(State.FAIL)).append(",\n");
        sb.append("  \"skipped\": ").append(r.count(State.SKIP)).append(",\n");
        sb.append("  \"gates\": [\n");
        for (int i = 0; i < r.gates().size(); i++) {
            GateResult g = r.gates().get(i);
            GateSpec spec = GATE_SPECS.get(g.gate());
            sb.append("    {\"gate\": \"").append(g.gate())
              .append("\", \"state\": \"").append(g.state())
              .append("\", \"detail\": \"").append(json(g.detail()))
              .append("\", \"threshold\": ")
              .append(spec == null || spec.threshold() == null
                      ? "null" : "\"" + json(spec.threshold()) + "\"")
              .append(", \"reference\": ")
              .append(spec == null ? "null" : "\"" + json(spec.reference()) + "\"")
              // Only a FAIL needs triage guidance; attaching it to every PASS
              // would bury the one case a reader must act on.
              .append(g.state() == State.FAIL && spec != null
                      ? ", \"check_before_believing\": \"" + json(spec.falsePositives()) + "\""
                      : "")
              .append("}").append(i < r.gates().size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n}\n");
        Files.writeString(dir.resolve("validity.json"), sb.toString());
    }
}
