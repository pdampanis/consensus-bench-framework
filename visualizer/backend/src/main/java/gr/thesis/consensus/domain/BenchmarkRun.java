package gr.thesis.consensus.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One row per experiment run. Mirrors the manifest.json produced by
 * run_experiment.sh, augmented with the aggregate YCSB numbers.
 *
 * Indexed on (system, scenario) to make the "all runs for this
 * configuration" queries cheap.
 */
@Entity
@Table(
    name = "benchmark_run",
    indexes = {
        @Index(name = "ix_run_system_scenario", columnList = "system,scenario"),
        @Index(name = "ix_run_started_at", columnList = "started_at")
    }
)
@Data
@NoArgsConstructor
public class BenchmarkRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "kraft", "zk", "etcd", "tendermint" */
    @Column(nullable = false, length = 32)
    private String system;

    /** "baseline", "packet_loss", "leader_kill", "dos", "network_partition" */
    @Column(nullable = false, length = 64)
    private String scenario;

    /** Human-readable id from the run directory, e.g. "run001". */
    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** YCSB "Overall Throughput(ops/sec)". */
    @Column(name = "throughput_ops")
    private Double throughputOps;

    /** YCSB "UPDATE AverageLatency(us)" in microseconds. */
    @Column(name = "write_avg_us")
    private Double writeAvgUs;

    @Column(name = "write_p95_us")
    private Double writeP95Us;

    @Column(name = "write_p99_us")
    private Double writeP99Us;

    @Column(name = "write_p999_us")
    private Double writeP999Us;

    @Column(name = "read_avg_us")
    private Double readAvgUs;

    @Column(name = "read_p99_us")
    private Double readP99Us;

    /** Relative path to the Prometheus snapshot tarball. */
    @Column(name = "prometheus_snapshot_path", length = 512)
    private String prometheusSnapshotPath;

    /** "PASS" / "FAIL" / "NOT_CHECKED" from the consistency checker. */
    @Column(name = "consistency_result", length = 16)
    private String consistencyResult;

    @Column(name = "cluster_size")
    private Integer clusterSize;

    @Column(length = 128)
    private String workload;

    @Column(name = "duration_secs")
    private Integer durationSecs;

    /** Host environment: kernel, docker version, cpu model — free-form JSON. */
    @Column(name = "environment_json", columnDefinition = "TEXT")
    private String environmentJson;
}
