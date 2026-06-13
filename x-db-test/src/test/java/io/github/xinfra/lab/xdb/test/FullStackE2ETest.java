package io.github.xinfra.lab.xdb.test;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.github.xinfra.lab.xdb.ddl.DDLExecutor;
import io.github.xinfra.lab.xdb.ddl.DDLOwnerManager;
import io.github.xinfra.lab.xdb.ddl.DDLWorker;
import io.github.xinfra.lab.xdb.ddl.KVDDLJobQueue;
import io.github.xinfra.lab.xdb.ddl.SchemaChangeExecutor;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.meta.KVMetaStore;
import io.github.xinfra.lab.xdb.server.XDBConfig;
import io.github.xinfra.lab.xdb.server.XDBServer;
import io.github.xinfra.lab.xdb.session.InfoSchemaHolder;
import io.github.xinfra.lab.xdb.session.SessionManagerImpl;
import io.github.xinfra.lab.xdb.session.TransactionManager;
import io.github.xinfra.lab.xkv.client.XKvClient;
import io.github.xinfra.lab.xkv.client.TxnClient;
import io.github.xinfra.lab.xkv.client.config.ClientConfig;
import io.github.xinfra.lab.xkv.client.raw.RawKvClient;
import io.github.xinfra.lab.xkv.client.txn.Transaction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.ServerSocket;
import java.sql.*;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full-stack E2E test: Testcontainers (PD + KV) → x-db server → JDBC.
 *
 * <p>Validates the complete pipeline: JDBC → MySQL protocol → SQL engine
 * → x-kv transactions → Raft consensus → RocksDB.
 *
 * <p>Networking: PD advertises {@code pd1:2379} (Docker network alias).
 * Fixed port mappings (2379→2379, 20160→20160) ensure the same port is
 * reachable on both the Docker network and the host. The JVM resolves
 * {@code pd1} to {@code 127.0.0.1} via {@code -Djdk.net.hosts.file}
 * (configured in pom.xml surefire plugin).
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("isDockerAvailable")
class FullStackE2ETest {

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(FullStackE2ETest.class);

    private static final String PD_IMAGE = "ghcr.io/x-infra-lab/x-pd:latest";
    private static final String KV_IMAGE = "ghcr.io/x-infra-lab/x-kv:latest";

    private static final int PD_PORT = 2379;
    private static final int PD_METRICS_PORT = 9190;
    private static final int KV_PORT = 20160;

    static Network network = Network.newNetwork();

    @SuppressWarnings("resource")
    static GenericContainer<?> pd = new GenericContainer<>(PD_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("pd1")
            .withExposedPorts(PD_PORT, PD_METRICS_PORT)
            .withCreateContainerCmdModifier(cmd ->
                    cmd.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindPort(PD_PORT), ExposedPort.tcp(PD_PORT)),
                            new PortBinding(Ports.Binding.bindPort(PD_METRICS_PORT), ExposedPort.tcp(PD_METRICS_PORT))
                    ))
            .withCommand(
                    "--node-id", "1",
                    "--cluster-id", "1",
                    "--client-address", "pd1:" + PD_PORT,
                    "--raft-address", "0.0.0.0:2380",
                    "--data-dir", "/data/x-pd",
                    "--metrics-port", String.valueOf(PD_METRICS_PORT)
            )
            .waitingFor(Wait.forHttp("/readyz").forPort(PD_METRICS_PORT)
                    .withStartupTimeout(Duration.ofSeconds(120)));

    @SuppressWarnings("resource")
    static GenericContainer<?> kv = new GenericContainer<>(KV_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("kv1")
            .withExposedPorts(KV_PORT)
            .withCreateContainerCmdModifier(cmd ->
                    cmd.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindPort(KV_PORT), ExposedPort.tcp(KV_PORT))
                    ))
            .dependsOn(pd)
            .withCommand(
                    "--store-id", "1",
                    "--pd", "pd1:" + PD_PORT,
                    "--client-address", "0.0.0.0:" + KV_PORT,
                    "--raft-address", "0.0.0.0:20170",
                    "--data-dir", "/data/x-kv",
                    "--metrics-port", "9191"
            )
            .waitingFor(Wait.forListeningPort()
                    .withStartupTimeout(Duration.ofSeconds(120)));

    private static XKvClient xkvClient;
    private static TxnClient txnClient;
    private static XDBServer xdbServer;
    private static DDLWorker ddlWorker;
    private static int xdbPort;

    private Connection conn;

    @BeforeAll
    static void startAll() throws Exception {
        pd.start();
        kv.start();

        log.info("PD available at localhost:{}", PD_PORT);
        log.info("KV available at localhost:{}", KV_PORT);
        log.info("=== PD container logs ===\n{}", pd.getLogs());
        log.info("=== KV container logs ===\n{}", kv.getLogs());

        ClientConfig clientConfig = ClientConfig.builder()
                .pdEndpoints(List.of("localhost:" + PD_PORT))
                .build();

        xkvClient = XKvClient.create(clientConfig);
        txnClient = TxnClient.create(clientConfig);
        RawKvClient rawKv = xkvClient.raw();

        await().atMost(Duration.ofSeconds(180))
                .pollInterval(Duration.ofSeconds(3))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    try {
                        rawKv.put("__probe__".getBytes(), "ok".getBytes());
                        byte[] val = rawKv.get("__probe__".getBytes()).orElse(null);
                        assertThat(val).isNotNull();
                        rawKv.delete("__probe__".getBytes());
                    } catch (Exception e) {
                        log.warn("KV probe failed: {}", e.getMessage());
                        throw e;
                    }
                });
        log.info("KV cluster is ready");

        String nodeId = UUID.randomUUID().toString();

        KVMetaStore metaStore = new KVMetaStore(
                key -> rawKv.get(key).orElse(null),
                rawKv::put,
                rawKv::delete,
                (key, expected, newVal) ->
                        rawKv.cas(key, Optional.ofNullable(expected), newVal).succeeded()
        );

        InfoSchemaHolder schemaHolder = new InfoSchemaHolder(metaStore);

        KVDDLJobQueue jobQueue = new KVDDLJobQueue(
                key -> rawKv.get(key).orElse(null),
                rawKv::put,
                rawKv::delete,
                (start, end, limit) -> rawKv.scan(start, end, limit).stream()
                        .map(kp -> new byte[][]{kp.key(), kp.value()})
                        .toList(),
                (key, expected, newVal) ->
                        rawKv.cas(key, Optional.ofNullable(expected), newVal).succeeded()
        );

        DDLOwnerManager ownerManager = new DDLOwnerManager(
                nodeId,
                key -> rawKv.get(key).orElse(null),
                rawKv::put,
                (key, expected, newVal) ->
                        rawKv.cas(key, Optional.ofNullable(expected), newVal).succeeded()
        );

        SchemaChangeExecutor schemaChangeExecutor = new SchemaChangeExecutor(metaStore);
        ddlWorker = new DDLWorker(ownerManager, jobQueue, schemaChangeExecutor, schemaHolder::refresh);
        DDLExecutor ddlExecutor = new DDLExecutor(jobQueue, 30_000);

        TransactionManager.TxnStarter txnStarter =
                pessimistic -> pessimistic ? txnClient.beginPessimistic() : txnClient.begin();
        TransactionManager.TxnCommitter txnCommitter =
                txn -> ((Transaction) txn).commit();
        TransactionManager.TxnRollbacker txnRollbacker =
                txn -> ((Transaction) txn).rollback();
        TransactionManager.TxnContextFactory txnContextFactory =
                (txn, evalCtx) -> {
                    Transaction t = (Transaction) txn;
                    return new TransactionContext(
                            (s, e, l) -> StreamSupport.stream(t.scan(s, e, l).spliterator(), false)
                                    .map(kp -> new TransactionContext.KVPair(kp.key(), kp.value()))
                                    .toList(),
                            key -> t.get(key).orElse(null),
                            t::put,
                            t::delete,
                            evalCtx
                    );
                };

        SessionManagerImpl sessionManager = new SessionManagerImpl(
                schemaHolder, ddlExecutor, metaStore,
                txnStarter, txnCommitter, txnRollbacker, txnContextFactory
        );

        Thread ddlThread = new Thread(ddlWorker, "ddl-worker-fullstack");
        ddlThread.setDaemon(true);
        ddlThread.start();

        xdbPort = findFreePort();
        XDBConfig config = new XDBConfig().port(xdbPort).workerThreads(2);
        xdbServer = new XDBServer(config, sessionManager);
        xdbServer.start();
        log.info("x-db server started on port {}", xdbPort);
    }

    @AfterAll
    static void stopAll() {
        if (xdbServer != null) xdbServer.shutdown();
        if (ddlWorker != null) ddlWorker.shutdown();
        try { if (txnClient != null) txnClient.close(); } catch (Exception ignored) {}
        try { if (xkvClient != null) xkvClient.close(); } catch (Exception ignored) {}
        pd.stop();
        kv.stop();
        network.close();
    }

    @BeforeEach
    void connect() throws SQLException {
        conn = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + xdbPort + "/?allowPublicKeyRetrieval=true&useSSL=false",
                "root", "");
    }

    @AfterEach
    void disconnect() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }

    // -----------------------------------------------------------------------
    // DDL
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("CREATE DATABASE, USE, CREATE TABLE, SHOW TABLES")
    void ddlOperations() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE fullstack_db");
            stmt.execute("USE fullstack_db");
            stmt.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(255), age INT)");
            stmt.execute("CREATE TABLE products (id BIGINT PRIMARY KEY, name VARCHAR(255), price INT, category VARCHAR(255))");

            try (ResultSet rs = stmt.executeQuery("SHOW TABLES")) {
                int count = 0;
                while (rs.next()) count++;
                assertThat(count).isEqualTo(2);
            }
        }
    }

    // -----------------------------------------------------------------------
    // DML — INSERT / SELECT / UPDATE / DELETE
    // -----------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("INSERT and SELECT rows")
    void insertAndSelect() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("USE fullstack_db");

            assertThat(stmt.executeUpdate(
                    "INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)")).isEqualTo(1);
            assertThat(stmt.executeUpdate(
                    "INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)")).isEqualTo(1);
            assertThat(stmt.executeUpdate(
                    "INSERT INTO users (id, name, age) VALUES (3, 'Charlie', 35)")).isEqualTo(1);

            try (ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
                int count = 0;
                while (rs.next()) count++;
                assertThat(count).isEqualTo(3);
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("SELECT with WHERE clause")
    void selectWithWhere() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("USE fullstack_db");

            try (ResultSet rs = stmt.executeQuery("SELECT name, age FROM users WHERE age > 28")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.next()).isTrue();
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("UPDATE and verify")
    void updateRows() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("USE fullstack_db");

            int affected = stmt.executeUpdate("UPDATE users SET age = 31 WHERE name = 'Alice'");
            assertThat(affected).isEqualTo(1);

            try (ResultSet rs = stmt.executeQuery("SELECT age FROM users WHERE name = 'Alice'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("age")).isEqualTo(31);
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("DELETE and verify")
    void deleteRows() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("USE fullstack_db");

            int affected = stmt.executeUpdate("DELETE FROM users WHERE name = 'Charlie'");
            assertThat(affected).isEqualTo(1);

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Aggregation
    // -----------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("Aggregation queries: COUNT, SUM, MIN, MAX")
    void aggregationQueries() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("USE fullstack_db");

            stmt.executeUpdate("INSERT INTO products (id, name, price, category) VALUES (1, 'Laptop', 999, 'Electronics')");
            stmt.executeUpdate("INSERT INTO products (id, name, price, category) VALUES (2, 'Mouse', 25, 'Electronics')");
            stmt.executeUpdate("INSERT INTO products (id, name, price, category) VALUES (3, 'Desk', 200, 'Furniture')");
            stmt.executeUpdate("INSERT INTO products (id, name, price, category) VALUES (4, 'Chair', 150, 'Furniture')");

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM products")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(4);
            }

            try (ResultSet rs = stmt.executeQuery("SELECT SUM(price) FROM products")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1374);
            }

            try (ResultSet rs = stmt.executeQuery("SELECT MIN(price) FROM products")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(25);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT MAX(price) FROM products")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(999);
            }
        }
    }

    @Test
    @Order(7)
    @DisplayName("GROUP BY with COUNT")
    void groupByCount() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("USE fullstack_db");

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT category, COUNT(*) FROM products GROUP BY category")) {
                int count = 0;
                while (rs.next()) {
                    String category = rs.getString(1);
                    int cnt = rs.getInt(2);
                    assertThat(cnt).isEqualTo(2);
                    count++;
                }
                assertThat(count).isEqualTo(2);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Prepared Statements (binary protocol)
    // -----------------------------------------------------------------------

    @Test
    @Order(8)
    @DisplayName("PreparedStatement INSERT and SELECT (server-side binary protocol)")
    void preparedStatements() throws SQLException {
        try (Connection psConn = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + xdbPort + "/?allowPublicKeyRetrieval=true&useSSL=false&useServerPrepStmts=true",
                "root", "")) {

            try (Statement stmt = psConn.createStatement()) {
                stmt.execute("CREATE DATABASE ps_db");
                stmt.execute("USE ps_db");
                stmt.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, item VARCHAR(255), qty INT)");
            }

            try (PreparedStatement ps = psConn.prepareStatement(
                    "INSERT INTO orders (id, item, qty) VALUES (?, ?, ?)")) {
                ps.setLong(1, 1);
                ps.setString(2, "Widget");
                ps.setInt(3, 10);
                assertThat(ps.executeUpdate()).isEqualTo(1);

                ps.setLong(1, 2);
                ps.setString(2, "Gadget");
                ps.setInt(3, 5);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            try (PreparedStatement ps = psConn.prepareStatement(
                    "SELECT item, qty FROM orders WHERE id = ?")) {
                ps.setLong(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("item")).isEqualTo("Widget");
                    assertThat(rs.getInt("qty")).isEqualTo(10);
                    assertThat(rs.next()).isFalse();
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Transactions
    // -----------------------------------------------------------------------

    @Test
    @Order(9)
    @DisplayName("Explicit transaction: BEGIN, INSERT, COMMIT")
    void transactionCommit() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE txn_db");
            stmt.execute("USE txn_db");
            stmt.execute("CREATE TABLE accounts (id BIGINT PRIMARY KEY, balance INT)");
        }

        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("USE txn_db");
            stmt.executeUpdate("INSERT INTO accounts (id, balance) VALUES (1, 100)");
            stmt.executeUpdate("INSERT INTO accounts (id, balance) VALUES (2, 200)");
        }
        conn.commit();
        conn.setAutoCommit(true);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("USE txn_db");
            try (ResultSet rs = stmt.executeQuery("SELECT SUM(balance) FROM accounts")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(300);
            }
        }
    }

    @Test
    @Order(10)
    @DisplayName("Explicit transaction: BEGIN, INSERT, ROLLBACK")
    void transactionRollback() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("USE txn_db");
        }

        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("USE txn_db");
            stmt.executeUpdate("INSERT INTO accounts (id, balance) VALUES (3, 500)");
        }
        conn.rollback();
        conn.setAutoCommit(true);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("USE txn_db");
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM accounts")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static int findFreePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }
}
