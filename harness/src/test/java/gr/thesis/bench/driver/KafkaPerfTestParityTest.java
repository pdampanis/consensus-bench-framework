package gr.thesis.bench.driver;

import gr.thesis.bench.core.LatencyRecorder;
import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.core.WorkloadEngine;
import gr.thesis.bench.topology.LocalDockerProvider;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2.2c — the G1 flaw-B regression gate, run locally: the harness's Kafka
 * saturation throughput must be the same ORDER OF MAGNITUDE as
 * `kafka-producer-perf-test` on the SAME single-node KRaft broker. The
 * original sin this pins against: each system measured by a different
 * client stack with different concurrency semantics — the retired probes
 * were 100–1000x off, and THAT class is what a local regression can catch.
 *
 * Why the band is 3x here and the 15% gate is G3's (measured 2026-07-15,
 * four configurations on one laptop — evidence, not hand-waving):
 *  - 6-partition topic: harness 137k vs oracle 97k (short oracle run: its
 *    3 s aggregate was ramp-dominated), then 87k vs 47k on longer runs —
 *    perf-test's null-key sticky partitioner streams ONE partition at a
 *    time while keyed records spread over all 6: a partitioning-shape
 *    difference, not a load-model one.
 *  - 1-partition topic (shapes matched), window 512: harness capped at
 *    25k = window/latency (Little's Law) while perf-test's 32 MB
 *    accumulator is an effective ~32k-deep queue; window 4096: 54k vs 91k.
 *  - The residual gap is the F18 bound: the driver expires queued records
 *    at 5 s (fault-run contract, non-negotiable) while perf-test waits out
 *    laptop writeback stalls with its 120 s delivery timeout.
 * On the campaign cluster (dedicated vCPU, real disk, stalls rare) the
 * symmetric 15% comparison runs as M6.1 calibration — where the plan
 * always put it ("on the real cluster, <=15% or explained").
 *
 * 2026-07-16 evidence extension: after a day of back-to-back suites and
 * image builds (measured: ~620 MB dirty+writeback, load 7.3) this test
 * measured 0.16x and, on a rerun under the same pressure, timed out in
 * connect()'s 10 s topic creation; on the settled machine (dirty 1 MB,
 * load <3) the very same code measured 0.50x green. Laptop disk pressure
 * alone swings the harness side ~5x — if this test fails locally, check
 * /proc/meminfo Dirty/Writeback and the load average BEFORE suspecting
 * the code, and rerun settled.
 *
 * Configuration chosen on measured stability, not aesthetics: both sides
 * run the driver's native 6-partition shape (fully matching the pipeline
 * via 1-partition topics was tried — single-pipeline acks=all on a laptop
 * disk is stall-dominated: 5x throughput swings and expiry cascades that
 * scale with queue depth). The remaining sticky-vs-keyed shape difference
 * measured <=2x, inside the band. The oracle gets a discarded warm-up exec
 * (fresh JVM per invocation); both clients acks=all, 1024 B values,
 * default batching. The oracle runs INSIDE the broker container (docker
 * exec) against the BROKER listener localhost:9093 (advertised by
 * container hostname); the harness runs from the host through the mapped
 * listener, like every other driver.
 */
class KafkaPerfTestParityTest {

    private static final Pattern RECORDS_PER_SEC =
            Pattern.compile("(\\d+) records sent, ([0-9.]+) records/sec");

    /** Runs perf-test inside the broker container, returns aggregate rec/s. */
    private static double perfTestRecordsPerSec(String containerName, String topic,
                                                int numRecords) throws Exception {
        Process p = new ProcessBuilder(
                "docker", "exec", containerName,
                "/opt/kafka/bin/kafka-producer-perf-test.sh",
                "--topic", topic,
                "--num-records", Integer.toString(numRecords),
                "--record-size", "1024",
                "--throughput", "-1",
                "--producer-props", "bootstrap.servers=localhost:9093", "acks=all")
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(240, TimeUnit.SECONDS), "perf-test must finish, output:\n" + out);
        assertEquals(0, p.exitValue(), "perf-test failed:\n" + out);

        double last = -1; // final summary is the LAST records/sec line
        Matcher m = RECORDS_PER_SEC.matcher(out);
        while (m.find()) last = Double.parseDouble(m.group(2));
        assertTrue(last > 0, "no records/sec line in perf-test output:\n" + out);
        return last;
    }

    @Test
    void saturationThroughputSameOrderOfMagnitudeAsKafkasOwnPerfTest() throws Exception {
        try (var provider = new LocalDockerProvider()) {
            var nodes = provider.start(SystemUnderTest.KRAFT, 1);
            String container = nodes.get(0).containerName();
            List<String> endpoints = provider.clientEndpoints();

            // The oracle's topic mirrors the driver's shape (6 partitions,
            // RF 1) — auto-creation would give it 1 partition, an unfair
            // race. The driver creates its own "bench" topic at connect().
            try (Admin admin = Admin.create(Map.<String, Object>of(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, endpoints.get(0)))) {
                admin.createTopics(List.of(new NewTopic("perfbench", KafkaDriver.PARTITIONS,
                        (short) 1))).all().get(10, TimeUnit.SECONDS);
            }

            // Oracle: one discarded warm-up exec, then the measured run.
            // The measured run must be LONG (≈15 s at laptop rates, matching
            // the harness window): a 3 s perf-test aggregate is dominated by
            // its own JVM/metadata ramp — measured 40% low against a clean
            // 15 s harness window before this was sized up.
            perfTestRecordsPerSec(container, "perfbench", 200_000);
            double oracle = perfTestRecordsPerSec(container, "perfbench", 750_000);

            // Harness: saturation mode, 1 KiB values, own warmup, window 512
            // (over 6 partitions the window does not bind — measured ratios
            // 1.4-1.9x at this shape; on 1 partition it did: Little's Law,
            // window/latency, see class javadoc).
            // A small error fraction is tolerated: a multi-second broker
            // stall (laptop fsync/GC) expires queued records at the 5 s
            // delivery deadline — honest timeouts, not a broken load model.
            // Throughput counts COMMITS only, so the comparison stays fair.
            double harness;
            try (var driver = new KafkaDriver(SystemUnderTest.KRAFT, endpoints)) {
                var cfg = new WorkloadEngine.Config(20, 5, 0, 512, 1024, 0.0);
                var rec = new LatencyRecorder();
                var r = new WorkloadEngine(driver, cfg, rec).run();
                long attempts = rec.countAfterWarmup() + r.errors();
                assertTrue(r.errors() <= attempts / 50,
                        "error rate above 2%% (" + r.errors() + "/" + attempts
                                + ") — first cause: " + r.firstError());
                harness = rec.countAfterWarmup() / 15.0;
            }

            double ratio = harness / oracle;
            System.out.printf(
                    "G1 flaw-B parity (local, order-of-magnitude): harness=%.0f ops/s "
                            + "perf-test=%.0f rec/s ratio=%.2fx%n", harness, oracle, ratio);
            assertTrue(ratio >= 1.0 / 3 && ratio <= 3.0,
                    ("G1 flaw-B class regression: harness %.0f ops/s vs perf-test %.0f rec/s "
                            + "(%.2fx) — outside the 3x order-of-magnitude band; the retired "
                            + "probe class was 100-1000x off").formatted(harness, oracle, ratio));
        }
    }
}
