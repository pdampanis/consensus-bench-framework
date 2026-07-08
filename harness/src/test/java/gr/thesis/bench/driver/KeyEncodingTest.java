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
    void paxiKeyPathIsNumericOnly() {
        for (int id : new int[]{0, 7, 999}) {
            String path = PaxiDriver.keyPath(id);
            assertTrue(path.matches("/\\d+"),
                    "Paxi needs an integer URL path (Atoi), got " + path);
        }
        assertEquals("/42", PaxiDriver.keyPath(42));
    }
}
