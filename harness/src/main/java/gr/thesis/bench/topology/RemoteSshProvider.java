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
 * ETCD, KRAFT and PAXOS/EPAXOS for now — every other system fails closed
 * until its block lands with its own golden (claiming them unverified
 * would be v6's sin).
 */
public final class RemoteSshProvider implements ClusterProvider {

    private static final Logger log = LoggerFactory.getLogger(RemoteSshProvider.class);
    private static final int SSH_PORT = 22;
    static final String HEALTH_CMD = "curl -sf --max-time 2 http://127.0.0.1:2379/health";
    static final String PAXI_CONFIG_PATH = "/root/thesis-paxi-config.json";
    static final String PRECLEAN_CMD =
            "docker ps -aq --filter name=thesis- | xargs -r docker rm -f";
    /** Fixed KRaft cluster id (the image auto-formats storage from it) —
     *  the SAME verified constant KraftMultiBrokerFormationTest used.
     *  Deterministic on purpose: state is container-local (no volume), so
     *  a fixed id can never collide with stale storage. */
    static final String KRAFT_CLUSTER_ID = "5L6g3nShT-eMCtK--X86s0";

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
        if (system != SystemUnderTest.ETCD && system != SystemUnderTest.KRAFT
                && system != SystemUnderTest.PAXOS && system != SystemUnderTest.EPAXOS) {
            throw new UnsupportedOperationException(
                    "RemoteSshProvider supports ETCD/KRAFT/PAXOS/EPAXOS for now; asked for " + system);
        }
        if (clusterSize < 1 || clusterSize > nodeIps.size()) {
            throw new IllegalArgumentException("clusterSize must be 1.." + nodeIps.size()
                    + " (provisioned nodes), got " + clusterSize);
        }
        if (!nodes.isEmpty()) {
            throw new IllegalStateException("provider already started — one cluster per instance");
        }

        // Idempotent pre-clean, on EVERY provisioned node and for ANY
        // thesis-* container (F29): a crashed earlier block may have left a
        // DIFFERENT system's containers behind, and a D8 size-down run
        // (7→5/3) leaves stale members on nodes outside the new cluster —
        // either way a stale SUT eats CPU on a measurement box and sprays
        // peer traffic at the new cluster. Same semantics as
        // LocalDockerProvider.removeLeftovers(). Empty match ⇒ xargs -r
        // runs nothing ⇒ exit 0; a real removal failure surfaces as a
        // non-zero exit (fail closed, never `|| true`).
        for (String ip : nodeIps) {
            ssh.execOrThrow(ip, SSH_PORT, PRECLEAN_CMD);
        }

        if (system == SystemUnderTest.ETCD) {
            startEtcd(clusterSize);
        } else if (system == SystemUnderTest.KRAFT) {
            startKraft(clusterSize);
        } else {
            startPaxi(system, clusterSize);
        }
        log.debug("phase: wait-healthy done — endpoints {}", endpoints);
        return List.copyOf(nodes);
    }

    private void startEtcd(int clusterSize) throws Exception {
        String initialCluster = IntStream.rangeClosed(1, clusterSize)
                .mapToObj(i -> "etcd" + i + "=http://" + nodeIps.get(i - 1) + ":2380")
                .collect(Collectors.joining(","));

        log.debug("phase: deploy — starting {} etcd container(s) over SSH", clusterSize);
        for (int i = 1; i <= clusterSize; i++) {
            String ip = nodeIps.get(i - 1);
            ssh.execOrThrow(ip, SSH_PORT,
                    "docker run -d --name " + containerName(SystemUnderTest.ETCD, i) + " --network host "
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
            awaitEtcdHealthy(nodeIps.get(i - 1));
        }
        for (int i = 1; i <= clusterSize; i++) {
            String ip = nodeIps.get(i - 1);
            nodes.add(new NodeHandle(i - 1, containerName(SystemUnderTest.ETCD, i), ip, ip));
            endpoints.add("http://" + ip + ":2379");
        }
    }

    /**
     * Paxi (Paxos/EPaxos) over SSH — one binary, {@code -algorithm} from the
     * enum (D4). Deltas from etcd, all golden-reviewable:
     *  - config.json is generated with REAL private IPs (address tcp:1735,
     *    http_address http:8080) and written to the node with a
     *    single-quoted printf (its own quotes are double — no base64), then
     *    bind-mounted to /config.json (paxi reads config.json from cwd=/).
     *  - readiness is a COMMITTED PROBE WRITE, not a health port: election
     *    is lazy and there is no /health (source-verified). {@code -adaptive}
     *    stays default (F26: leader_kill wedges — the honest no-failure-
     *    detector result).
     */
    private void startPaxi(SystemUnderTest system, int clusterSize) throws Exception {
        String algorithm = system == SystemUnderTest.EPAXOS ? "epaxos" : "paxos";
        String config = paxiConfigJson(clusterSize);

        log.debug("phase: deploy — starting {} {} container(s) over SSH", clusterSize, system);
        for (int i = 1; i <= clusterSize; i++) {
            String ip = nodeIps.get(i - 1);
            ssh.execOrThrow(ip, SSH_PORT,
                    "printf '%s' '" + config + "' > " + PAXI_CONFIG_PATH);
        }
        for (int i = 1; i <= clusterSize; i++) {
            String ip = nodeIps.get(i - 1);
            ssh.execOrThrow(ip, SSH_PORT,
                    "docker run -d --name " + containerName(system, i) + " --network host"
                            + " -v " + PAXI_CONFIG_PATH + ":/config.json "
                            + LocalDockerProvider.PAXI_IMAGE
                            + " -id 1." + i + " -algorithm " + algorithm);
        }
        // Quorum gate: retry a committed probe write on node1 until it 200s
        // (election is lazy — the first request forms the leader).
        awaitPaxiProbeWrite(nodeIps.get(0));
        for (int i = 1; i <= clusterSize; i++) {
            String ip = nodeIps.get(i - 1);
            nodes.add(new NodeHandle(i - 1, containerName(system, i), ip, ip));
            endpoints.add("http://" + ip + ":8080");
        }
    }

    /**
     * KRaft over SSH (P3.3d-kraft) — the env-var contract VERIFIED BY
     * EXECUTION in KraftMultiBrokerFormationTest (2026-07-17: 3-broker
     * quorum, acks=all under min.insync.replicas=2, Isr=3), with the
     * remote deltas: voters and advertised listeners carry REAL private
     * IPs (F20), --network host binds :9092/:9093 natively (D2), and no
     * volume — the image auto-formats storage from KAFKA_CLUSTER_ID, so
     * cluster state dies with the container (byte-fresh clusters).
     * Readiness: per-node "Kafka Server started" in docker logs (the
     * local test's wait strategy), then the quorum oracle on node1 —
     * kafka-broker-api-versions prints one "(id: N)" header per JOINED
     * broker; the gate requires exactly clusterSize of them (2 of 3
     * would serve acks=all silently degraded — refuse it). The bench
     * topic is KafkaDriver.connect()'s job, not the provider's.
     */
    private void startKraft(int clusterSize) throws Exception {
        String voters = IntStream.rangeClosed(1, clusterSize)
                .mapToObj(i -> i + "@" + nodeIps.get(i - 1) + ":9093")
                .collect(Collectors.joining(","));
        // Internal-topic RF follows the cluster size, like KafkaDriver's
        // bench topic (RF=min(3,N)); min-ISR 2 only makes sense at RF 3.
        int rf = Math.min(3, clusterSize);
        int txnMinIsr = rf >= 3 ? 2 : 1;

        log.debug("phase: deploy — starting {} KRaft container(s) over SSH", clusterSize);
        for (int i = 1; i <= clusterSize; i++) {
            String ip = nodeIps.get(i - 1);
            ssh.execOrThrow(ip, SSH_PORT,
                    "docker run -d --name " + containerName(SystemUnderTest.KRAFT, i) + " --network host"
                            + " -e KAFKA_NODE_ID=" + i
                            + " -e KAFKA_PROCESS_ROLES=broker,controller"
                            + " -e KAFKA_CONTROLLER_QUORUM_VOTERS=" + voters
                            + " -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093"
                            + " -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://" + ip + ":9092"
                            + " -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER"
                            + " -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT"
                            + " -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
                            + " -e KAFKA_CLUSTER_ID=" + KRAFT_CLUSTER_ID
                            + " -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=" + rf
                            + " -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=" + rf
                            + " -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=" + txnMinIsr
                            + " " + LocalDockerProvider.KAFKA_IMAGE);
        }
        for (int i = 1; i <= clusterSize; i++) {
            awaitKraftStarted(nodeIps.get(i - 1), containerName(SystemUnderTest.KRAFT, i));
        }
        awaitKraftQuorum(nodeIps.get(0), containerName(SystemUnderTest.KRAFT, 1), clusterSize);
        for (int i = 1; i <= clusterSize; i++) {
            String ip = nodeIps.get(i - 1);
            nodes.add(new NodeHandle(i - 1, containerName(SystemUnderTest.KRAFT, i), ip, ip));
            // BARE host:port — the Kafka bootstrap contract (no scheme).
            endpoints.add(ip + ":9092");
        }
    }

    /** Per-node started gate: the same log line the verified local test's
     *  wait strategy keyed on. grep -q ⇒ the exit code IS the signal. */
    private void awaitKraftStarted(String ip, String container) throws Exception {
        String probe = "docker logs " + container + " 2>&1 | grep -q 'Kafka Server started'";
        long deadline = System.nanoTime() + healthDeadline.toNanos();
        SshExecutor.ExecResult last = null;
        while (System.nanoTime() < deadline) {
            last = ssh.exec(ip, SSH_PORT, probe);
            if (last.exitCode() == 0) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException(container + " on " + ip + " never logged"
                + " 'Kafka Server started' within " + healthDeadline + " — last: "
                + (last == null ? "never polled" : "exit " + last.exitCode() + ", " + last.stderr()));
    }

    /** Cluster-formed gate: count api-versions' per-broker "(id:" header
     *  lines (the oracle KraftMultiBrokerFormationTest verified) and
     *  require exactly clusterSize joined brokers. */
    private void awaitKraftQuorum(String ip, String container, int clusterSize) throws Exception {
        String probe = "docker exec " + container
                + " /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server " + ip + ":9092";
        long deadline = System.nanoTime() + healthDeadline.toNanos();
        long seen = -1;
        while (System.nanoTime() < deadline) {
            SshExecutor.ExecResult r = ssh.exec(ip, SSH_PORT, probe);
            if (r.exitCode() == 0) {
                seen = r.stdout().lines().filter(l -> l.contains("(id:")).count();
                if (seen == clusterSize) return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("KRaft cluster on " + ip + " never formed within "
                + healthDeadline + ": saw " + (seen < 0 ? "no broker list" : seen + " broker(s)")
                + " joined, need " + clusterSize);
    }

    private String paxiConfigJson(int clusterSize) {
        String addr = IntStream.rangeClosed(1, clusterSize)
                .mapToObj(i -> "\"1." + i + "\": \"tcp://" + nodeIps.get(i - 1) + ":1735\"")
                .collect(Collectors.joining(", "));
        String http = IntStream.rangeClosed(1, clusterSize)
                .mapToObj(i -> "\"1." + i + "\": \"http://" + nodeIps.get(i - 1) + ":8080\"")
                .collect(Collectors.joining(", "));
        return "{\"address\": {" + addr + "}, \"http_address\": {" + http + "}}";
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

    private static String containerName(SystemUnderTest system, int oneBasedIndex) {
        return "thesis-" + system.containerName(oneBasedIndex);
    }

    /** rm -f, accepting only "already absent" as a benign non-zero exit. */
    private void removeContainer(String ip, String name) throws Exception {
        SshExecutor.ExecResult r = ssh.exec(ip, SSH_PORT, "docker rm -f " + name);
        if (r.exitCode() != 0 && !r.stderr().contains("No such container")) {
            throw new IllegalStateException("could not remove " + name + " on " + ip
                    + " (exit " + r.exitCode() + "): " + r.stderr());
        }
    }

    private void awaitEtcdHealthy(String ip) throws Exception {
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

    /** Paxi quorum gate: a committed probe write on node1 (curl -sf exit 0 =
     *  HTTP 200 = committed). Retried because election is lazy and peers may
     *  still be forming the mesh. */
    private void awaitPaxiProbeWrite(String ip) throws Exception {
        String probe = "curl -sf --max-time 2 -X PUT --data-binary probeval http://" + ip + ":8080/1";
        long deadline = System.nanoTime() + healthDeadline.toNanos();
        SshExecutor.ExecResult last = null;
        while (System.nanoTime() < deadline) {
            last = ssh.exec(ip, SSH_PORT, probe);
            if (last.exitCode() == 0) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException("paxi on " + ip + " failed its probe-write quorum gate within "
                + healthDeadline + " — last: " + (last == null ? "never polled"
                : "exit " + last.exitCode() + ", " + last.stderr()));
    }
}
