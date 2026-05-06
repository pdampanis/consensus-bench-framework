package gr.thesis.consensus.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BenchmarkRunRepository extends JpaRepository<BenchmarkRun, Long> {

    List<BenchmarkRun> findBySystemOrderByStartedAtAsc(String system);

    List<BenchmarkRun> findBySystemAndScenarioOrderByStartedAtAsc(String system, String scenario);

    @Query("select distinct r.system from BenchmarkRun r order by r.system")
    List<String> findDistinctSystems();

    @Query("select distinct r.scenario from BenchmarkRun r where r.system = ?1 order by r.scenario")
    List<String> findDistinctScenariosForSystem(String system);
}
