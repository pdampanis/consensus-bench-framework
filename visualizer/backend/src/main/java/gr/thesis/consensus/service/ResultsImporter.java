package gr.thesis.consensus.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.thesis.consensus.domain.BenchmarkRun;
import gr.thesis.consensus.domain.BenchmarkRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.stream.Stream;

/**
 * At application startup, walks the mounted results directory and imports
 * every run into the database. Idempotent: re-importing the same run
 * updates the existing row rather than duplicating it.
 *
 * The directory layout mirrors the run_experiment.sh output:
 *   results/<system>/<scenario>/<runId>/manifest.json
 *   results/<system>/<scenario>/<runId>/ycsb-run.txt
 *   results/<system>/<scenario>/<runId>/consistency.txt
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResultsImporter implements ApplicationRunner {

    @Value("${app.results-dir:/data/results}")
    private String resultsDir;

    private final BenchmarkRunRepository repo;
    private final ObjectMapper om = new ObjectMapper();

    @Override
    public void run(ApplicationArguments args) {
        Path root = Paths.get(resultsDir);
        if (!Files.isDirectory(root)) {
            log.warn("Results directory {} does not exist yet; skipping import.", root);
            return;
        }

        try (Stream<Path> walk = Files.walk(root, 4)) {
            walk.filter(p -> p.getFileName().toString().equals("manifest.json"))
                .forEach(this::importOne);
        } catch (IOException e) {
            log.error("Failed to walk results dir", e);
        }
    }

    private void importOne(Path manifestPath) {
        try {
            JsonNode node = om.readTree(manifestPath.toFile());
            String system = node.path("system").asText();
            String scenario = node.path("scenario").asText();
            String runId = node.path("run_id").asText();

            BenchmarkRun run = repo.findBySystemAndScenarioOrderByStartedAtAsc(system, scenario)
                .stream()
                .filter(r -> runId.equals(r.getRunId()))
                .findFirst()
                .orElseGet(BenchmarkRun::new);

            run.setSystem(system);
            run.setScenario(scenario);
            run.setRunId(runId);
            run.setStartedAt(parseTs(node.path("started_at").asText()));
            // v2 manifest emits warmup_secs + measure_secs separately; total
            // duration is their sum. Falls back to 480 (our default 8 min).
            int warmup = node.path("warmup_secs").asInt(180);
            int measure = node.path("measure_secs").asInt(300);
            run.setDurationSecs(warmup + measure);
            // v2 campaign uses one workload; v3 adds scalability across sizes.
            run.setWorkload("native-perf-tool");
            run.setClusterSize(node.path("cluster_size").asInt(3));
            run.setEnvironmentJson(node.toString());

            // New v2 format: read throughput.csv and latency.csv. The
            // perf tool (kafka-producer-perf-test.sh or etcd benchmark)
            // ran natively and parse_results.sh normalized its output
            // into these two files.
            Path tCsv = manifestPath.getParent().resolve("throughput.csv");
            if (Files.exists(tCsv)) parseThroughputCsv(tCsv, run);

            Path lCsv = manifestPath.getParent().resolve("latency.csv");
            if (Files.exists(lCsv)) parseLatencyCsv(lCsv, run);

            // No consistency check in v2 (we don't run Jepsen/Elle).
            run.setConsistencyResult("NOT_CHECKED");

            repo.save(run);
        } catch (Exception e) {
            log.warn("Failed to import {}: {}", manifestPath, e.getMessage());
        }
    }

    /**
     * Throughput CSV has two columns: t_off (seconds into the run) and
     * ops (ops/sec during that second). We compute the mean across the
     * measurement window (t_off >= 180s, i.e. after the 3-minute warmup)
     * and store it as the run's headline throughput.
     */
    private void parseThroughputCsv(Path path, BenchmarkRun run) throws IOException {
        double sum = 0;
        int count = 0;
        double maxT = 0;
        // First pass: find max t_off so we can decide whether to skip warmup
        for (String line : Files.readAllLines(path)) {
            String[] parts = line.split(",");
            if (parts.length != 2) continue;
            try {
                double t = Double.parseDouble(parts[0].trim());
                if (t > maxT) maxT = t;
            } catch (NumberFormatException ignored) {}
        }
        boolean skipWarmup = maxT > 180;
        for (String line : Files.readAllLines(path)) {
            String[] parts = line.split(",");
            if (parts.length != 2) continue;
            try {
                double t = Double.parseDouble(parts[0].trim());
                double ops = Double.parseDouble(parts[1].trim());
                if (skipWarmup && t < 180) continue;
                sum += ops;
                count++;
            } catch (NumberFormatException ignored) {}
        }
        if (count > 0) run.setThroughputOps(sum / count);
    }

    /**
     * Latency CSV has rows like "metric,value_us" where metric is one
     * of avg, p50, p95, p99, p99_9, max. Map each to the BenchmarkRun
     * field of the same name.
     */
    private void parseLatencyCsv(Path path, BenchmarkRun run) throws IOException {
        boolean firstLine = true;
        for (String line : Files.readAllLines(path)) {
            if (firstLine) { firstLine = false; continue; } // skip header
            String[] parts = line.split(",");
            if (parts.length != 2) continue;
            String metric = parts[0].trim();
            double value;
            try {
                value = Double.parseDouble(parts[1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            switch (metric) {
                case "avg"   -> run.setWriteAvgUs(value);
                case "p95"   -> run.setWriteP95Us(value);
                case "p99"   -> run.setWriteP99Us(value);
                case "p99_9" -> run.setWriteP999Us(value);
                default -> {}
            }
        }
    }

    /**
     * Parses the ISO-8601 timestamp emitted by `date -Iseconds` which has
     * the form "2026-04-17T12:30:45+00:00". Instant.parse() rejects that
     * form (it requires a Z suffix), so we parse it as OffsetDateTime
     * first and convert. Falls back to now() only if that also fails.
     */
    private static Instant parseTs(String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (Exception e1) {
            try {
                return Instant.parse(iso);
            } catch (Exception e2) {
                return Instant.now();
            }
        }
    }
}
