package gr.thesis.bench.results;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M5.4 — archives each run's Prometheus window into {@code metrics/*.csv}
 * beside its CSVs, which is what makes a run SELF-CONTAINED: every figure and
 * every validity gate must reproduce from the collected tree alone, months
 * later, with the cluster destroyed (CAMPAIGN_RUNBOOK §5, and Hoefler &amp;
 * Belli's "state everything needed to reproduce" as the acceptance standard).
 *
 * <p>Mechanics, exactly as the runbook pins them: time bounds come from the
 * run's own manifest, the window is padded by ±15 s (three scrape intervals,
 * so boundary samples are not lost to scrape-phase alignment), and each
 * {@code <name> | <promql>} line of {@code observability/export_queries.txt}
 * becomes one {@code query_range} at {@code step=5s} written to
 * {@code metrics/<name>.csv}.
 *
 * <p>The output shape is the contract {@link gr.thesis.bench.validity.ValidityChecker}
 * already declared and is deliberately NOT re-invented here: header row,
 * first column {@code t_unix}, last column {@code value}, labels in between.
 *
 * <p><b>An empty result still WRITES its file</b> (header only). That looks
 * like a detail and is the opposite: the §4 meta-rule says an empty series
 * FAILS the gate that needs it, because empty means the retrieval path is
 * broken — a wrong label, a dead target — which is exactly when a gate must
 * not wave a run through. Writing the header proves the query RAN and
 * returned nothing, and separates that from "the exporter never got here",
 * which is the one thing a missing file could otherwise mean.
 */
public final class PrometheusExporter {

    private static final Logger log = LoggerFactory.getLogger(PrometheusExporter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Runbook §5.2 — three scrape intervals. */
    static final Duration PADDING = Duration.ofSeconds(15);
    /** Runbook §5.3 — matches the 5 s scrape interval in prometheus.yml. */
    static final String STEP_SECONDS = "5";

    /** The HTTP seam, so the CSV contract is testable without a live
     *  Prometheus — the same pattern {@code SshExecutor} uses for the remote
     *  layer, and for the same reason: the thing worth pinning is what we
     *  WRITE, not what the network did. */
    @FunctionalInterface
    public interface RangeQuery {
        /** @return the raw {@code /api/v1/query_range} JSON body. */
        String run(String promql, Instant start, Instant end) throws Exception;
    }

    /** One {@code <name> | <promql>} line of export_queries.txt. */
    public record Query(String name, String promql) { }

    private PrometheusExporter() { }

    /**
     * Parse {@code export_queries.txt}. Comments and blank lines are skipped;
     * anything else MUST be {@code name | promql} — a malformed line fails
     * closed rather than being dropped, because a silently skipped query is
     * a metric that simply never appears, and the gate that needed it would
     * then fail with a misleading "broken retrieval" diagnosis (the F40
     * class, where a gate consulted a name no query produced).
     */
    public static List<Query> parseQueries(Path file) throws IOException {
        List<Query> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int bar = line.indexOf('|');
            if (bar <= 0 || bar == line.length() - 1) {
                throw new IllegalArgumentException(file + ":" + (i + 1)
                        + " is neither a comment nor `name | promql`: " + line);
            }
            String name = line.substring(0, bar).strip();
            String promql = line.substring(bar + 1).strip();
            if (name.isEmpty() || promql.isEmpty()) {
                throw new IllegalArgumentException(file + ":" + (i + 1)
                        + " has an empty name or query: " + line);
            }
            out.add(new Query(name, promql));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(file + " defines no queries");
        }
        return out;
    }

    /**
     * Export every query for one run window into {@code runDir/metrics/}.
     *
     * @return how many queries returned NO samples. Non-zero is not an error
     *         here — it is the ValidityChecker's business to decide which
     *         empty series matter — but it is logged, because "all 23 empty"
     *         means the obs stack is unreachable and every later gate will
     *         fail for a reason that has nothing to do with the run.
     */
    public static int export(Path runDir, List<Query> queries, Instant started, Instant ended,
                             RangeQuery query) throws IOException {
        Path metrics = runDir.resolve("metrics");
        Files.createDirectories(metrics);
        Instant from = started.minus(PADDING);
        Instant to = ended.plus(PADDING);
        int empty = 0;

        for (Query q : queries) {
            String csv;
            try {
                csv = toCsv(query.run(q.promql(), from, to));
            } catch (Exception e) {
                // One unreachable query must not cost the other 22. The file
                // is still written (header only) so the gate that needs it
                // sees an EMPTY series and fails closed, rather than seeing
                // nothing and skipping.
                log.warn("query {} failed — writing an empty series so the gate that needs"
                        + " it fails closed rather than skipping: {}", q.name(), e.toString());
                csv = header();
            }
            Files.writeString(metrics.resolve(q.name() + ".csv"), csv);
            if (csv.equals(header())) {
                empty++;
            }
        }
        if (empty == queries.size()) {
            log.error("ALL {} queries returned no samples for {} — the obs stack is very"
                    + " likely unreachable. Every metric gate will now fail for a reason"
                    + " that has nothing to do with this run.", queries.size(), runDir);
        } else if (empty > 0) {
            log.warn("{} of {} queries returned no samples for {}", empty, queries.size(), runDir);
        }
        return empty;
    }

    private static String header() {
        return "t_unix,t_iso,series,value\n";
    }

    /**
     * {@code query_range} JSON → the run's CSV contract. Prometheus returns a
     * matrix: a list of series, each with its label set and its samples as
     * {@code [unixSeconds, "value"]}. Labels are flattened into ONE
     * {@code series} column rather than spread across columns, because the
     * column set would otherwise differ per query and the reader's rule is
     * "first column is t_unix, last is value".
     */
    static String toCsv(String body) throws IOException {
        JsonNode root = JSON.readTree(body);
        String status = root.path("status").asText("");
        if (!"success".equals(status)) {
            throw new IOException("prometheus returned status=" + status
                    + " error=" + root.path("error").asText(""));
        }
        StringBuilder sb = new StringBuilder(header());
        for (JsonNode series : root.path("data").path("result")) {
            String labels = flattenLabels(series.path("metric"));
            for (JsonNode sample : series.path("values")) {
                if (!sample.isArray() || sample.size() < 2) {
                    continue;
                }
                String t = sample.get(0).asText();
                String v = sample.get(1).asText();
                long unix = (long) Double.parseDouble(t);
                sb.append(unix).append(',')
                  .append(Instant.ofEpochSecond(unix)).append(',')
                  .append(labels).append(',')
                  .append(v).append('\n');
            }
        }
        return sb.toString();
    }

    /** {@code k=v k=v} in a single CSV-safe cell, name first when present. */
    private static String flattenLabels(JsonNode metric) {
        Map<String, String> sorted = new LinkedHashMap<>();
        metric.fieldNames().forEachRemaining(f -> sorted.put(f, metric.path(f).asText()));
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(k).append('=').append(v.replace(',', ';'));
        });
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** The production seam: a real Prometheus over HTTP, bounded like every
     *  other network call the harness makes (the F18 rule — nothing the
     *  harness waits on may hang a run). */
    public static RangeQuery http(String baseUrl) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
        return (promql, start, end) -> {
            String url = baseUrl + "/api/v1/query_range"
                    + "?query=" + URLEncoder.encode(promql, StandardCharsets.UTF_8)
                    + "&start=" + start.getEpochSecond()
                    + "&end=" + end.getEpochSecond()
                    + "&step=" + STEP_SECONDS;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> res =
                    client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new UncheckedIOException(new IOException(
                        "prometheus HTTP " + res.statusCode() + " for " + promql));
            }
            return res.body();
        };
    }
}
