package gr.thesis.bench.campaign;

import gr.thesis.bench.core.Scenario;
import gr.thesis.bench.core.SystemUnderTest;
import gr.thesis.bench.driver.ConsensusDriver;
import gr.thesis.bench.driver.EtcdDriver;
import gr.thesis.bench.driver.KafkaDriver;
import gr.thesis.bench.driver.PaxiDriver;
import gr.thesis.bench.topology.LocalDockerProvider;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session-mode wiring that MUST be pinned before a billed VM sees it:
 * fault targeting (F13/F19 — the detected leader, never a guess; replica 0
 * only where no stable leader exists BY DESIGN), the per-system driver
 * dispatch, the upstream-client invocation string, and the Spec's
 * fail-closed validation. The full orchestration path is exercised live at
 * the P3.4 canary — these tests pin every decision it will make.
 */
class RemoteRunnerTest {

    /** Driver stub reporting a chosen leader — campaign tests must not
     *  depend on topology-test internals. */
    private static ConsensusDriver leaderReporting(Optional<Integer> leader) {
        return new ConsensusDriver() {
            @Override public SystemUnderTest system() { return SystemUnderTest.ETCD; }
            @Override public void connect() { }
            @Override public CompletionStage<Void> write(int keyId, byte[] value) {
                throw new UnsupportedOperationException("not driven in this test");
            }
            @Override public Optional<Integer> currentLeaderIndex() { return leader; }
            @Override public void close() { }
        };
    }

    @Test
    void leaderSensitiveFaultsTargetTheDetectedLeader() throws Exception {
        assertEquals(2, RemoteRunner.faultTargetIndex(
                SystemUnderTest.ETCD, leaderReporting(Optional.of(2))));
    }

    @Test
    void noDetectedLeaderRefusesToGuess() {
        var e = assertThrows(IllegalStateException.class,
                () -> RemoteRunner.faultTargetIndex(
                        SystemUnderTest.KRAFT, leaderReporting(Optional.empty())));
        assertTrue(e.getMessage().contains("KRAFT"), e.getMessage());
    }

    @Test
    void leaderlessAndRotatingSystemsUseDocumentedReplicaZero() throws Exception {
        // EPaxos is leaderless BY DESIGN; CometBFT's proposer rotates every
        // height. Replica 0 is equivalent by symmetry — a DOCUMENTED
        // deterministic choice, so no driver is even consulted (null).
        assertEquals(0, RemoteRunner.faultTargetIndex(SystemUnderTest.EPAXOS, null));
        assertEquals(0, RemoteRunner.faultTargetIndex(SystemUnderTest.TENDERMINT, null));
    }

    @Test
    void driverDispatchMatchesSystem() {
        assertInstanceOf(EtcdDriver.class,
                RemoteRunner.driverFor(SystemUnderTest.ETCD, List.of("http://10.0.0.11:2379")));
        assertInstanceOf(KafkaDriver.class,
                RemoteRunner.driverFor(SystemUnderTest.KRAFT, List.of("10.0.0.11:9092")));
        assertInstanceOf(KafkaDriver.class,
                RemoteRunner.driverFor(SystemUnderTest.KAFKA_ZK, List.of("10.0.0.11:9092")));
        assertInstanceOf(PaxiDriver.class,
                RemoteRunner.driverFor(SystemUnderTest.EPAXOS, List.of("http://10.0.0.11:8080")));
        assertThrows(IllegalArgumentException.class,
                () -> RemoteRunner.driverFor(SystemUnderTest.HOTSTUFF, List.of("10.0.0.11:26001")),
                "HOTSTUFF has no ConsensusDriver — its own client is the boundary");
    }

    @Test
    void hotstuffClientCommandIsTheProbedCliContract() {
        // Probed 2026-07-17: client <target> --timeout --size --rate
        // --nodes <all>; the client blocks until every --nodes address
        // accepts connections (the cluster-up gate). --network host: the
        // loadgen talks straight onto the private net.
        List<String> eps = List.of("10.0.0.11:26001", "10.0.0.12:26001",
                "10.0.0.13:26001", "10.0.0.14:26001");
        assertEquals("docker run -d --name thesis-hs-client --network host "
                        + LocalDockerProvider.HOTSTUFF_IMAGE
                        + " client 10.0.0.11:26001 --timeout 5000 --size 1024 --rate 350"
                        + " --nodes 10.0.0.11:26001 10.0.0.12:26001 10.0.0.13:26001 10.0.0.14:26001",
                RemoteRunner.clientRunCommand(eps, 350, 1024));
    }

    @Test
    void imageMapCoversEverySystem() {
        for (SystemUnderTest s : SystemUnderTest.values()) {
            assertTrue(RemoteRunner.imageFor(s) != null && !RemoteRunner.imageFor(s).isBlank(),
                    "manifest image pin missing for " + s);
        }
        assertEquals(LocalDockerProvider.HOTSTUFF_IMAGE,
                RemoteRunner.imageFor(SystemUnderTest.HOTSTUFF));
    }

    @Test
    void faultTimeOutsideTheRunFailsClosedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new RemoteRunner.Spec(
                SystemUnderTest.ETCD, Scenario.LEADER_KILL, 3, 300, 480, 180, 200, 1024,
                0.0, 480 /* == duration: fault could never land */, 0, 0,
                Path.of("results"), "r001", Path.of("deploy/inventory.env"), "root"));
    }

    @Test
    void engineStartWaitIsBoundedSoAFailedConnectCannotSpinForever() {
        // F50c: the fault thread waits for the engine to start the EventLog
        // (F47's alignment fix). If engine.run() dies inside driver.connect(),
        // start() never comes and the ORIGINAL unbounded loop spun for the
        // life of the campaign JVM — one leaked daemon thread per failed
        // fault cell, plus a fixed 30 s join timeout each time. The bound is
        // a leak-stopper, not a timing gate, so it fails LOUD rather than
        // returning as if the engine had started.
        var neverStarted = new gr.thesis.bench.core.EventLog(8);
        long t0 = System.nanoTime();
        var e = assertThrows(IllegalStateException.class,
                () -> RemoteRunner.awaitEngineStart(neverStarted, java.time.Duration.ofMillis(300)));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue(e.getMessage().contains("event log"), e.getMessage());
        assertTrue(elapsedMs < 5_000, "must give up at its bound, waited " + elapsedMs + " ms");
    }

    @Test
    void engineStartWaitReturnsOnceTheLogIsStarted() throws Exception {
        var started = new gr.thesis.bench.core.EventLog(8);
        started.start(System.nanoTime());
        RemoteRunner.awaitEngineStart(started, java.time.Duration.ofSeconds(5)); // must not throw
    }

    @Test
    void baselineSpecIgnoresTheFaultTimeItNeverUses() {
        // F48: a short BASELINE run was rejected because the DEFAULTED
        // fault-at (warmup+60) landed outside it — but no fault thread ever
        // starts on BASELINE, so the check validated an input the run never
        // reads. The validation now applies to fault scenarios only.
        var spec = new RemoteRunner.Spec(SystemUnderTest.ETCD, Scenario.BASELINE, 3, 300,
                200, 180, 200, 1024, 0.0, 240 /* > duration, unused */, 0, 0,
                Path.of("results"), "r001", Path.of("deploy/inventory.env"), "root");
        assertEquals(Scenario.BASELINE, spec.scenario());
    }

    // ---- F70: how the event buffer is sized, and where it stops scaling ----

    // ---- D15.3: slow_node's load lasts as long as the other faults ----

    @Test
    void slowNodeDurationIsDerivedFromTheRunShapeNotFixed() {
        // Standard block: 480 - 240 + 30 = 270. Failover block: 360 - 240
        // + 30 = 150. A FIXED 120 s left slow_node faulted for a shorter
        // slice of the measurement window than kill/partition/packet_loss,
        // which persist until heal() — and F5 compares those side by side.
        var std = new RemoteRunner.Spec(SystemUnderTest.ETCD, Scenario.SLOW_NODE, 3, 300,
                480, 180, 200, 1024, 0.0, 240, 0, 0, Path.of("results"), "r001",
                Path.of("inv"), "root");
        assertEquals(270, RemoteRunner.slowNodeSeconds(std));

        var failover = new RemoteRunner.Spec(SystemUnderTest.ETCD, Scenario.SLOW_NODE, 3, 300,
                360, 180, 200, 1024, 0.0, 240, 0, 0, Path.of("results"), "r001",
                Path.of("inv"), "root");
        assertEquals(150, RemoteRunner.slowNodeSeconds(failover));
    }

    // ---- D14/F53: --loss belongs to PACKET_LOSS and nowhere else ----

    @Test
    void severityIsRequiredForPacketLossAndRefusedElsewhere() {
        // Before D14 `--loss` defaulted to 30 for EVERY scenario, so a
        // leader_kill spec silently carried a severity it never used. Once
        // severity became part of cell identity that would have written a
        // `loss30` path segment onto runs that never lost a packet.
        assertThrows(IllegalArgumentException.class, () -> new RemoteRunner.Spec(
                SystemUnderTest.ETCD, Scenario.LEADER_KILL, 3, 300, 480, 180, 200, 1024,
                0.0, 240, 30, 0, Path.of("results"), "r001",
                Path.of("deploy/inventory.env"), "root"));
        assertThrows(IllegalArgumentException.class, () -> new RemoteRunner.Spec(
                SystemUnderTest.ETCD, Scenario.PACKET_LOSS, 3, 300, 480, 180, 200, 1024,
                0.0, 240, 0, 0, Path.of("results"), "r001",
                Path.of("deploy/inventory.env"), "root"));
        // …and the valid shape constructs.
        var ok = new RemoteRunner.Spec(SystemUnderTest.ETCD, Scenario.PACKET_LOSS, 3, 300,
                480, 180, 200, 1024, 0.0, 240, 5, 0, Path.of("results"), "r001",
                Path.of("deploy/inventory.env"), "root");
        assertEquals(5, ok.packetLossPercent());
    }

    /** faultAt is derived so the Spec's own F48 guard is always satisfied —
     *  these tests are about buffer sizing, not about fault timing. */
    private static RemoteRunner.Spec faultSpec(long rate, int duration) {
        return new RemoteRunner.Spec(SystemUnderTest.ETCD, Scenario.LEADER_KILL, 3, rate,
                duration, 180, 200, 1024, 0.0, Math.max(1, duration / 2), 0, 0,
                Path.of("results"), "r001", Path.of("deploy/inventory.env"), "root");
    }

    @Test
    void eventCapacityScalesWithTheRunShapeForRateBoundRuns() {
        // The buffer is derived, not fixed: rate x duration with 2x slack for
        // retries and the drain tail, plus the in-flight window.
        assertEquals(1_000 * 300 * 2 + 200, RemoteRunner.eventCapacity(faultSpec(1_000, 300)));
        // …and it is floored, so a tiny smoke run still records its events.
        assertEquals(100_000, RemoteRunner.eventCapacity(faultSpec(10, 10)));
    }

    @Test
    void eventCapacityStopsScalingAtTheRoofAndSaturationRunsGetOnlyTheRoof() {
        // F70's mechanism, pinned so the roof is visible in the suite rather
        // than buried in a constant. At the campaign shape (duration 480) the
        // derived size reaches the 4,000,000 roof at ~4,167 ops/s, so every
        // faster run is capped — and a SATURATION run (rate <= 0) has no rate
        // to derive from and takes the roof unconditionally.
        assertEquals(4_000_000, RemoteRunner.eventCapacity(faultSpec(50_000, 480)));
        assertEquals(4_000_000, RemoteRunner.eventCapacity(faultSpec(0, 480)));

        // Why that matters, stated as arithmetic rather than prose: the
        // campaign injects at warmup+60 = 240 s, so a run sustaining more
        // than 4,000,000 / 240 s commits fills the buffer BEFORE the fault
        // mark. Past that point failoverMillis() can find no qualifying
        // commit and the manifest would read "fault fired, never recovered".
        // CsvResultsWriter now refuses to call such a run complete (F70);
        // sizing the buffer from a measured saturation input is the follow-on.
        assertTrue(4_000_000 / 240 < 20_000,
                "a >20k ops/s fault run overflows before the mark — the honest-status"
                        + " rule in CsvResultsWriter is what stands between that and the"
                        + " F4 failover ECDF");
    }
}
