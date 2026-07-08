package gr.thesis.bench;

import gr.thesis.bench.topology.LocalDockerProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0.3 acceptance (integration — needs Docker): ONE command performs
 * clean -> deploy -> test -> teardown:
 *  - a leftover thesis-* container from a "crashed" earlier run is
 *    pre-cleaned (idempotency);
 *  - the run produces a complete manifest (status=complete, errors=0,
 *    ops>0 implied by status);
 *  - nothing survives teardown;
 *  - a second consecutive invocation works (no name/port clash).
 */
class LocalRunTest {

    @TempDir
    Path out;

    private static long thesisContainers() {
        return DockerClientFactory.instance().client().listContainersCmd()
                .withShowAll(true).exec().stream()
                .flatMap(c -> Arrays.stream(c.getNames()))
                .filter(n -> n.startsWith("/thesis-"))
                .count();
    }

    @Test
    void localRun_cleansDeploysTestsTearsDown_twiceInARow() throws Exception {
        // Simulate a crashed earlier run: a stopped leftover container.
        DockerClientFactory.instance().client()
                .createContainerCmd(LocalDockerProvider.ETCD_IMAGE)
                .withName("thesis-leftover-p03").exec();
        assertEquals(1, thesisContainers(), "leftover planted");

        Main.main(new String[]{"local-run", "--size", "1", "--duration", "3", "--warmup", "1",
                "--rate", "100", "--out", out.toString(), "--run", "it1"});

        String manifest = Files.readString(out.resolve("etcd/baseline/size1/it1/manifest.json"));
        assertTrue(manifest.contains("\"status\": \"complete\""), manifest);
        assertTrue(manifest.contains("\"errors\": 0"), manifest);
        assertEquals(0, thesisContainers(), "teardown + pre-clean leave zero thesis-* containers");

        // Second consecutive invocation must be clash-free and just as clean.
        Main.main(new String[]{"local-run", "--size", "1", "--duration", "3", "--warmup", "1",
                "--rate", "100", "--out", out.toString(), "--run", "it2"});

        assertTrue(Files.readString(out.resolve("etcd/baseline/size1/it2/manifest.json"))
                .contains("\"status\": \"complete\""));
        assertEquals(0, thesisContainers());
    }
}
