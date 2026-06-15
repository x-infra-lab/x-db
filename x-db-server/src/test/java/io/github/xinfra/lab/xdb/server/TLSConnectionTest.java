package io.github.xinfra.lab.xdb.server;

import io.github.xinfra.lab.xdb.ddl.*;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.session.InfoSchemaHolder;
import io.github.xinfra.lab.xdb.session.SessionManagerImpl;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class TLSConnectionTest {

    @Test
    void tlsConnectionSucceeds() throws Exception {
        Path tmpDir = Files.createTempDirectory("x-db-tls-test");
        try {
            Path certFile = tmpDir.resolve("server-cert.pem");
            Path keyFile = tmpDir.resolve("server-key.pem");
            generateCertWithKeytool(tmpDir, certFile, keyFile);

            int port = findFreePort();
            try (AuthenticationTest.TestServer server = startTlsServer(
                    port, "", certFile.toString(), keyFile.toString())) {
                try (Connection conn = DriverManager.getConnection(
                        "jdbc:mysql://127.0.0.1:" + port
                                + "/?useSSL=true&verifyServerCertificate=false",
                        "root", "")) {
                    assertThat(conn.isClosed()).isFalse();
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT 1 + 2")) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1)).isEqualTo(3);
                    }
                }
            }
        } finally {
            deleteRecursive(tmpDir);
        }
    }

    @Test
    void tlsWithPasswordAuthentication() throws Exception {
        Path tmpDir = Files.createTempDirectory("x-db-tls-test");
        try {
            Path certFile = tmpDir.resolve("server-cert.pem");
            Path keyFile = tmpDir.resolve("server-key.pem");
            generateCertWithKeytool(tmpDir, certFile, keyFile);

            int port = findFreePort();
            try (AuthenticationTest.TestServer server = startTlsServer(
                    port, "tls-secret", certFile.toString(), keyFile.toString())) {
                try (Connection conn = DriverManager.getConnection(
                        "jdbc:mysql://127.0.0.1:" + port
                                + "/?useSSL=true&verifyServerCertificate=false"
                                + "&defaultAuthenticationPlugin=mysql_native_password",
                        "root", "tls-secret")) {
                    assertThat(conn.isClosed()).isFalse();
                    assertThat(conn.isValid(5)).isTrue();
                }
            }
        } finally {
            deleteRecursive(tmpDir);
        }
    }

    @Test
    void nonTlsConnectionStillWorks() throws Exception {
        int port = findFreePort();

        MySQLProtocolTest.InMemoryKVStore kvStore = new MySQLProtocolTest.InMemoryKVStore();
        MySQLProtocolTest.InMemoryMetaStore metaStore = new MySQLProtocolTest.InMemoryMetaStore();
        MySQLProtocolTest.InMemoryDDLJobQueue jobQueue = new MySQLProtocolTest.InMemoryDDLJobQueue();

        Map<byte[], byte[]> ownerKV = new HashMap<>();
        DDLOwnerManager ownerManager = new DDLOwnerManager("test-node",
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
                }});

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
                });

        Thread ddlWorkerThread = new Thread(ddlWorker, "ddl-worker-tls-test");
        ddlWorkerThread.setDaemon(true);
        ddlWorkerThread.start();

        XDBConfig config = new XDBConfig().port(port).workerThreads(2);
        XDBServer server = new XDBServer(config, sessionManager);
        server.start();

        try (AuthenticationTest.TestServer ts = new AuthenticationTest.TestServer(
                server, ddlWorker, ddlWorkerThread, port)) {
            try (Connection conn = DriverManager.getConnection(
                    "jdbc:mysql://127.0.0.1:" + port + "/?useSSL=false",
                    "root", "")) {
                assertThat(conn.isClosed()).isFalse();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void generateCertWithKeytool(Path tmpDir, Path certFile, Path keyFile) throws Exception {
        Path ks = tmpDir.resolve("keystore.p12");
        String pass = "changeit";

        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "x-db-test",
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365",
                "-dname", "CN=x-db-test",
                "-storetype", "PKCS12",
                "-keystore", ks.toString(),
                "-storepass", pass,
                "-keypass", pass
        );
        pb.inheritIO();
        int exit = pb.start().waitFor();
        if (exit != 0) throw new RuntimeException("keytool genkeypair failed with exit code " + exit);

        // Export certificate to PEM
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var fis = Files.newInputStream(ks)) {
            keyStore.load(fis, pass.toCharArray());
        }
        Certificate cert = keyStore.getCertificate("x-db-test");
        java.security.Key key = keyStore.getKey("x-db-test", pass.toCharArray());

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(certFile))) {
            pw.println("-----BEGIN CERTIFICATE-----");
            pw.println(Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded()));
            pw.println("-----END CERTIFICATE-----");
        }

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(keyFile))) {
            pw.println("-----BEGIN PRIVATE KEY-----");
            pw.println(Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded()));
            pw.println("-----END PRIVATE KEY-----");
        }
    }

    private static AuthenticationTest.TestServer startTlsServer(
            int port, String password, String certPath, String keyPath) throws Exception {
        MySQLProtocolTest.InMemoryKVStore kvStore = new MySQLProtocolTest.InMemoryKVStore();
        MySQLProtocolTest.InMemoryMetaStore metaStore = new MySQLProtocolTest.InMemoryMetaStore();
        MySQLProtocolTest.InMemoryDDLJobQueue jobQueue = new MySQLProtocolTest.InMemoryDDLJobQueue();

        Map<byte[], byte[]> ownerKV = new HashMap<>();
        DDLOwnerManager ownerManager = new DDLOwnerManager("test-node",
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
                }});

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
                });

        Thread ddlWorkerThread = new Thread(ddlWorker, "ddl-worker-tls-test");
        ddlWorkerThread.setDaemon(true);
        ddlWorkerThread.start();

        XDBConfig config = new XDBConfig().port(port).workerThreads(2)
                .rootPassword(password)
                .tlsEnabled(true)
                .tlsCertFile(certPath)
                .tlsKeyFile(keyPath);
        XDBServer server = new XDBServer(config, sessionManager);
        server.start();

        return new AuthenticationTest.TestServer(server, ddlWorker, ddlWorkerThread, port);
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static void deleteRecursive(Path path) {
        try {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }
}
