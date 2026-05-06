package gr.thesis.consensus.service;

import gr.thesis.consensus.domain.BenchmarkRun;
import gr.thesis.consensus.domain.BenchmarkRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * Computes summary statistics across the set of runs for a given
 * (system, scenario). Mirrors the Python analyse.py computations so that
 * the visualizer can display the same numbers the thesis figures use,
 * without a round trip through the Python script.
 */
@Service
@RequiredArgsConstructor
public class AggregationService {

    private final BenchmarkRunRepository repo;

    public Map<String, Object> aggregate(String system, String scenario) {
        List<BenchmarkRun> runs = repo.findBySystemAndScenarioOrderByStartedAtAsc(system, scenario);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("system", system);
        out.put("scenario", scenario);
        out.put("nRuns", runs.size());
        out.put("throughputOps", summarise(runs, BenchmarkRun::getThroughputOps));
        out.put("writeP99Us", summarise(runs, BenchmarkRun::getWriteP99Us));
        out.put("writeAvgUs", summarise(runs, BenchmarkRun::getWriteAvgUs));
        out.put("rawRuns", runs);
        return out;
    }

    public Map<String, Object> compareAcrossSystems(String scenario) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", scenario);
        Map<String, Object> bySystem = new LinkedHashMap<>();
        for (String s : repo.findDistinctSystems()) {
            List<BenchmarkRun> runs = repo.findBySystemAndScenarioOrderByStartedAtAsc(s, scenario);
            if (runs.isEmpty()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("throughputOps", summarise(runs, BenchmarkRun::getThroughputOps));
            row.put("writeP99Us", summarise(runs, BenchmarkRun::getWriteP99Us));
            row.put("nRuns", runs.size());
            bySystem.put(s, row);
        }
        out.put("systems", bySystem);
        return out;
    }

    /** Returns mean, median, std, min, max, and a 95% bootstrap CI for the mean. */
    private Map<String, Double> summarise(List<BenchmarkRun> runs, ToDoubleFunction<BenchmarkRun> getter) {
        double[] values = runs.stream()
            .mapToDouble(r -> {
                Double v = getter.applyAsDouble(r) == 0.0 ? null : getter.applyAsDouble(r);
                return v != null ? v : Double.NaN;
            })
            .filter(v -> !Double.isNaN(v))
            .toArray();
        Map<String, Double> m = new LinkedHashMap<>();
        if (values.length == 0) return m;

        Arrays.sort(values);
        double mean = Arrays.stream(values).average().orElse(Double.NaN);
        double median = values[values.length / 2];
        double min = values[0];
        double max = values[values.length - 1];
        double std = std(values, mean);
        double[] ci = bootstrapCi(values);

        m.put("n", (double) values.length);
        m.put("mean", mean);
        m.put("median", median);
        m.put("std", std);
        m.put("min", min);
        m.put("max", max);
        m.put("ci95Low", ci[0]);
        m.put("ci95High", ci[1]);
        return m;
    }

    private static double std(double[] values, double mean) {
        if (values.length < 2) return 0;
        double s = 0;
        for (double v : values) s += (v - mean) * (v - mean);
        return Math.sqrt(s / (values.length - 1));
    }

    /**
     * Bootstrap 95% confidence interval for the mean. Resamples 1000 times.
     * Matches the analyse.py behaviour so the backend and the Python script
     * agree to within bootstrap noise.
     */
    private static double[] bootstrapCi(double[] values) {
        int resamples = 1000;
        Random rng = new Random(42); // deterministic
        double[] means = new double[resamples];
        for (int i = 0; i < resamples; i++) {
            double sum = 0;
            for (int j = 0; j < values.length; j++) {
                sum += values[rng.nextInt(values.length)];
            }
            means[i] = sum / values.length;
        }
        Arrays.sort(means);
        return new double[]{ means[(int) (0.025 * resamples)],
                             means[(int) (0.975 * resamples)] };
    }
}
