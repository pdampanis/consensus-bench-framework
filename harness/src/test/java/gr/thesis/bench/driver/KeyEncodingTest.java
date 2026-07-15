package gr.thesis.bench.driver;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins each driver's native encoding of the typed workload keyId (P1.1).
 * The SAME keyId stream drives every system; only the encoding differs —
 * and each encoding has a system-imposed constraint worth a test:
 *  - etcd accepts arbitrary bytes (we use a readable path-style key);
 *  - Paxi parses the URL path with Atoi, so the path MUST be numeric-only
 *    (the old byte[]-blob contract sent "bench/k<i>/<op>" and would have
 *    failed every single Paxi op).
 */
class KeyEncodingTest {

    @Test
    void etcdKeyIsPathStyleAndStable() {
        assertEquals("bench/k0", new String(EtcdHttpDriver.encodeKey(0), StandardCharsets.UTF_8));
        assertEquals("bench/k999", new String(EtcdHttpDriver.encodeKey(999), StandardCharsets.UTF_8));
    }

    @Test
    void kafkaKeyIsUtf8IntegerBytes() {
        // Kafka's native encoding of a workload keyId: the integer's UTF-8
        // string as record-key bytes. Same keyId -> same bytes -> same
        // partition (murmur2 on the key), so contention is deterministic.
        assertEquals("0", new String(KafkaDriver.encodeKey(0), StandardCharsets.UTF_8));
        assertEquals("42", new String(KafkaDriver.encodeKey(42), StandardCharsets.UTF_8));
        assertEquals("999", new String(KafkaDriver.encodeKey(999), StandardCharsets.UTF_8));
    }

    @Test
    void cometBftTxPrefixIsKvstoreKeyEquals() {
        // CometBFT's native encoding: kvstore's CheckTx splits the tx on
        // '=' and requires EXACTLY two parts (code 2 otherwise — measured:
        // a raw-byte nonce containing 0x3d failed ~12% of txs), so this is
        // the only '=' in the tx and nonce+payload must stay '='-free.
        assertEquals("k0=", new String(CometBftDriver.txPrefix(0), StandardCharsets.UTF_8));
        assertEquals("k999=", new String(CometBftDriver.txPrefix(999), StandardCharsets.UTF_8));
    }

    @Test
    void paxiKeyPathIsNumericOnly() {
        for (int id : new int[]{0, 7, 999}) {
            String path = PaxiDriver.keyPath(id);
            assertTrue(path.matches("/\\d+"),
                    "Paxi needs an integer URL path (Atoi), got " + path);
        }
        assertEquals("/42", PaxiDriver.keyPath(42));
    }
}
