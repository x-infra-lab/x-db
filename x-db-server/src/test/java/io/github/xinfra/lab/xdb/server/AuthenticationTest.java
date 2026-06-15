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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticationTest {

    // -----------------------------------------------------------------------
    // Test: empty password allowed when no rootPassword configured
    // -----------------------------------------------------------------------

    @Test
    void emptyPasswordAllowedByDefault() throws Exception {
        try (TestServer server = startServer("")) {
            try (Connection conn = connect(server.port, "root", "")) {
                assertThat(conn.isClosed()).isFalse();
                assertThat(conn.isValid(5)).isTrue();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Test: correct password accepted
    // -----------------------------------------------------------------------

    @Test
    void correctPasswordAccepted() throws Exception {
        try (TestServer server = startServer("test-secret-123")) {
            try (Connection conn = connect(server.port, "root", "test-secret-123")) {
                assertThat(conn.isClosed()).isFalse();
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT 1 + 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(2);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Test: wrong password rejected
    // -----------------------------------------------------------------------

    @Test
    void wrongPasswordRejected() throws Exception {
        try (TestServer server = startServer("correct-password")) {
            assertThatThrownBy(() -> connect(server.port, "root", "wrong-password"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Access denied");
        }
    }

    // -----------------------------------------------------------------------
    // Test: empty password rejected when password is set
    // -----------------------------------------------------------------------

    @Test
    void emptyPasswordRejectedWhenPasswordSet() throws Exception {
        try (TestServer server = startServer("my-password")) {
            assertThatThrownBy(() -> connect(server.port, "root", ""))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Access denied");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Connection connect(int port, String user, String password) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + port
                        + "/?allowPublicKeyRetrieval=true&useSSL=false"
                        + "&defaultAuthenticationPlugin=mysql_native_password",
                user, password);
    }

    private static TestServer startServer(String rootPassword) throws Exception {
        int port = findFreePort();

        MySQLProtocolTest.InMemoryKVStore kvStore = new MySQLProtocolTest.InMemoryKVStore();
        MySQLProtocolTest.InMemoryMetaStore metaStore = new MySQLProtocolTest.InMemoryMetaStore();
        MySQLProtocolTest.InMemoryDDLJobQueue jobQueue = new MySQLProtocolTest.InMemoryDDLJobQueue();

        Map<byte[], byte[]> ownerKV = new HashMap<>();
        DDLOwnerManager ownerManager = new DDLOwnerManager(
                "test-node",
                k -> { synchronized (ownerKV) {
                    for (var e : ownerKV.entrySet()) if (Arrays.equals(e.getKey(), k)) return e.getValue();
                    return null;
                }},
                (k, v) -> { synchronized (ownerKV) {
                    ownerKV.entrySet().removeIf(e -> Arrays.equals(e.getKey(), k));
                    ownerKV.put(k, v);
                }},
                (k, expected, newVal) -> { synchronized (ownerKV) {
                    byte[] cur = null;
                    for (var e : ownerKV.entrySet()) if (Arrays.equals(e.getKey(), k)) { cur = e.getValue(); break; }
                    if (Arrays.equals(cur, expected)) {
                        ownerKV.entrySet().removeIf(e -> Arrays.equals(e.getKey(), k));
                        ownerKV.put(k, newVal);
                        return true;
                    }
                    return false;
                }}
        );

        InfoSchemaHolder schemaHolder = new InfoSchemaHolder(metaStore);
        SchemaChangeExecutor schemaChangeExecutor = new SchemaChangeExecutor(metaStore);
        DDLWorker ddlWorker = new DDLWorker(ownerManager, jobQueue, schemaChangeExecutor, schemaHolder::refresh);
        DDLExecutor ddlExecutor = new DDLExecutor(jobQueue, 60_000);

        SessionManagerImpl sessionManager = new SessionManagerImpl(
                schemaHolder, ddlExecutor, metaStore,
                pessimistic -> new MySQLProtocolTest.InMemoryTxn(kvStore),
                txn -> ((MySQLProtocolTest.InMemoryTxn) txn).commit(),
                txn -> ((MySQLProtocolTest.InMemoryTxn) txn).rollback(),
                (txn, evalCtx) -> {
                    MySQLProtocolTest.InMemoryTxn t = (MySQLProtocolTest.InMemoryTxn) txn;
                    return new TransactionContext(t::scan, t::get, t::put, t::delete, evalCtx);
                }
        );

        Thread ddlWorkerThread = new Thread(ddlWorker, "ddl-worker-auth-test");
        ddlWorkerThread.setDaemon(true);
        ddlWorkerThread.start();

        XDBConfig config = new XDBConfig().port(port).workerThreads(2).rootPassword(rootPassword);
        XDBServer server = new XDBServer(config, sessionManager);
        server.start();

        return new TestServer(server, ddlWorker, ddlWorkerThread, port);
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    static class TestServer implements AutoCloseable {
        final XDBServer server;
        final DDLWorker ddlWorker;
        final Thread ddlWorkerThread;
        final int port;

        TestServer(XDBServer server, DDLWorker ddlWorker, Thread ddlWorkerThread, int port) {
            this.server = server;
            this.ddlWorker = ddlWorker;
            this.ddlWorkerThread = ddlWorkerThread;
            this.port = port;
        }

        @Override
        public void close() {
            server.shutdown();
            ddlWorker.shutdown();
            ddlWorkerThread.interrupt();
        }
    }
}
