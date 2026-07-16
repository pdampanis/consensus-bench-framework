package gr.thesis.bench.driver;

import gr.thesis.bench.core.SystemUnderTest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P2.4b unit contracts (no Docker):
 *
 *  1. Ballot-header parsing — F22: paxi has NO /state endpoint; the leader
 *     is identified by the {@code Ballot} response header every committed
 *     paxos write carries, formatted "n.zone.node" (ballot.go String()),
 *     whose ID part is the LEADER's paxi ID. Our clusters are single-zone
 *     ("1.<node>", node 1-based = endpoint index 0-based); any other shape
 *     is a topology surprise and must fail loud — never kill the wrong
 *     node (the same rule KafkaDriver pins for an unmappable leader).
 *
 *  2. Endpoint strategy — F24: the endpoint list is node-ordered for BOTH
 *     systems (index identity for leader detection / fault targeting), but
 *     the write path differs by protocol: PAXOS pins every write to ONE
 *     endpoint (single client entry; internal forwarding is the documented
 *     hop), EPAXOS round-robins all endpoints (leaderless — single-endpoint
 *     traffic could never exercise it; the retired probe's exact mistake).
 */
class PaxiDriverContractTest {

    @Test
    void ballotHeaderParsesToTheLeaderNodeIndex() {
        assertEquals(0, PaxiDriver.leaderNodeIndexFromBallot("12.1.1"));
        assertEquals(1, PaxiDriver.leaderNodeIndexFromBallot("7.1.2"));
        assertEquals(2, PaxiDriver.leaderNodeIndexFromBallot("3.1.3"));
    }

    @Test
    void ballotFromAnUnexpectedZoneFailsLoud() {
        assertThrows(IllegalStateException.class,
                () -> PaxiDriver.leaderNodeIndexFromBallot("7.2.1"),
                "multi-zone ballot on a single-zone cluster is a topology surprise");
    }

    @Test
    void malformedBallotFailsLoud() {
        for (String bad : new String[]{"", "garbage", "7", "7.1", "a.b.c", "7.1.0"}) {
            assertThrows(IllegalStateException.class,
                    () -> PaxiDriver.leaderNodeIndexFromBallot(bad),
                    "malformed ballot must fail loud: <" + bad + ">");
        }
    }

    @Test
    void paxosPinsWritesToTheFirstEndpointEpaxosRoundRobins() {
        for (long seq = 0; seq < 6; seq++) {
            assertEquals(0, PaxiDriver.endpointIndexFor(SystemUnderTest.PAXOS, seq, 3),
                    "PAXOS writes pin to the single client entry (F24)");
        }
        assertEquals(0, PaxiDriver.endpointIndexFor(SystemUnderTest.EPAXOS, 0, 3));
        assertEquals(1, PaxiDriver.endpointIndexFor(SystemUnderTest.EPAXOS, 1, 3));
        assertEquals(2, PaxiDriver.endpointIndexFor(SystemUnderTest.EPAXOS, 2, 3));
        assertEquals(0, PaxiDriver.endpointIndexFor(SystemUnderTest.EPAXOS, 3, 3));
    }
}
