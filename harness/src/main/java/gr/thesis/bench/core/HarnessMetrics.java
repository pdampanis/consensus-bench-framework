package gr.thesis.bench.core;

import com.sun.net.httpserver.HttpServer;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * M5.3 — the harness's own metrics on :9400, which is the last input the
 * {@code window_headroom} validity gate was waiting for.
 *
 * <p>Why the instrument must measure ITSELF: every other gate watches the
 * systems under test, but the classic way a benchmark lies is that the
 * CLIENT was the ceiling. Little's Law makes that concrete — a run whose
 * in-flight window sits pinned at its limit is reporting
 * {@code window/latency}, which is a fact about the harness, not about
 * consensus. That exact confusion has already cost this project twice
 * (P2.2c's Kafka parity and P2.3's CometBFT floor were both window-bound),
 * and both times it was diagnosed by hand afterwards. This makes it a gate.
 *
 * <p>The hot path pays NOTHING: the gauge is a poll of a value the engine
 * already maintains, read by the scrape thread, never written per operation.
 * That is the same discipline as the {@code -v} reporter, which reads the
 * engine's atomics rather than instrumenting the loop.
 */
public final class HarnessMetrics implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HarnessMetrics.class);
    /** prometheus.yml's `harness` job scrapes this port on the loadgen. */
    public static final int PORT = 9400;

    private final PrometheusMeterRegistry registry;
    private final HttpServer server;

    private HarnessMetrics(PrometheusMeterRegistry registry, HttpServer server) {
        this.registry = registry;
        this.server = server;
    }

    /**
     * Start the endpoint and register the in-flight gauge.
     *
     * @param engine    polled for occupancy and counters, never pushed to
     * @param maxWindow the configured ceiling, exported alongside so the
     *                  analysis computes OCCUPANCY without having to join
     *                  against the manifest
     */
    public static HarnessMetrics start(WorkloadEngine engine, int maxWindow) throws IOException {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        // Exactly the three names observability/export_queries.txt already
        // declares (harness_inflight / harness_rate / harness_errors). The
        // queries were written first, as the spec — this closes the loop the
        // F40 contract test guards.
        io.micrometer.core.instrument.Gauge
                .builder("bench_inflight_current", engine, WorkloadEngine::currentInFlight)
                .description("operations in flight right now")
                .register(registry);
        io.micrometer.core.instrument.Gauge
                .builder("bench_inflight_max", () -> maxWindow)
                .description("configured in-flight window ceiling")
                .register(registry);
        io.micrometer.core.instrument.FunctionCounter
                .builder("bench_ops_submitted", engine, e -> (double) e.submittedCount())
                .description("operations issued")
                .register(registry);
        io.micrometer.core.instrument.FunctionCounter
                .builder("bench_ops_failed", engine, e -> (double) e.errorCount())
                .description("operations that failed")
                .register(registry);

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        log.info("harness metrics on :{}/metrics (window ceiling {})", PORT, maxWindow);
        return new HarnessMetrics(registry, server);
    }

    /** Visible for tests: the exposition text a scrape would return. */
    public String scrape() {
        return registry.scrape();
    }

    @Override
    public void close() {
        // Zero delay: the run is over, and a lingering socket would collide
        // with the NEXT cell's registry on the same port.
        server.stop(0);
        registry.close();
    }
}
