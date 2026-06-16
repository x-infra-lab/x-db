package io.github.xinfra.lab.xdb.server;

import io.github.xinfra.lab.xdb.ddl.*;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.meta.DatabaseInfo;
import io.github.xinfra.lab.xdb.meta.MetaStore;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.session.InfoSchemaHolder;
import io.github.xinfra.lab.xdb.session.SessionManagerImpl;
import org.junit.jupiter.api.*;

import java.net.ServerSocket;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MySQLProtocolTest {

    private static XDBServer server;
    private static int port;
    private static Thread ddlWorkerThread;
    private static DDLWorker ddlWorker;

    private Connection conn;

    @BeforeAll
    static void startServer() throws Exception {
        port = findFreePort();

        InMemoryKVStore kvStore = new InMemoryKVStore();
        InMemoryMetaStore metaStore = new InMemoryMetaStore();
        InMemoryDDLJobQueue jobQueue = new InMemoryDDLJobQueue();

        Map<byte[], byte[]> ownerKV = new HashMap<>();
        DDLOwnerManager.KVGetter ownerGetter = k -> { synchronized (ownerKV) {
                    for (var e : ownerKV.entrySet()) if (Arrays.equals(e.getKey(), k)) return e.getValue();
                    return null;
                }};
        DDLOwnerManager.KVPutter ownerPutter = (k, v) -> { synchronized (ownerKV) {
                    ownerKV.entrySet().removeIf(e -> Arrays.equals(e.getKey(), k));
                    ownerKV.put(k, v);
                }};
        DDLOwnerManager.CASOperation ownerCas = (k, expected, newVal) -> { synchronized (ownerKV) {
                    byte[] cur = null;
                    for (var e : ownerKV.entrySet()) if (Arrays.equals(e.getKey(), k)) { cur = e.getValue(); break; }
                    if (Arrays.equals(cur, expected)) {
                        ownerKV.entrySet().removeIf(e -> Arrays.equals(e.getKey(), k));
                        ownerKV.put(k, newVal);
                        return true;
                    }
                    return false;
                }};
        DDLOwnerManager ownerManager = new DDLOwnerManager(
                "test-node", ownerGetter, ownerPutter, ownerCas);

        InfoSchemaHolder schemaHolder = new InfoSchemaHolder(metaStore);
        SchemaChangeExecutor schemaChangeExecutor = new SchemaChangeExecutor(metaStore);
        ddlWorker = new DDLWorker(ownerManager, jobQueue, schemaChangeExecutor, schemaHolder::refresh);
        DDLExecutor ddlExecutor = new DDLExecutor(jobQueue, 60_000);

        SessionManagerImpl sessionManager = new SessionManagerImpl(
                schemaHolder, ddlExecutor, metaStore,
                pessimistic -> new InMemoryTxn(kvStore),
                txn -> ((InMemoryTxn) txn).commit(),
                txn -> ((InMemoryTxn) txn).rollback(),
                (txn, evalCtx) -> {
                    InMemoryTxn t = (InMemoryTxn) txn;
                    return new TransactionContext(t::scan, t::get, t::put, t::delete, evalCtx);
                }
        );

        ddlWorkerThread = new Thread(ddlWorker, "ddl-worker-protocol-test");
        ddlWorkerThread.setDaemon(true);
        ddlWorkerThread.start();

        XDBConfig config = new XDBConfig().port(port).workerThreads(2);
        server = new XDBServer(config, sessionManager);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.shutdown();
        if (ddlWorker != null) ddlWorker.shutdown();
        if (ddlWorkerThread != null) ddlWorkerThread.interrupt();
    }

    @BeforeEach
    void connect() throws SQLException {
        conn = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + port + "/?allowPublicKeyRetrieval=true&useSSL=false",
                "root", "");
    }

    @AfterEach
    void disconnect() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }

    // -----------------------------------------------------------------------
    // Connection
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("JDBC connection handshake succeeds")
    void connectionSucceeds() throws SQLException {
        assertThat(conn.isClosed()).isFalse();
    }

    @Test
    @DisplayName("PING via isValid()")
    void pingWorks() throws SQLException {
        assertThat(conn.isValid(5)).isTrue();
    }

    // -----------------------------------------------------------------------
    // DDL via JDBC
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CREATE DATABASE and USE via JDBC")
    void createDatabaseViaJDBC() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE proto_test_db");
            stmt.execute("USE proto_test_db");
        }
    }

    @Test
    @DisplayName("CREATE TABLE and SHOW TABLES via JDBC")
    void createTableViaJDBC() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE proto_tbl_db");
            stmt.execute("USE proto_tbl_db");
            stmt.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(255), age INT)");

            try (ResultSet rs = stmt.executeQuery("SHOW TABLES")) {
                assertThat(rs.next()).isTrue();
                String tableName = rs.getString(1);
                assertThat(tableName).isEqualToIgnoringCase("users");
            }
        }
    }

    // -----------------------------------------------------------------------
    // DML via JDBC
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("INSERT and SELECT via JDBC")
    void insertAndSelectViaJDBC() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE proto_dml_db");
            stmt.execute("USE proto_dml_db");
            stmt.execute("CREATE TABLE items (id BIGINT PRIMARY KEY, name VARCHAR(255), price INT)");

            int affected = stmt.executeUpdate(
                    "INSERT INTO items (id, name, price) VALUES (1, 'Widget', 100)");
            assertThat(affected).isEqualTo(1);

            try (ResultSet rs = stmt.executeQuery("SELECT * FROM items")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isEqualTo(1);
                assertThat(rs.getString("name")).isEqualTo("Widget");
                assertThat(rs.getInt("price")).isEqualTo(100);
                assertThat(rs.next()).isFalse();
            }
        }
    }

    // -----------------------------------------------------------------------
    // SELECT expression (no FROM)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("SELECT 1+1 via JDBC")
    void selectExpression() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 + 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }

    // -----------------------------------------------------------------------
    // Prepared statements (COM_STMT_PREPARE / COM_STMT_EXECUTE)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PreparedStatement INSERT and SELECT via JDBC (server-side)")
    void preparedStatementInsertAndSelect() throws SQLException {
        // Use a separate connection with useServerPrepStmts=true to exercise COM_STMT_PREPARE
        try (Connection psConn = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + port + "/?allowPublicKeyRetrieval=true&useSSL=false&useServerPrepStmts=true",
                "root", "")) {

            try (Statement stmt = psConn.createStatement()) {
                stmt.execute("CREATE DATABASE proto_ps_db");
                stmt.execute("USE proto_ps_db");
                stmt.execute("CREATE TABLE products (id BIGINT PRIMARY KEY, name VARCHAR(255), price INT)");
            }

            try (PreparedStatement ps = psConn.prepareStatement(
                    "INSERT INTO products (id, name, price) VALUES (?, ?, ?)")) {
                ps.setLong(1, 1);
                ps.setString(2, "Laptop");
                ps.setInt(3, 999);
                assertThat(ps.executeUpdate()).isEqualTo(1);

                ps.setLong(1, 2);
                ps.setString(2, "Mouse");
                ps.setInt(3, 25);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            try (PreparedStatement ps = psConn.prepareStatement(
                    "SELECT name, price FROM products WHERE id = ?")) {
                ps.setLong(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("name")).isEqualTo("Laptop");
                    assertThat(rs.getInt("price")).isEqualTo(999);
                    assertThat(rs.next()).isFalse();
                }
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

    static class InMemoryKVStore {
        private static final Comparator<byte[]> CMP = (a, b) -> {
            int min = Math.min(a.length, b.length);
            for (int i = 0; i < min; i++) {
                int c = Integer.compare(a[i] & 0xFF, b[i] & 0xFF);
                if (c != 0) return c;
            }
            return Integer.compare(a.length, b.length);
        };
        private final ConcurrentSkipListMap<byte[], byte[]> data = new ConcurrentSkipListMap<>(CMP);
        byte[] get(byte[] k) { byte[] v = data.get(k); return v != null ? Arrays.copyOf(v, v.length) : null; }
        void put(byte[] k, byte[] v) { data.put(Arrays.copyOf(k, k.length), Arrays.copyOf(v, v.length)); }
        void delete(byte[] k) { data.remove(k); }
        List<TransactionContext.KVPair> scan(byte[] start, byte[] end, int limit) {
            var sub = end != null ? data.subMap(start, true, end, false) : data.tailMap(start, true);
            List<TransactionContext.KVPair> result = new ArrayList<>();
            int n = 0;
            for (var e : sub.entrySet()) {
                if (limit > 0 && n >= limit) break;
                result.add(new TransactionContext.KVPair(
                        Arrays.copyOf(e.getKey(), e.getKey().length),
                        Arrays.copyOf(e.getValue(), e.getValue().length)));
                n++;
            }
            return result;
        }
    }

    static class InMemoryTxn {
        private final InMemoryKVStore store;
        InMemoryTxn(InMemoryKVStore store) { this.store = store; }
        byte[] get(byte[] k) { return store.get(k); }
        void put(byte[] k, byte[] v) { store.put(k, v); }
        void delete(byte[] k) { store.delete(k); }
        List<TransactionContext.KVPair> scan(byte[] s, byte[] e, int l) { return store.scan(s, e, l); }
        void commit() {}
        void rollback() {}
    }

    static class InMemoryMetaStore implements MetaStore {
        private long schemaVersion;
        private final Map<Long, DatabaseInfo> databases = new HashMap<>();
        private final Map<Long, Map<Long, TableInfo>> tables = new HashMap<>();
        private final AtomicLong globalIdGen = new AtomicLong(1000);
        private final Map<Long, AtomicLong> autoIncIds = new HashMap<>();
        @Override public long getSchemaVersion() { return schemaVersion; }
        @Override public void setSchemaVersion(long v) { schemaVersion = v; }
        @Override public void createDatabase(DatabaseInfo db) { databases.put(db.getId(), db); tables.putIfAbsent(db.getId(), new HashMap<>()); }
        @Override public void dropDatabase(long id) { databases.remove(id); tables.remove(id); }
        @Override public DatabaseInfo getDatabase(long id) { return databases.get(id); }
        @Override public java.util.List<Long> listDatabaseIds() { return new ArrayList<>(databases.keySet()); }
        @Override public void createTable(long dbId, TableInfo t) { tables.computeIfAbsent(dbId, k -> new HashMap<>()).put(t.getId(), t); }
        @Override public void updateTable(long dbId, TableInfo t) { var m = tables.get(dbId); if (m != null) m.put(t.getId(), t); }
        @Override public void dropTable(long dbId, long tId) { var m = tables.get(dbId); if (m != null) m.remove(tId); }
        @Override public TableInfo getTable(long dbId, long tId) { var m = tables.get(dbId); return m != null ? m.get(tId) : null; }
        @Override public java.util.List<Long> listTableIds(long dbId) { var m = tables.get(dbId); return m != null ? new ArrayList<>(m.keySet()) : new ArrayList<>(); }
        @Override public long advanceSchemaVersion() { return ++schemaVersion; }
        @Override public long allocAutoIncId(long tableId, int batch) { return autoIncIds.computeIfAbsent(tableId, k -> new AtomicLong(0)).addAndGet(batch); }
        @Override public long allocGlobalId() { return globalIdGen.incrementAndGet(); }
        private final Map<Long, byte[]> statsData = new HashMap<>();
        @Override public void putTableStats(long tableId, byte[] statsJson) { statsData.put(tableId, statsJson); }
        @Override public byte[] getTableStats(long tableId) { return statsData.get(tableId); }
    }

    static class InMemoryDDLJobQueue implements DDLJobQueue {
        private final ConcurrentHashMap<Long, DDLJob> queue = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, DDLJob> history = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong(0);
        @Override public void enqueue(DDLJob j) { if (j.getId()==0) j.setId(allocJobId()); if (j.getState()==null||j.getState()==DDLState.NONE) j.setState(DDLState.QUEUED); j.setStartTs(System.currentTimeMillis()); queue.put(j.getId(),j); }
        @Override public DDLJob dequeue() { return queue.values().stream().filter(j->j.getState()==DDLState.QUEUED).min(Comparator.comparingLong(DDLJob::getId)).orElse(null); }
        @Override public DDLJob getJob(long id) { DDLJob j=queue.get(id); return j!=null?j:history.get(id); }
        @Override public void updateJob(DDLJob j) { queue.put(j.getId(),j); }
        @Override public java.util.List<DDLJob> listJobs() { return new ArrayList<>(queue.values()); }
        @Override public void moveToHistory(DDLJob j) { queue.remove(j.getId()); history.put(j.getId(),j); }
        @Override public java.util.List<DDLJob> listHistory(int limit) { return history.values().stream().sorted(Comparator.comparingLong(DDLJob::getFinishTs).reversed()).limit(limit).toList(); }
        @Override public long allocJobId() { return idGen.incrementAndGet(); }
    }
}
