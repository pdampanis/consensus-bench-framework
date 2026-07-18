package gr.thesis.bench;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the CLI argument contract (P0.1, written red-first):
 *  - "--key value" pairs, as before;
 *  - a bare -v / --verbose boolean flag, anywhere in the line, that must
 *    NOT consume the following token as its value;
 *  - fail closed on a trailing key with no value (the old parser silently
 *    dropped it — the v6 disease in miniature).
 */
class ArgParserTest {

    @Test
    void pairsAreParsed() {
        Map<String, String> m = Main.parse(new String[]{"--rate", "300", "--duration", "20"});
        assertEquals("300", m.get("rate"));
        assertEquals("20", m.get("duration"));
        assertFalse(m.containsKey("verbose"), "no flag given -> verbose absent");
    }

    @Test
    void bareShortVerboseFlag() {
        assertEquals("true", Main.parse(new String[]{"-v"}).get("verbose"));
    }

    @Test
    void bareLongVerboseFlag() {
        assertEquals("true", Main.parse(new String[]{"--verbose"}).get("verbose"));
    }

    @Test
    void verboseBetweenPairsDoesNotEatTheNextKey() {
        Map<String, String> m = Main.parse(new String[]{"--rate", "300", "-v", "--duration", "20"});
        assertEquals("true", m.get("verbose"));
        assertEquals("300", m.get("rate"));
        assertEquals("20", m.get("duration"), "-v must not consume --duration as its value");
    }

    @Test
    void trailingKeyWithoutValueFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.parse(new String[]{"--rate"}));
    }

    @Test
    void unknownArgumentKeyFailsClosed() {
        // F32: a typo'd key (--ratee) must throw, not silently run at the
        // default — the same fail-closed rule the value-less key already has.
        var known = java.util.Set.of("rate", "duration");
        var e = assertThrows(IllegalArgumentException.class,
                () -> Main.requireKnownKeys(Map.of("ratee", "300"), known));
        assertEquals(true, e.getMessage().contains("--ratee"),
                "the unknown key must be NAMED: " + e.getMessage());
        Main.requireKnownKeys(Map.of("rate", "300"), known); // valid: must not throw
    }

    @Test
    void durationMustExceedWarmup() {
        // duration == warmup would make the measurement window empty and the
        // throughput division by (duration - warmup) blow up (review F17).
        assertThrows(IllegalArgumentException.class,
                () -> Main.requireDurationExceedsWarmup(5, 5));
        assertThrows(IllegalArgumentException.class,
                () -> Main.requireDurationExceedsWarmup(4, 5));
        Main.requireDurationExceedsWarmup(6, 5); // valid: must not throw
    }
}
