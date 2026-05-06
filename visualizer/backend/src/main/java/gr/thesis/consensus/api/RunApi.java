package gr.thesis.consensus.api;

import gr.thesis.consensus.domain.BenchmarkRun;
import gr.thesis.consensus.domain.BenchmarkRunRepository;
import gr.thesis.consensus.service.AggregationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints consumed by the React frontend.
 *
 * Intentionally small surface: the frontend needs the list of systems and
 * scenarios, the per-configuration aggregated statistics, the per-run raw
 * data for time-series plotting, and the consensus-algorithm animation
 * metadata. Everything else (authentication, pagination, etc.) is out of
 * scope for a thesis visualizer.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // Local dev only. Tighten for any real deployment.
@RequiredArgsConstructor
public class RunApi {

    private final BenchmarkRunRepository repo;
    private final AggregationService aggregation;

    /** List of all systems that have at least one run. */
    @GetMapping("/systems")
    public List<String> listSystems() {
        return repo.findDistinctSystems();
    }

    /** Scenarios present for a given system. */
    @GetMapping("/systems/{system}/scenarios")
    public List<String> listScenarios(@PathVariable String system) {
        return repo.findDistinctScenariosForSystem(system);
    }

    /** All individual runs for a (system, scenario). */
    @GetMapping("/systems/{system}/scenarios/{scenario}/runs")
    public List<BenchmarkRun> listRuns(@PathVariable String system,
                                       @PathVariable String scenario) {
        return repo.findBySystemAndScenarioOrderByStartedAtAsc(system, scenario);
    }

    /**
     * Aggregated statistics (mean, median, p95CI, per-run distribution) for
     * a (system, scenario) across all runs. This is the primary endpoint
     * used by the comparison charts.
     */
    @GetMapping("/systems/{system}/scenarios/{scenario}/aggregate")
    public Map<String, Object> aggregate(@PathVariable String system,
                                         @PathVariable String scenario) {
        return aggregation.aggregate(system, scenario);
    }

    /**
     * Side-by-side aggregated comparison across every system for one
     * scenario. This powers the main "cross-system" charts.
     */
    @GetMapping("/compare/{scenario}")
    public Map<String, Object> compareAcrossSystems(@PathVariable String scenario) {
        return aggregation.compareAcrossSystems(scenario);
    }
}
