package gr.thesis.bench.results;

import gr.thesis.bench.validity.ValidityChecker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5.4 (S3.1) — the exporter that makes a run self-contained. Two layers,
 * deliberately: canned JSON pins the CSV CONTRACT (fast, deterministic), and
 * one real Prometheus proves the HTTP path actually works end to end.
 *
 * The second layer exists because this is the last piece the P3.4 canary
 * depends on, and the canary is the project's designated proof of the LIVE
 * scrape path — discovering there that the URL encoding or the JSON shape was
 * wrong would cost a whole provisioning cycle. F28 is the standing lesson:
 * a recorded/canned test can be green while the real thing is broken.
 */
class PrometheusExporterTest {

    /** The repo's real query set — parsing it here means a malformed edit to
     *  export_queries.txt breaks the BUILD rather than the campaign. */
    private static final Path QUERIES = Path.of("../observability/export_queries.txt");

    private static String matrixJson() {
        return """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"instance":"10.0.0.11:9100","role":"consensus"},
                   "values":[[1723600000,"0.12"],[1723600005,"0.15"]]},
                  {"metric":{"instance":"10.0.0.12:9100","role":"consensus"},
                   "values":[[1723600000,"0.20"]]}
                ]}}""";
    }

    @Test
    void everyQueryInTheRepoFileParses() throws Exception {
        List<PrometheusExporter.Query> qs = PrometheusExporter.parseQueries(QUERIES);
        assertEquals(23, qs.size(),
                "the query set is pinned; if this changes, ValidityChecker's"
                        + " CONSULTED_METRICS contract test is the other half to check");
        assertTrue(qs.stream().anyMatch(q -> q.name().equals("clock_offset")),
                "F40's addition must still be there");
        assertTrue(qs.stream().allMatch(q -> !q.promql().isBlank()));
    }

    @Test
    void aMalformedQueryLineFailsClosedRatherThanBeingSkipped(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("q.txt");
        Files.writeString(f, "# fine\ngood | up\nthis line has no bar\n");
        var e = assertThrows(IllegalArgumentException.class,
                () -> PrometheusExporter.parseQueries(f));
        // A silently dropped query is a metric that never appears, and the
        // gate needing it then fails with a misleading "broken retrieval"
        // diagnosis — the F40 class.
        assertTrue(e.getMessage().contains("q.txt:3"), e.getMessage());
    }

    @Test
    void csvCarriesTheContractValidityCheckerReads() throws Exception {
        String csv = PrometheusExporter.toCsv(matrixJson());
        List<String> lines = csv.lines().toList();
        assertEquals("t_unix,t_iso,series,value", lines.get(0));
        assertEquals(4, lines.size(), csv);          // header + 3 samples
        // First column t_unix, LAST column value — the reader's rule.
        assertTrue(lines.get(1).startsWith("1723600000,"));
        assertTrue(lines.get(1).endsWith(",0.12"));
    }

    @Test
    void aPrometheusErrorIsNotSilentlyAnEmptySeries() {
        assertThrows(java.io.IOException.class, () -> PrometheusExporter.toCsv(
                "{\"status\":\"error\",\"error\":\"bad query\"}"));
    }

    @Test
    void anEmptyResultStillWritesItsFileSoTheGateFailsClosed(@TempDir Path run) throws Exception {
        var qs = List.of(new PrometheusExporter.Query("loadgen_cpu", "up"));
        int empty = PrometheusExporter.export(run, qs, Instant.EPOCH, Instant.EPOCH.plusSeconds(10),
                (q, s, e) -> "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}");

        assertEquals(1, empty);
        Path csv = run.resolve("metrics/loadgen_cpu.csv");
        assertTrue(Files.exists(csv),
                "an empty series must still produce a file: metrics/ present + series empty"
                        + " is the §4 meta-rule FAIL, while a MISSING file reads as"
                        + " 'the exporter never ran' and would SKIP instead");
        assertEquals("t_unix,t_iso,series,value\n", Files.readString(csv));
    }

    @Test
    void aFailingQueryDoesNotCostTheOthers(@TempDir Path run) throws Exception {
        var qs = List.of(new PrometheusExporter.Query("a", "up"),
                new PrometheusExporter.Query("b", "up"));
        PrometheusExporter.export(run, qs, Instant.EPOCH, Instant.EPOCH.plusSeconds(10),
                (q, s, e) -> {
                    if (q.equals("up")) {
                        throw new IllegalStateException("boom");
                    }
                    return matrixJson();
                });
        assertTrue(Files.exists(run.resolve("metrics/a.csv")));
        assertTrue(Files.exists(run.resolve("metrics/b.csv")));
    }

    @Test
    void theWindowIsPaddedByThreeScrapeIntervals(@TempDir Path run) throws Exception {
        var seen = new Instant[2];
        PrometheusExporter.export(run, List.of(new PrometheusExporter.Query("x", "up")),
                Instant.ofEpochSecond(1000), Instant.ofEpochSecond(2000),
                (q, s, e) -> { seen[0] = s; seen[1] = e; return matrixJson(); });
        // Runbook §5.2: ±15 s so boundary samples are not lost to scrape
        // phase alignment.
        assertEquals(Instant.ofEpochSecond(985), seen[0]);
        assertEquals(Instant.ofEpochSecond(2015), seen[1]);
    }

    // ---- the layer canned JSON cannot give us: a REAL Prometheus ----

    @Test
    void exportsAgainstARealPrometheusAndTheCheckerCanReadIt(@TempDir Path run) throws Exception {
        // Same pinned version observability/docker-compose.yml deploys, so
        // this proves the API shape of the Prometheus the campaign will
        // actually query. The default config scrapes Prometheus itself, so
        // `up` has real samples with no extra wiring.
        try (var prom = new GenericContainer<>("prom/prometheus:v2.53.0")
                .withExposedPorts(9090)
                .waitingFor(Wait.forHttp("/-/ready").forPort(9090))) {
            prom.start();
            String base = "http://" + prom.getHost() + ":" + prom.getMappedPort(9090);

            // DEADLINE-POLLED, not a fixed sleep (F45's rule, learned again
            // here): the first version slept 12 s, passed in isolation and
            // failed inside the full suite, because how fast a container
            // scrapes is a function of the machine's spare capacity — the
            // F27/F68 class of gate that reds on unrelated load. Polling
            // asserts the CONDITION and lets a slow machine take longer,
            // while a Prometheus that never scrapes still fails.
            var qs = List.of(new PrometheusExporter.Query("node_up", "up"));
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
            int empty = 1;
            while (System.nanoTime() < deadline) {
                Instant end = Instant.now();
                empty = PrometheusExporter.export(run, qs, end.minusSeconds(120), end,
                        PrometheusExporter.http(base));
                if (empty == 0) {
                    break;
                }
                Thread.sleep(2_000);
            }

            assertEquals(0, empty,
                    "a self-scraping Prometheus must yield `up` samples within 90 s");
            String csv = Files.readString(run.resolve("metrics/node_up.csv"));
            assertTrue(csv.lines().count() > 1, csv);

            // The point of the whole increment: the ValidityChecker must be
            // able to READ what the exporter WROTE. Pinning the two halves
            // separately would let them drift; this asserts the seam.
            var samples = ValidityChecker.metricValues(run, "node_up");
            assertFalse(samples.isEmpty(), "checker must parse the exporter's own output");
            assertEquals(1.0, samples.get(0)[1], 1e-9, "`up` is 1 for a live target");
        }
    }
}
