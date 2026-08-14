package gr.thesis.bench.campaign;

import gr.thesis.bench.core.Scenario;
import gr.thesis.bench.core.SystemUnderTest;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The campaign's named simulations (D12). A simulation is a typed Java
 * constant, not a parsed config file: the rebuild's founding lesson is that
 * typed Java makes the v6 string-surgery bug class inexpressible at compile
 * time, and a YAML spec would reintroduce exactly that for the sake of a
 * rerun-without-rebuild nobody has needed. What the thesis DOES need — a
 * publishable, citable artifact — comes from serializing the resolved spec
 * instead (see {@link MatrixRunner#writeSimulationSpec}).
 *
 * <p>Gatling and JMH declare their benchmarks the same way and for the same
 * reason; OpenMessaging and YCSB use files because they are multi-tenant
 * products, not a single-author instrument.
 *
 * <p>Sweep RATES stay operator inputs, deliberately: the runbook's 25/50/75%
 * points come from the measured saturation of a PRIOR block, so a static
 * constant cannot know them. Everything that is a decision rather than a
 * measurement lives here.
 */
public final class Simulations {

    private Simulations() { }

    /** Baseline + the four non-failover faults, n=5 — the standard block. */
    public static MatrixRunner.Block standard(SystemUnderTest system, int clusterSize,
                                              List<Long> rates, long seed,
                                              Path out, Path inventory, String sshUser) {
        return MatrixRunner.block(system, clusterSize,
                List.of(Scenario.BASELINE, Scenario.PACKET_LOSS, Scenario.NETWORK_PARTITION,
                        Scenario.SLOW_NODE, Scenario.DOUBLE_KILL),
                rates, List.of(0.0), 5, seed, out, inventory, sshUser);
    }

    /** The failover-distribution block: leader_kill ≥30 times at one rate,
     *  on the runbook's shorter shape (D15.4), with leaderless targets
     *  rotated (D15.5). F4's ECDF is the object of interest. */
    public static MatrixRunner.Block failover(SystemUnderTest system, int clusterSize,
                                              long rate, long seed,
                                              Path out, Path inventory, String sshUser) {
        return MatrixRunner.failoverBlock(system, clusterSize, rate, 30, seed,
                out, inventory, sshUser);
    }

    /** D7's conflict sweep, Paxos/EPaxos only — the knob EPaxos's fast path
     *  depends on, and without which EPaxos ≈ Paxos. */
    public static MatrixRunner.Block conflictSweep(SystemUnderTest system, List<Long> rates,
                                                   long seed, Path out, Path inventory,
                                                   String sshUser) {
        if (system != SystemUnderTest.PAXOS && system != SystemUnderTest.EPAXOS) {
            throw new IllegalArgumentException(
                    "the conflict sweep is a Paxi-pair factor (D7), not applicable to " + system);
        }
        return MatrixRunner.block(system, 3, List.of(Scenario.BASELINE), rates,
                List.of(0.0, 0.02, 0.10), 5, seed, out, inventory, sshUser);
    }

    /** Every named simulation, so `--simulation` can list them and a typo
     *  fails closed against the real set rather than silently running the
     *  default (F32's rule, applied to a value rather than a key). */
    public static Map<String, Builder> byName() {
        Map<String, Builder> m = new LinkedHashMap<>();
        m.put("standard", Simulations::standard);
        m.put("failover", (sys, size, rates, seed, out, inv, user) ->
                failover(sys, size, rates.get(0), seed, out, inv, user));
        m.put("conflict-sweep", (sys, size, rates, seed, out, inv, user) ->
                conflictSweep(sys, rates, seed, out, inv, user));
        return m;
    }

    /** One shape so every named simulation is selectable identically. */
    @FunctionalInterface
    public interface Builder {
        MatrixRunner.Block build(SystemUnderTest system, int clusterSize, List<Long> rates,
                                 long seed, Path out, Path inventory, String sshUser);
    }
}
