package gr.thesis.bench.topology;

import gr.thesis.bench.core.SystemUnderTest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * ClusterProvider over SSH (P3.3b, M4.2) — one consensus container per VM
 * on the campaign's private network. THE V6 DANGER ZONE, so every action
 * flows through {@link SshExecutor}: the same code paths run against the
 * recorder (G2 golden files, reviewed by a human) and the real cluster.
 *
 * Remote deltas honored (the PENDING_TASKS P3.3 preregistration):
 *  - {@code NodeHandle.privateIp} carries the REAL private IP (F20) — the
 *    address peers, clients, and faults act on; {@code host} is the SSH
 *    target, the same IP, because the harness runs ON the loadgen.
 *  - {@code --network host} (D2): native ports on private IPs, no mapped
 *    ports; advertise URLs carry each node's own private IP.
 *  - Health = {@code curl} ON the node against localhost (host networking
 *    makes them equivalent), retried until the deadline — etcd's /health
 *    answers only once the member sees quorum, so all containers start
 *    BEFORE the gate polls.
 *  - Container names are DETERMINISTIC (thesis-etcd1..N): exactly one
 *    cluster exists at a time on the campaign (EXECUTION_AND_COST_MODEL
 *    §1), pre-clean covers crashed predecessors, and determinism is what
 *    makes the goldens exact.
 *
 * ETCD only for now — every other system fails closed until its block
 * lands with its own golden (claiming them unverified would be v6's sin).
 */
public final class RemoteSshProvider implements ClusterProvider {

    private static final Logger log = LoggerFactory.getLogger(RemoteSshProvider.class);
    private static final int SSH_PORT = 22;
    static final String HEALTH_CMD = "curl -sf --max-time 2 http://127.0.0.1:2379/health";

    private final SshExecutor ssh;
    private final List<String> nodeIps; // node order — index IS the node index
    private final Duration healthDeadline;
    private final List<NodeHandle> nodes = new ArrayList<>();
    private final List<String> endpoints = new ArrayList<>();

    public RemoteSshProvider(SshExecutor ssh, List<String> nodePrivateIps,
                             Duration healthDeadline) {
        if (nodePrivateIps.isEmpty()) throw new IllegalArgumentException("no node IPs");
        this.ssh = ssh;
        this.nodeIps = List.copyOf(nodePrivateIps);
        this.healthDeadline = healthDeadline;
    }

    @Override
    public List<NodeHandle> start(SystemUnderTest system, int clusterSize) throws Exception {
        if (system != SystemUnderTest.ETCD) {
            throw new UnsupportedOperationException(
                    "RemoteSshProvider supports ETCD for now; asked for " + system);
        }
        if (clusterSize < 1 || clusterSize > nodeIps.size()) {
            throw new IllegalArgumentException("clusterSize must be 1.." + nodeIps.size()
                    + " (provisioned nodes), got " + clusterSize);
        }
        if (!nodes.isEmpty()) {
            throw new IllegalStateException("provider already started — one cluster per instance");
        }

        // Idempotent pre-clean: a crashed earlier block must not wedge this
        // one. Removal of a nonexistent container is fine; anything else is
        // not (fail closed, never `|| true`).
        for (int i = 1; i <= clusterSize; i++) {
            removeContainer(nodeIps.get(i - 1), containerName(i));
        }

        String initialCluster = IntStream.rangeClosed(1, clusterSize)
                .mapToObj(i -> "etcd" + i + "=http://" + nodeIps.get(i - 1) + ":2380")
                .collect(Collectors.joining(","));

        log.debug("phase: deploy — starting {} etcd container(s) over SSH", clusterSize);
        for (int i = 1; i <= clusterSize; i++) {
            String ip = nodeIps.get(i - 1);
            ssh.execOrThrow(ip, SSH_PORT,
                    "docker run -d --name " + containerName(i) + " --network host "
                            + LocalDockerProvider.ETCD_IMAGE + " etcd"
                            + " --name etcd" + i
                            + " --listen-client-urls http://0.0.0.0:2379"
                            + " --advertise-client-urls http://" + ip + ":2379"
                            + " --listen-peer-urls http://0.0.0.0:2380"
                            + " --initial-advertise-peer-urls http://" + ip + ":2380"
                            + " --initial-cluster " + initialCluster
                            + " --initial-cluster-state new"
                            + " --initial-cluster-token thesis-bench");
        }

        // All containers are up before the gate polls: /health answers only
        // once the member sees quorum (the LocalDockerProvider lesson).
        for (int i = 1; i <= clusterSize; i++) {
            awaitHealthy(nodeIps.get(i - 1));
        }

        for (int i = 1; i <= clusterSize; i++) {
            String ip = nodeIps.get(i - 1);
            nodes.add(new NodeHandle(i - 1, containerName(i), ip, ip)); // real IP — F20
            endpoints.add("http://" + ip + ":2379");
        }
        log.debug("phase: wait-healthy done — endpoints {}", endpoints);
        return List.copyOf(nodes);
    }

    @Override
    public List<String> clientEndpoints() {
        if (endpoints.isEmpty()) {
            throw new IllegalStateException("cluster not started — no endpoints to hand out");
        }
        return List.copyOf(endpoints);
    }

    @Override
    public void stop() throws Exception {
        for (NodeHandle n : nodes) {
            removeContainer(n.host(), n.containerName());
        }
        nodes.clear();
        endpoints.clear();
    }

    private static String containerName(int oneBasedIndex) {
        return "thesis-" + SystemUnderTest.ETCD.containerName(oneBasedIndex);
    }

    /** rm -f, accepting only "already absent" as a benign non-zero exit. */
    private void removeContainer(String ip, String name) throws Exception {
        SshExecutor.ExecResult r = ssh.exec(ip, SSH_PORT, "docker rm -f " + name);
        if (r.exitCode() != 0 && !r.stderr().contains("No such container")) {
            throw new IllegalStateException("could not remove " + name + " on " + ip
                    + " (exit " + r.exitCode() + "): " + r.stderr());
        }
    }

    private void awaitHealthy(String ip) throws Exception {
        long deadline = System.nanoTime() + healthDeadline.toNanos();
        SshExecutor.ExecResult last = null;
        while (System.nanoTime() < deadline) {
            last = ssh.exec(ip, SSH_PORT, HEALTH_CMD);
            if (last.exitCode() == 0 && last.stdout().contains("\"health\":\"true\"")) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("etcd on " + ip + " never became healthy within "
                + healthDeadline + " — last: " + (last == null ? "never polled"
                : "exit " + last.exitCode() + ", " + last.stdout() + last.stderr()));
    }
}
