package gr.thesis.bench.results;

import gr.thesis.bench.core.Scenario;
import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.core.WorkloadEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Writes the EXACT output contract the existing pipeline consumes:
 *   <root>/<system>/<scenario>/size<N>/<runId>/throughput.csv   (t_off,ops_per_sec)
 *   <root>/<system>/<scenario>/size<N>/<runId>/latency.csv      (metric,value_us)
 *   <root>/<system>/<scenario>/size<N>/<runId>/manifest.json
 *
 * Rationale: analyse.py (Mann-Whitney U, bootstrap CIs) and the React
 * visualizer were reviewed across multiple sessions and are trusted; the
 * harness replaces how numbers are PRODUCED, never how they are analysed.
 *
 * The run identity includes clusterSize in the PATH - v6's scalability
 * cells were silently skipped because size-5 runs collided with size-3
 * paths and the idempotency check ate them. Here the collision cannot
 * be expressed.
 */
public final class CsvResultsWriter {

    public record RunIdentity(SystemUnderTest system, Scenario scenario,
                              int clusterSize, double conflictRatio, String runId) {
        public RunIdentity {
            // The ratio becomes a path segment (c<percent>). A non-whole
            // percent would render the same segment as its rounding neighbor
            // (0.025 and 0.02 both -> "c2") and silently MERGE two different
            // cells — the v6 path-collision class. Fail closed instead.
            double pct = conflictRatio * 100.0;
            if (!(pct >= 0.0 && pct <= 100.0) || Math.abs(pct - Math.round(pct)) > 1e-9) {
                throw new IllegalArgumentException(
                        "conflictRatio must be a whole percent in [0,1], got " + conflictRatio);
            }
        }

        /**
         * .../<system>/<scenario>/size<N>[/c<percent>]/<runId>
         * The c-segment appears only for conflict runs (c > 0): the five
         * non-Paxi systems never sweep conflict, so their tree (and the
         * committed M0 reference results) keeps its layout; a c>0 run can
         * never collide with a c=0 run because the extra segment differs.
         */
        public Path dir(Path root) {
            Path p = root.resolve(system.name().toLowerCase())
                         .resolve(scenario.name().toLowerCase())
                         .resolve("size" + clusterSize);
            if (conflictRatio > 0.0) {
                p = p.resolve("c" + Math.round(conflictRatio * 100.0));
            }
            return p.resolve(runId);
        }
    }

    /**
     * @param environment "local" (dev laptop — NEVER thesis data; analysis
     *                    filters it out) or "hetzner" (the real cluster).
     * @param imageRef    digest-pinned image the cluster ran, or null when
     *                    unknown (endpoint-run against a cluster we didn't
     *                    start) — an honest null, never a guessed value.
     */
    public void write(Path root, RunIdentity id, WorkloadEngine.Result r,
                      WorkloadEngine.Config cfg, String environment, String imageRef,
                      Instant started, Instant ended) throws IOException {
        final int durationSecs = cfg.durationSecs();
        final int warmupSecs = cfg.warmupSecs();
        Path dir = id.dir(root);
        Files.createDirectories(dir);

        // Every second of the configured run window is written, ZEROS
        // INCLUDED — a zero-commit second is stall evidence the fault
        // analysis needs, never noise to drop. Only the drain-buffer tail
        // (t >= duration, completions that landed after the load stopped)
        // is filtered to nonzero.
        StringBuilder tp = new StringBuilder();
        long[] ps = r.committedPerSecond();
        for (int t = 0; t < ps.length; t++) {
            if (t < durationSecs || ps[t] > 0) tp.append(t).append(',').append(ps[t]).append('\n');
        }
        Files.writeString(dir.resolve("throughput.csv"), tp.toString());

        var lat = r.latencies();
        String latencyCsv = "metric,value_us\n"
                + "avg,"   + Math.round(lat.meanMicros()) + "\n" // TRUE mean (P1.3)
                + "p50,"   + lat.percentileMicros(50.0) + "\n"
                + "p95,"   + lat.percentileMicros(95.0) + "\n"
                + "p99,"   + lat.percentileMicros(99.0) + "\n"
                + "p99_9," + lat.percentileMicros(99.9) + "\n"
                + "max,"   + lat.percentileMicros(100.0) + "\n";
        Files.writeString(dir.resolve("latency.csv"), latencyCsv);

        // Full post-warmup histogram in standard .hlog format (readable by
        // HdrHistogram tooling and analyse.py v2). This is what makes the
        // methodology's pooled-distribution analysis (§3: merge histograms,
        // never average percentiles) possible — latency.csv above is a
        // human-readable summary, latency.hlog is the analysis input.
        try (var hlogStream = new java.io.PrintStream(
                Files.newOutputStream(dir.resolve("latency.hlog")))) {
            var w = new org.HdrHistogram.HistogramLogWriter(hlogStream);
            w.outputComment("consensus-bench post-warmup latency, unit=microseconds");
            w.outputLogFormatVersion();
            w.outputStartTime(started.toEpochMilli());
            w.outputLegend();
            w.outputIntervalHistogram(
                    started.getEpochSecond() + warmupSecs,   // measurement start
                    ended.getEpochSecond(),                  // run end
                    lat.warmSnapshot());
        }

        long opsAfterWarmup = lat.countAfterWarmup();
        // error_rate is the whole-run failure fraction (committed + failed
        // attempts). A majority-failed run may not call itself "complete" —
        // finer validity judgment (client bottleneck, fault ground truth…)
        // belongs to the ValidityChecker (M5.5), but the manifest must not
        // overstate on its own.
        long committedTotal = 0;
        for (long c : r.committedPerSecond()) committedTotal += c;
        long attempts = committedTotal + r.errors();
        double errorRate = attempts == 0 ? 0.0 : r.errors() / (double) attempts;

        // Fault instrumentation (P1.4): explicit nulls for baseline runs —
        // an absent measurement is null, never zero.
        String faultAt = "null", failover = "null";
        boolean faultFired = r.events() != null && r.events().faultMarkMillis().isPresent();
        if (faultFired) {
            faultAt = Long.toString(r.events().faultMarkMillis().getAsLong());
            failover = r.events().failoverMillis().isPresent()
                    ? Long.toString(r.events().failoverMillis().getAsLong()) : "null";
        }

        // F50: a fault-scenario run that cannot evidence its fault is VOID as
        // that scenario, however clean the measurement looks. The mark is
        // stamped only after FaultInjector.apply() RETURNS, so "mark present"
        // <=> "the fault demonstrably fired"; its absence means the injection
        // threw, or stalled past the runner's join, and what was actually
        // measured is an undisturbed cluster wearing a leader_kill label.
        // Recording that here — rather than in the caller — is what makes the
        // campaign's resume check, the ValidityChecker and analyse.py all read
        // one truth, and keeps the trap disarmed for any future caller.
        boolean faultNeverFired = id.scenario() != Scenario.BASELINE && !faultFired;
        String status = (opsAfterWarmup > 0 && errorRate <= 0.5 && !faultNeverFired)
                ? "complete" : "failed";

        String manifest = """
                {
                  "system": "%s",
                  "scenario": "%s",
                  "cluster_size": %d,
                  "conflict_ratio": %s,
                  "run_id": "%s",
                  "environment": "%s",
                  "image": %s,
                  "harness_version": "%s",
                  "config_hash": "%s",
                  "started_at": "%s",
                  "ended_at": "%s",
                  "duration_secs": %d,
                  "warmup_secs": %d,
                  "rate_ops_s": %d,
                  "window": %d,
                  "value_size_bytes": %d,
                  "ops_after_warmup": %d,
                  "errors": %d,
                  "error_rate": %s,
                  "fault_injected_at_ms": %s,
                  "failover_ms": %s,
                  "status": "%s",
                  "harness": "consensus-bench-java"
                }
                """.formatted(id.system().name().toLowerCase(),
                              id.scenario().name().toLowerCase(),
                              id.clusterSize(),
                              String.format(java.util.Locale.ROOT, "%.2f", id.conflictRatio()),
                              id.runId(),
                              environment,
                              imageRef == null ? "null" : "\"" + imageRef + "\"",
                              harnessVersion(),
                              configHash(id, cfg, imageRef),
                              started, ended, durationSecs, warmupSecs,
                              cfg.targetRatePerSec(), cfg.maxInFlight(), cfg.valueSizeBytes(),
                              opsAfterWarmup, r.errors(),
                              String.format(java.util.Locale.ROOT, "%.4f", errorRate),
                              faultAt, failover,
                              status);
        Files.writeString(dir.resolve("manifest.json"), manifest);
    }

    /** Implementation-Version from the shaded jar's manifest; "dev" when
     *  running from classes (tests, IDE) — an honest tag, not a guess. */
    private static String harnessVersion() {
        String v = CsvResultsWriter.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    /**
     * 12 hex chars of SHA-256 over every input that defines the cell —
     * methodology §1's "configuration hash, making any cell individually
     * reproducible": two runs with the same hash ran the same experiment.
     * Canonical '|'-joined string, so any param change changes the hash.
     */
    private static String configHash(RunIdentity id, WorkloadEngine.Config cfg, String imageRef) {
        String canonical = String.join("|",
                id.system().name(), id.scenario().name(),
                Integer.toString(id.clusterSize()), Double.toString(id.conflictRatio()),
                Integer.toString(cfg.durationSecs()), Integer.toString(cfg.warmupSecs()),
                Long.toString(cfg.targetRatePerSec()), Integer.toString(cfg.maxInFlight()),
                Integer.toString(cfg.valueSizeBytes()),
                imageRef == null ? "unknown" : imageRef);
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) hex.append(String.format("%02x", d[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // JVM-guaranteed algorithm
        }
    }
}
