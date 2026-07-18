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
 * Failed-start policy (F35, deliberate): {@code nodes} is registered only
 * after every readiness gate passes, so a mid-start failure leaves the
 * just-started containers RUNNING on the VMs — stop() removes nothing.
 * That is evidence preservation: the operator debugs the failure from the
 * still-alive containers' `docker logs`, and the F29 pre-clean sweeps them
 * on the next start(), so stationarity is never at risk. (KAFKA_ZK's
 * ensemble is the one exception — aux containers register as they start,
 * so a failed broker phase still tears the ZK ensemble down.)
 *
 * All seven systems are served, each behind its own reviewed golden.
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
    static final String TM_HOME_DIR = "/root/thesis-tm";
    static final String TM_KEYGEN_DIR = "/root/thesis-tm-testnet";
    static final String HS_DIR = "/root/thesis-hs";
    static final String HS_KEYGEN_DIR = "/root/thesis-hs-keys";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final java.util.regex.Pattern TM_HEIGHT =
            java.util.regex.Pattern.compile("\"latest_block_height\":\"(\\d+)\"");

    private final SshExecutor ssh;
    private final List<String> nodeIps; // node order — index IS the node index
    private final Duration healthDeadline;
    private final List<NodeHandle> nodes = new ArrayList<>();
    private final List<String> endpoints = new ArrayList<>();
    /** Auxiliary containers (KAFKA_ZK's ensemble) as (ip, name) — removed
     *  by stop() AFTER the member containers (reverse dependency order). */
    private final List<String[]> auxContainers = new ArrayList<>();

    public RemoteSshProvider(SshExecutor ssh, List<String> nodePrivateIps,
                             Duration healthDeadline) {
        if (nodePrivateIps.isEmpty()) throw new IllegalArgumentException("no node IPs");
        this.ssh = ssh;
        this.nodeIps = List.copyOf(nodePrivateIps);
        this.healthDeadline = healthDeadline;
    }

    @Override
    public List<NodeHandle> start(SystemUnderTest system, int clusterSize) throws Exception {
        if (system == SystemUnderTest.HOTSTUFF && clusterSize != 4) {
            throw new UnsupportedOperationException(
                    "HOTSTUFF runs the thesis shape of 4 (D9: n=3f+1, f=1); asked for " + clusterSize);
        }
        if (system == SystemUnderTest.TENDERMINT && clusterSize != 4) {
            throw new UnsupportedOperationException(
                    "TENDERMINT runs the thesis shape of 4 (D9: n=3f+1, f=1); asked for " + clusterSize);
        }
        if (system == SystemUnderTest.KAFKA_ZK && clusterSize != 3) {
            throw new UnsupportedOperationException(
                    "KAFKA_ZK runs the thesis shape of 3 colocated zk+broker pairs (D10); asked for "
                            + clusterSize);
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
        } else if (system == SystemUnderTest.KAFKA_ZK) {
            startKafkaZk(clusterSize);
        } else if (system == SystemUnderTest.TENDERMINT) {
            startTendermint(clusterSize);
        } else if (system == SystemUnderTest.HOTSTUFF) {
            startHotStuff(clusterSize);
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
        requireImageOnNodes(LocalDockerProvider.PAXI_IMAGE, clusterSize);
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
            awaitContainerLog(nodeIps.get(i - 1), containerName(SystemUnderTest.KRAFT, i),
                    "Kafka Server started");
        }
        awaitKafkaQuorum(nodeIps.get(0), containerName(SystemUnderTest.KRAFT, 1), clusterSize);
        for (int i = 1; i <= clusterSize; i++) {
            String ip = nodeIps.get(i - 1);
            nodes.add(new NodeHandle(i - 1, containerName(SystemUnderTest.KRAFT, i), ip, ip));
            // BARE host:port — the Kafka bootstrap contract (no scheme).
            endpoints.add(ip + ":9092");
        }
    }

    /** Per-node started gate: a mode-specific log line, retried. KRaft
     *  passes "Kafka Server started" (KafkaRaftServer's line); KAFKA_ZK
     *  passes "started (kafka.server.KafkaServer)" — the ZK-MODE line, so
     *  a wrong-mode broker can never pass its gate. grep -q ⇒ the exit
     *  code IS the signal. */
    private void awaitContainerLog(String ip, String container, String needle) throws Exception {
        String probe = "docker logs " + container + " 2>&1 | grep -q '" + needle + "'";
        long deadline = System.nanoTime() + healthDeadline.toNanos();
        SshExecutor.ExecResult last = null;
        while (System.nanoTime() < deadline) {
            last = ssh.exec(ip, SSH_PORT, probe);
            if (last.exitCode() == 0) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException(container + " on " + ip + " never logged"
                + " '" + needle + "' within " + healthDeadline + " — last: "
                + (last == null ? "never polled" : "exit " + last.exitCode() + ", " + last.stderr()));
    }

    /**
     * Kafka+ZooKeeper over SSH (P3.3d-kafka_zk step 2) — the D10 colocated
     * shape VERIFIED BY EXECUTION in KafkaZkColocatedFormationTest
     * (2026-07-17): VM i hosts thesis-zk<i> AND thesis-k<i>, mirroring
     * KRaft's combined controller+broker on the same 2 vCPUs (symmetric
     * contention BY DESIGN — the F6 caption states it). The broker
     * BYPASSES the image entrypoint (it refuses ZK mode — probed): a
     * printf'd server.properties + kafka-server-start.sh, on the SAME
     * image digest as KRaft — identical binaries, only coordination
     * differs. ZK serves Prometheus on :7000 (ZOO_CFG_EXTRA), which is
     * both the campaign's scrape target and the per-node readiness gate.
     */
    private void startKafkaZk(int clusterSize) throws Exception {
        String zooServers = IntStream.rangeClosed(1, clusterSize)
                .mapToObj(i -> "server." + i + "=" + nodeIps.get(i - 1) + ":2888:3888;2181")
                .collect(Collectors.joining(" "));
        String zkConnect = IntStream.rangeClosed(1, clusterSize)
                .mapToObj(i -> nodeIps.get(i - 1) + ":2181")
                .collect(Collectors.joining(","));

        log.debug("phase: deploy — starting {} zk + {} broker container(s) over SSH",
                clusterSize, clusterSize);
        for (int i = 1; i <= clusterSize; i++) {
            String ip = nodeIps.get(i - 1);
            ssh.execOrThrow(ip, SSH_PORT,
                    "docker run -d --name thesis-zk" + i + " --network host"
                            + " -e ZOO_MY_ID=" + i
                            + " -e 'ZOO_SERVERS=" + zooServers + "'"
                            + " -e 'ZOO_CFG_EXTRA=metricsProvider.className="
                            + "org.apache.zookeeper.metrics.prometheus.PrometheusMetricsProvider"
                            + " metricsProvider.httpPort=7000'"
                            + " " + LocalDockerProvider.ZOOKEEPER_IMAGE);
            auxContainers.add(new String[]{ip, "thesis-zk" + i});
        }
        for (int i = 1; i <= clusterSize; i++) {
            awaitZkMetricsUp(nodeIps.get(i - 1));
        }
        for (int i = 1; i <= clusterSize; i++) {
            String ip = nodeIps.get(i - 1);
            ssh.execOrThrow(ip, SSH_PORT,
                    "docker run -d --name " + containerName(SystemUnderTest.KAFKA_ZK, i)
                            + " --network host --entrypoint sh " + LocalDockerProvider.KAFKA_IMAGE
                            + " -c \"printf '%s\\n'"
                            + " 'broker.id=" + i + "'"
                            + " 'zookeeper.connect=" + zkConnect + "'"
                            + " 'listeners=PLAINTEXT://:9092'"
                            + " 'advertised.listeners=PLAINTEXT://" + ip + ":9092'"
                            + " 'offsets.topic.replication.factor=3'"
                            + " 'transaction.state.log.replication.factor=3'"
                            + " 'transaction.state.log.min.isr=2'"
                            + " 'log.dirs=/tmp/kafka-logs'"
                            + " > /tmp/server.properties"
                            + " && /opt/kafka/bin/kafka-server-start.sh /tmp/server.properties\"");
        }
        for (int i = 1; i <= clusterSize; i++) {
            awaitContainerLog(nodeIps.get(i - 1), containerName(SystemUnderTest.KAFKA_ZK, i),
                    "started (kafka.server.KafkaServer)");
        }
        awaitKafkaQuorum(nodeIps.get(0), containerName(SystemUnderTest.KAFKA_ZK, 1), clusterSize);
        for (int i = 1; i <= clusterSize; i++) {
            String ip = nodeIps.get(i - 1);
            // The handle IS the broker: faults kill it while the colocated
            // ZK survives — the D10 comparison's semantic.
            nodes.add(new NodeHandle(i - 1, containerName(SystemUnderTest.KAFKA_ZK, i), ip, ip));
            endpoints.add(ip + ":9092");
        }
    }

    /** ZK per-node gate: the :7000 Prometheus endpoint answering doubles
     *  as the campaign's scrape-target proof (D10/P4.3). */
    private void awaitZkMetricsUp(String ip) throws Exception {
        String probe = "curl -sf --max-time 2 http://127.0.0.1:7000/metrics";
        long deadline = System.nanoTime() + healthDeadline.toNanos();
        SshExecutor.ExecResult last = null;
        while (System.nanoTime() < deadline) {
            last = ssh.exec(ip, SSH_PORT, probe);
            if (last.exitCode() == 0) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException("zookeeper on " + ip + " never served :7000/metrics within "
                + healthDeadline + " — last: " + (last == null ? "never polled"
                : "exit " + last.exitCode() + ", " + last.stderr()));
    }

    /** Cluster-formed gate for BOTH Kafka modes: count api-versions'
     *  per-broker "(id:" header lines (the oracle both formation tests
     *  verified) and require exactly clusterSize joined brokers. */
    private void awaitKafkaQuorum(String ip, String container, int clusterSize) throws Exception {
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
        throw new IllegalStateException("Kafka cluster on " + ip + " never formed within "
                + healthDeadline + ": saw " + (seen < 0 ? "no broker list" : seen + " broker(s)")
                + " joined, need " + clusterSize);
    }

    /**
     * CometBFT over SSH (P3.3d-cometbft step 2) — the DISTRIBUTION-shaped
     * recipe VERIFIED BY EXECUTION in CometBftMultiValidatorFormationTest
     * (2026-07-17): `cometbft testnet` as a one-shot keygen on node1, four
     * small JSONs distributed per node (genesis, both keys, and
     * data/priv_validator_state.json — init's FilePV loader REQUIRES it
     * once the key is pre-placed), `cometbft init` filling default config
     * around the pre-placed files, peers via the --p2p.persistent_peers
     * CLI flag EXCLUDING self, node ids via -e CMTHOME one-shots (the
     * --home flag is IGNORED by this image — probed). Each cat'ed file is
     * COMPACTED to single-line JSON before the printf: that is both the
     * single-quote safety proof (JSON contains none) and what keeps every
     * golden line one line. Readiness: /health per node, then /status on
     * node1 until latest_block_height >= 1 — a height only >2/3 of the
     * validators can produce, so it IS the quorum proof.
     */
    private void startTendermint(int clusterSize) throws Exception {
        String img = LocalDockerProvider.COMETBFT_IMAGE;
        // Fresh state FIRST: a stale data/ dir would resurrect a previous
        // chain against a fresh genesis. rm -rf is exit-0 when absent.
        ssh.execOrThrow(nodeIps.get(0), SSH_PORT, "rm -rf " + TM_HOME_DIR + " " + TM_KEYGEN_DIR);
        for (int i = 2; i <= clusterSize; i++) {
            ssh.execOrThrow(nodeIps.get(i - 1), SSH_PORT, "rm -rf " + TM_HOME_DIR);
        }
        String hostnames = IntStream.range(0, clusterSize)
                .mapToObj(i -> " --hostname " + nodeIps.get(i))
                .collect(Collectors.joining());
        log.debug("phase: keygen — cometbft testnet one-shot on {}", nodeIps.get(0));
        ssh.execOrThrow(nodeIps.get(0), SSH_PORT,
                "docker run --rm -v " + TM_KEYGEN_DIR + ":/testnet " + img
                        + " testnet --v " + clusterSize + " --o /testnet" + hostnames);

        String genesis = compactJson(catOnKeygenNode("node0/config/genesis.json"), "genesis.json");
        String[] privKey = new String[clusterSize];
        String[] nodeKey = new String[clusterSize];
        String[] pvState = new String[clusterSize];
        String[] ids = new String[clusterSize];
        for (int i = 0; i < clusterSize; i++) {
            privKey[i] = compactJson(catOnKeygenNode("node" + i + "/config/priv_validator_key.json"),
                    "priv_validator_key.json");
            nodeKey[i] = compactJson(catOnKeygenNode("node" + i + "/config/node_key.json"),
                    "node_key.json");
            pvState[i] = compactJson(catOnKeygenNode("node" + i + "/data/priv_validator_state.json"),
                    "priv_validator_state.json");
        }
        for (int i = 0; i < clusterSize; i++) {
            ids[i] = ssh.execOrThrow(nodeIps.get(0), SSH_PORT,
                    "docker run --rm -v " + TM_KEYGEN_DIR + ":/testnet -e CMTHOME=/testnet/node" + i
                            + " " + img + " show-node-id").trim();
        }

        log.debug("phase: deploy — distributing files + starting {} validators over SSH", clusterSize);
        for (int i = 0; i < clusterSize; i++) {
            String ip = nodeIps.get(i);
            ssh.execOrThrow(ip, SSH_PORT,
                    "mkdir -p " + TM_HOME_DIR + "/config " + TM_HOME_DIR + "/data");
            ssh.execOrThrow(ip, SSH_PORT,
                    "printf '%s' '" + genesis + "' > " + TM_HOME_DIR + "/config/genesis.json");
            ssh.execOrThrow(ip, SSH_PORT,
                    "printf '%s' '" + privKey[i] + "' > " + TM_HOME_DIR + "/config/priv_validator_key.json");
            ssh.execOrThrow(ip, SSH_PORT,
                    "printf '%s' '" + nodeKey[i] + "' > " + TM_HOME_DIR + "/config/node_key.json");
            ssh.execOrThrow(ip, SSH_PORT,
                    "printf '%s' '" + pvState[i] + "' > " + TM_HOME_DIR + "/data/priv_validator_state.json");
        }
        for (int i = 0; i < clusterSize; i++) {
            String ip = nodeIps.get(i);
            StringBuilder peers = new StringBuilder();
            for (int j = 0; j < clusterSize; j++) {
                if (j == i) continue; // a self-entry is refused by the dialer
                if (peers.length() > 0) peers.append(',');
                peers.append(ids[j]).append('@').append(nodeIps.get(j)).append(":26656");
            }
            ssh.execOrThrow(ip, SSH_PORT,
                    "docker run -d --name " + containerName(SystemUnderTest.TENDERMINT, i + 1)
                            + " --network host -v " + TM_HOME_DIR + ":/cometbft --entrypoint sh " + img
                            + " -c \"cometbft init"
                            + " && sed -i 's/^max_subscription_clients = .*/max_subscription_clients = 2000/'"
                            + " /cometbft/config/config.toml"
                            + " && sed -i 's/^addr_book_strict = .*/addr_book_strict = false/'"
                            + " /cometbft/config/config.toml"
                            + " && cometbft start --proxy_app=kvstore"
                            + " --rpc.laddr=tcp://0.0.0.0:26657"
                            + " --p2p.laddr=tcp://0.0.0.0:26656"
                            + " --p2p.persistent_peers=" + peers + "\"");
        }
        for (int i = 0; i < clusterSize; i++) {
            awaitTmHealthy(nodeIps.get(i));
        }
        awaitTmCommittedHeight(nodeIps.get(0));
        for (int i = 0; i < clusterSize; i++) {
            String ip = nodeIps.get(i);
            nodes.add(new NodeHandle(i, containerName(SystemUnderTest.TENDERMINT, i + 1), ip, ip));
            endpoints.add("http://" + ip + ":26657"); // CometBftDriver RPC-base contract
        }
    }

    private String catOnKeygenNode(String relPath) throws Exception {
        return ssh.execOrThrow(nodeIps.get(0), SSH_PORT, "cat " + TM_KEYGEN_DIR + "/" + relPath);
    }

    /** Single-line re-serialization of a keygen artifact — the printf
     *  single-quote safety proof (JSON contains none) and the one-golden-
     *  line guarantee. Unparseable output fails closed naming the file. */
    private static String compactJson(String raw, String what) {
        try {
            return JSON.readTree(raw).toString();
        } catch (Exception e) {
            throw new IllegalStateException("unparseable " + what + " from the cometbft keygen: "
                    + e.getMessage(), e);
        }
    }

    private void awaitTmHealthy(String ip) throws Exception {
        String probe = "curl -sf --max-time 2 http://127.0.0.1:26657/health";
        long deadline = System.nanoTime() + healthDeadline.toNanos();
        SshExecutor.ExecResult last = null;
        while (System.nanoTime() < deadline) {
            last = ssh.exec(ip, SSH_PORT, probe);
            if (last.exitCode() == 0) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException("cometbft RPC on " + ip + " never answered /health within "
                + healthDeadline + " — last: " + (last == null ? "never polled"
                : "exit " + last.exitCode() + ", " + last.stderr()));
    }

    /** Quorum gate: latest_block_height >= 1 on node1 — only >2/3 of the
     *  validators signing can produce a height, so RPC-up alone (health)
     *  never passes a cluster that cannot commit. */
    private void awaitTmCommittedHeight(String ip) throws Exception {
        String probe = "curl -sf --max-time 2 http://127.0.0.1:26657/status";
        long deadline = System.nanoTime() + healthDeadline.toNanos();
        String lastSeen = "never polled";
        while (System.nanoTime() < deadline) {
            SshExecutor.ExecResult r = ssh.exec(ip, SSH_PORT, probe);
            if (r.exitCode() == 0) {
                java.util.regex.Matcher m = TM_HEIGHT.matcher(r.stdout());
                lastSeen = m.find() ? "height " + m.group(1) : "no height in: " + r.stdout();
                if (m.reset(r.stdout()).find() && Long.parseLong(m.group(1)) >= 1) return;
            } else {
                lastSeen = "exit " + r.exitCode() + ", " + r.stderr();
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("cometbft on " + ip + " never committed height >= 1 within "
                + healthDeadline + " (fewer than 2/3 of validators signing?) — last: " + lastSeen);
    }

    /**
     * HotStuff over SSH (P3.3d-hotstuff) — the DISTRIBUTION shape VERIFIED
     * BY EXECUTION in HotStuffMultiNodeFormationTest (2026-07-18, 21.5 s
     * green: 4 nodes, client traffic committed through BFT consensus, every
     * replica logging logs.py's own commit-parse target). Recipe:
     *  - `node keys` one-shots on node1 generate the four keypairs; each
     *    key file is COMPACTED to single-line JSON (base64 material — no
     *    single quotes, the printf safety proof) and distributed.
     *  - committee.json = fab config.py's exact shape with REAL private IPs
     *    and THREE ports per node: consensus :26000, transactions :26001
     *    (the upstream client's target), mempool :26002.
     *  - one `node -vv run` container per VM; -vv is the log level whose
     *    benchmark-feature lines ARE HotStuff's metrics (P2.5/P4.5); the
     *    store is container-local so state dies with the container.
     *  - readiness is BOOT-level ("successfully booted" per node), not
     *    commit-level: HotStuff commits only under traffic and its ONLY
     *    traffic source is the upstream client — which itself refuses to
     *    start until every --nodes address accepts connections, and whose
     *    SUMMARY analysis fails closed on a run that committed nothing.
     *    The quorum proof therefore rides the measured run; the golden
     *    header states this honestly.
     */
    private void startHotStuff(int clusterSize) throws Exception {
        String img = LocalDockerProvider.HOTSTUFF_IMAGE;
        requireImageOnNodes(img, clusterSize);
        // Fresh config dirs FIRST (stale keys + a fresh committee would be
        // an unbootable mismatch); the store needs no rm — it is container-
        // local and died with the pre-cleaned container.
        ssh.execOrThrow(nodeIps.get(0), SSH_PORT, "rm -rf " + HS_DIR + " " + HS_KEYGEN_DIR);
        for (int i = 2; i <= clusterSize; i++) {
            ssh.execOrThrow(nodeIps.get(i - 1), SSH_PORT, "rm -rf " + HS_DIR);
        }
        log.debug("phase: keygen — {} `node keys` one-shots on {}", clusterSize, nodeIps.get(0));
        for (int i = 1; i <= clusterSize; i++) {
            ssh.execOrThrow(nodeIps.get(0), SSH_PORT,
                    "docker run --rm -v " + HS_KEYGEN_DIR + ":/keys " + img
                            + " node keys --filename /keys/node" + i + ".json");
        }
        String[] keyJson = new String[clusterSize];
        String[] pubKey = new String[clusterSize];
        for (int i = 0; i < clusterSize; i++) {
            keyJson[i] = compactJson(
                    ssh.execOrThrow(nodeIps.get(0), SSH_PORT,
                            "cat " + HS_KEYGEN_DIR + "/node" + (i + 1) + ".json"),
                    "node" + (i + 1) + ".json");
            com.fasterxml.jackson.databind.JsonNode name;
            try {
                name = JSON.readTree(keyJson[i]).path("name");
            } catch (Exception e) {
                throw new IllegalStateException("unreadable hotstuff key file node"
                        + (i + 1) + ".json", e);
            }
            if (name.isMissingNode() || name.asText().isEmpty()) {
                throw new IllegalStateException("hotstuff key file node" + (i + 1)
                        + ".json carries no 'name' (public key) — keygen contract broken");
            }
            pubKey[i] = name.asText();
        }
        String committee = hotStuffCommitteeJson(pubKey, clusterSize);

        log.debug("phase: deploy — distributing files + starting {} hotstuff nodes over SSH",
                clusterSize);
        for (int i = 0; i < clusterSize; i++) {
            String ip = nodeIps.get(i);
            ssh.execOrThrow(ip, SSH_PORT, "mkdir -p " + HS_DIR);
            ssh.execOrThrow(ip, SSH_PORT,
                    "printf '%s' '" + keyJson[i] + "' > " + HS_DIR + "/node.json");
            ssh.execOrThrow(ip, SSH_PORT,
                    "printf '%s' '" + committee + "' > " + HS_DIR + "/committee.json");
        }
        for (int i = 0; i < clusterSize; i++) {
            String ip = nodeIps.get(i);
            ssh.execOrThrow(ip, SSH_PORT,
                    "docker run -d --name " + containerName(SystemUnderTest.HOTSTUFF, i + 1)
                            + " --network host -v " + HS_DIR + ":/hs " + img
                            + " node -vv run --keys /hs/node.json --committee /hs/committee.json"
                            + " --store /store");
        }
        for (int i = 0; i < clusterSize; i++) {
            awaitContainerLog(nodeIps.get(i), containerName(SystemUnderTest.HOTSTUFF, i + 1),
                    "successfully booted");
        }
        for (int i = 0; i < clusterSize; i++) {
            String ip = nodeIps.get(i);
            nodes.add(new NodeHandle(i, containerName(SystemUnderTest.HOTSTUFF, i + 1), ip, ip));
            // BARE host:port of the TRANSACTIONS address — what the upstream
            // client takes; the harness never drives HotStuff through a
            // ConsensusDriver (the documented measurement boundary).
            endpoints.add(ip + ":26001");
        }
    }

    /** fab config.py's committee shape, single-line, insertion-ordered —
     *  the same builder the formation test verified by execution. */
    private String hotStuffCommitteeJson(String[] pubKey, int clusterSize) {
        var consensusAuth = JSON.createObjectNode();
        var mempoolAuth = JSON.createObjectNode();
        for (int i = 0; i < clusterSize; i++) {
            String ip = nodeIps.get(i);
            var c = JSON.createObjectNode();
            c.put("name", pubKey[i]);
            c.put("stake", 1);
            c.put("address", ip + ":26000");
            consensusAuth.set(pubKey[i], c);
            var m = JSON.createObjectNode();
            m.put("name", pubKey[i]);
            m.put("stake", 1);
            m.put("transactions_address", ip + ":26001");
            m.put("mempool_address", ip + ":26002");
            mempoolAuth.set(pubKey[i], m);
        }
        var consensus = JSON.createObjectNode();
        consensus.set("authorities", consensusAuth);
        consensus.put("epoch", 1);
        var mempool = JSON.createObjectNode();
        mempool.set("authorities", mempoolAuth);
        mempool.put("epoch", 1);
        var root = JSON.createObjectNode();
        root.set("consensus", consensus);
        root.set("mempool", mempool);
        return root.toString();
    }

    /** Local-built images (paxi, hotstuff) exist in NO registry (F33): a
     *  bare `docker run` on a VM would try to pull and fail at first
     *  contact on a billed box. Gate on presence per member node, failing
     *  closed with the shipping command in the message. */
    private void requireImageOnNodes(String image, int clusterSize) throws Exception {
        for (int i = 1; i <= clusterSize; i++) {
            String ip = nodeIps.get(i - 1);
            SshExecutor.ExecResult r = ssh.exec(ip, SSH_PORT,
                    "docker image inspect -f {{.Id}} " + image);
            if (r.exitCode() != 0) {
                throw new IllegalStateException("image " + image + " is not on node " + ip
                        + " — ship it from the laptop: docker save " + image
                        + " | ssh root@" + ip + " docker load");
            }
        }
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
        // Ensemble/auxiliary containers go AFTER the members that depend
        // on them; a survivor would leak into the next block on these VMs.
        for (String[] aux : auxContainers) {
            removeContainer(aux[0], aux[1]);
        }
        nodes.clear();
        endpoints.clear();
        auxContainers.clear();
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
