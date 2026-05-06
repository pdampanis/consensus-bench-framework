package gr.thesis.consensus.api;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serves the per-algorithm animation metadata. Each algorithm is described
 * by a JSON document that lists the nodes (with positions), the static
 * edges between them, and a sequence of "message flow" events that the
 * frontend animates as packets travelling along edges.
 *
 * The JSON files live in src/main/resources/algorithms/ and are shipped
 * with the jar so no external config is needed. The frontend fetches them
 * once on algorithm-tab selection.
 */
@RestController
@RequestMapping("/api/algorithms")
@CrossOrigin(origins = "*")
public class AlgorithmApi {

    private static final List<String> ALGORITHMS = List.of(
        "raft", "kraft", "zab", "epaxos", "tendermint", "hotstuff"
    );

    @GetMapping
    public List<String> list() {
        return ALGORITHMS;
    }

    @GetMapping(value = "/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String get(@PathVariable String name) throws IOException {
        if (!ALGORITHMS.contains(name)) {
            throw new IllegalArgumentException("Unknown algorithm: " + name);
        }
        var resource = new ClassPathResource("algorithms/" + name + ".json");
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
