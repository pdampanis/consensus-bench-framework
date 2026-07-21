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
                0.0, 480 /* == duration: fault could never land */, 30,
                Path.of("results"), "r001", Path.of("deploy/inventory.env"), "root"));
    }

    @Test
    void baselineSpecIgnoresTheFaultTimeItNeverUses() {
        // F48: a short BASELINE run was rejected because the DEFAULTED
        // fault-at (warmup+60) landed outside it — but no fault thread ever
        // starts on BASELINE, so the check validated an input the run never
        // reads. The validation now applies to fault scenarios only.
        var spec = new RemoteRunner.Spec(SystemUnderTest.ETCD, Scenario.BASELINE, 3, 300,
                200, 180, 200, 1024, 0.0, 240 /* > duration, unused */, 30,
                Path.of("results"), "r001", Path.of("deploy/inventory.env"), "root");
        assertEquals(Scenario.BASELINE, spec.scenario());
    }
}
