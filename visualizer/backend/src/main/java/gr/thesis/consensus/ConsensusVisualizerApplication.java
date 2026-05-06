package gr.thesis.consensus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Consensus Benchmark Visualizer backend.
 *
 * This service reads thesis benchmark results from disk (via a results-import
 * batch job at startup) and from an accompanying Postgres database, and
 * exposes them over a small REST API to the React frontend. It also serves
 * static consensus-algorithm animation metadata (a JSON description of how
 * each algorithm's message flow should be animated) so the frontend can
 * render the per-algorithm visualizations without embedding that knowledge
 * in the client.
 *
 * Designed to be started via docker-compose with a sibling Postgres
 * container and the results directory mounted read-only.
 */
@SpringBootApplication
public class ConsensusVisualizerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsensusVisualizerApplication.class, args);
    }
}
