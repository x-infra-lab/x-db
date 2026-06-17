package io.github.xinfra.lab.xdb.test;

import io.github.xinfra.lab.xdb.session.ExecuteResult;
import io.github.xinfra.lab.xdb.session.Session;
import io.github.xinfra.lab.xdb.session.SessionState;
import org.junit.jupiter.api.*;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ConcurrentDDLDMLTest {

    private static TestHarness harness;
    private static final AtomicInteger dbSeq = new AtomicInteger(0);

    @BeforeAll
    static void startHarness() {
        harness = new TestHarness();
    }

    @AfterAll
    static void stopHarness() {
        if (harness != null) harness.close();
    }

    private String freshDb(Session session) {
        String name = "cdb_" + dbSeq.incrementAndGet();
        session.execute("CREATE DATABASE " + name);
        session.useDatabase(name);
        return name;
    }

    // -----------------------------------------------------------------------
    // Concurrent DDL from multiple sessions
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Concurrent DDL")
    class ConcurrentDDL {

        @Test
        @DisplayName("concurrent CREATE DATABASE from separate sessions")
        void concurrentCreateDatabase() throws Exception {
            int threads = 4;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger errors = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                int threadId = t;
                new Thread(() -> {
                    Session s = harness.createSession();
                    ready.countDown();
                    try {
                        go.await();
                        String dbName = "conc_db_" + dbSeq.incrementAndGet();
                        s.execute("CREATE DATABASE " + dbName);
                        s.useDatabase(dbName);
                        assertThat(s.currentDatabase()).isEqualTo(dbName);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        s.close();
                        done.countDown();
                    }
                }).start();
            }

            ready.await();
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(errors.get()).isZero();
        }

        @Test
        @DisplayName("concurrent CREATE TABLE in different databases")
        void concurrentCreateTableDifferentDbs() throws Exception {
            int threads = 4;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger errors = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                new Thread(() -> {
                    Session s = harness.createSession();
                    String db = freshDb(s);
                    ready.countDown();
                    try {
                        go.await();
                        s.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(255))");
                        ExecuteResult result = s.execute("SHOW TABLES");
                        assertThat(result.isQuery()).isTrue();
                        assertThat(result.getRows()).isNotEmpty();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        s.close();
                        done.countDown();
                    }
                }).start();
            }

            ready.await();
            go.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
            assertThat(errors.get()).isZero();
        }
    }

    // -----------------------------------------------------------------------
    // Session isolation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Session isolation")
    class SessionIsolation {

        @Test
        @DisplayName("each session has independent transaction state")
        void independentTransactions() {
            Session s1 = harness.createSession();
            Session s2 = harness.createSession();
            freshDb(s1);

            s1.begin(false);
            assertThat(s1.state()).isEqualTo(SessionState.IN_TRANSACTION);
            assertThat(s2.state()).isEqualTo(SessionState.IDLE);

            s1.close();
            s2.close();
        }

        @Test
        @DisplayName("each session has independent current database")
        void independentDatabases() {
            Session s1 = harness.createSession();
            Session s2 = harness.createSession();

            String db1 = freshDb(s1);
            String db2 = freshDb(s2);

            assertThat(s1.currentDatabase()).isEqualTo(db1);
            assertThat(s2.currentDatabase()).isEqualTo(db2);
            assertThat(db1).isNotEqualTo(db2);

            s1.close();
            s2.close();
        }
    }

    // -----------------------------------------------------------------------
    // DDL visibility across sessions
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DDL visibility across sessions")
    class DDLVisibility {

        @Test
        @DisplayName("DDL in one session is visible to another via SHOW TABLES")
        void ddlVisibleAcrossSessions() {
            Session s1 = harness.createSession();
            String db = freshDb(s1);
            s1.execute("CREATE TABLE cross_session (id BIGINT PRIMARY KEY, name VARCHAR(100))");

            Session s2 = harness.createSession();
            s2.useDatabase(db);
            ExecuteResult result = s2.execute("SHOW TABLES");
            boolean found = result.getRows().stream()
                    .anyMatch(r -> "cross_session".equalsIgnoreCase(r.get(0).toStringValue()));
            assertThat(found).isTrue();

            s1.close();
            s2.close();
        }

        @Test
        @DisplayName("DROP TABLE makes table invisible in other sessions")
        void dropTableVisibility() {
            Session s1 = harness.createSession();
            String db = freshDb(s1);
            s1.execute("CREATE TABLE ephemeral (id BIGINT PRIMARY KEY)");
            s1.execute("DROP TABLE ephemeral");

            Session s2 = harness.createSession();
            s2.useDatabase(db);
            ExecuteResult result = s2.execute("SHOW TABLES");
            boolean found = result.getRows().stream()
                    .anyMatch(r -> "ephemeral".equalsIgnoreCase(r.get(0).toStringValue()));
            assertThat(found).isFalse();

            s1.close();
            s2.close();
        }

        @Test
        @DisplayName("CREATE TABLE IF NOT EXISTS is idempotent")
        void createTableIfNotExists() {
            Session s = harness.createSession();
            freshDb(s);
            s.execute("CREATE TABLE dup_test (id BIGINT PRIMARY KEY)");

            assertThatCode(() ->
                    s.execute("CREATE TABLE IF NOT EXISTS dup_test (id BIGINT PRIMARY KEY)")
            ).doesNotThrowAnyException();

            s.close();
        }

        @Test
        @DisplayName("DROP TABLE IF EXISTS is idempotent")
        void dropTableIfExists() {
            Session s = harness.createSession();
            freshDb(s);

            assertThatCode(() ->
                    s.execute("DROP TABLE IF EXISTS nonexistent")
            ).doesNotThrowAnyException();

            s.close();
        }

        @Test
        @DisplayName("CREATE DATABASE IF NOT EXISTS is idempotent")
        void createDatabaseIfNotExists() {
            Session s = harness.createSession();
            String db = freshDb(s);

            assertThatCode(() ->
                    s.execute("CREATE DATABASE IF NOT EXISTS " + db)
            ).doesNotThrowAnyException();

            s.close();
        }
    }

    // -----------------------------------------------------------------------
    // Transaction + DDL interaction
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Transaction + DDL interaction")
    class TxnDDLInteraction {

        @Test
        @DisplayName("DDL is rejected inside explicit transaction")
        void ddlRejectedInTransaction() {
            Session s = harness.createSession();
            freshDb(s);
            s.begin(false);

            Assertions.assertThrows(Exception.class, () ->
                    s.execute("CREATE TABLE blocked (id BIGINT PRIMARY KEY)"));

            s.close();
        }

        @Test
        @DisplayName("DDL works after transaction commit")
        void ddlAfterCommit() {
            Session s = harness.createSession();
            freshDb(s);

            s.begin(false);
            s.commit();

            assertThatCode(() ->
                    s.execute("CREATE TABLE after_commit (id BIGINT PRIMARY KEY)")
            ).doesNotThrowAnyException();

            s.close();
        }

        @Test
        @DisplayName("DDL works after transaction rollback")
        void ddlAfterRollback() {
            Session s = harness.createSession();
            freshDb(s);

            s.begin(false);
            s.rollback();

            assertThatCode(() ->
                    s.execute("CREATE TABLE after_rollback (id BIGINT PRIMARY KEY)")
            ).doesNotThrowAnyException();

            s.close();
        }

        @Test
        @DisplayName("multiple DDL then transaction then DDL sequence")
        void ddlTxnDdlSequence() {
            Session s = harness.createSession();
            freshDb(s);

            s.execute("CREATE TABLE t1 (id BIGINT PRIMARY KEY)");
            s.execute("CREATE TABLE t2 (id BIGINT PRIMARY KEY)");

            s.begin(false);
            s.commit();

            s.execute("CREATE TABLE t3 (id BIGINT PRIMARY KEY)");

            ExecuteResult result = s.execute("SHOW TABLES");
            assertThat(result.getRows()).hasSizeGreaterThanOrEqualTo(3);

            s.close();
        }
    }

    // -----------------------------------------------------------------------
    // Concurrent session lifecycle
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Concurrent session lifecycle")
    class ConcurrentSessionLifecycle {

        @Test
        @DisplayName("creating and closing sessions concurrently is safe")
        void concurrentCreateClose() throws Exception {
            int threads = 8;
            int iterations = 20;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger errors = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        for (int i = 0; i < iterations; i++) {
                            Session s = harness.createSession();
                            assertThat(s.state()).isEqualTo(SessionState.IDLE);
                            s.close();
                            assertThat(s.state()).isEqualTo(SessionState.CLOSED);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                }).start();
            }

            ready.await();
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(errors.get()).isZero();
        }

        @Test
        @DisplayName("each concurrent session gets a unique ID")
        void uniqueSessionIds() throws Exception {
            int threads = 8;
            int sessionsPerThread = 20;
            Set<Long> ids = ConcurrentHashMap.newKeySet();
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        for (int i = 0; i < sessionsPerThread; i++) {
                            Session s = harness.createSession();
                            ids.add(s.id());
                            s.close();
                        }
                    } catch (Exception e) {
                        // ignored
                    } finally {
                        done.countDown();
                    }
                }).start();
            }

            ready.await();
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(ids).hasSize(threads * sessionsPerThread);
        }

        @Test
        @DisplayName("concurrent close of same session is safe")
        void concurrentCloseOfSameSession() throws Exception {
            Session s = harness.createSession();
            s.begin(false);

            int threads = 10;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            for (int i = 0; i < threads; i++) {
                new Thread(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException ignored) {}
                    s.close();
                    done.countDown();
                }).start();
            }

            ready.await();
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(s.state()).isEqualTo(SessionState.CLOSED);
        }
    }

    // -----------------------------------------------------------------------
    // Sequential DDL operations
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Sequential DDL operations")
    class SequentialDDL {

        @Test
        @DisplayName("CREATE multiple tables in sequence")
        void createMultipleTables() {
            Session s = harness.createSession();
            freshDb(s);

            for (int i = 0; i < 5; i++) {
                s.execute("CREATE TABLE t" + i + " (id BIGINT PRIMARY KEY, val INT)");
            }

            ExecuteResult result = s.execute("SHOW TABLES");
            assertThat(result.getRows()).hasSize(5);

            s.close();
        }

        @Test
        @DisplayName("CREATE TABLE then DROP TABLE then re-CREATE")
        void createDropRecreate() {
            Session s = harness.createSession();
            freshDb(s);

            s.execute("CREATE TABLE volatile_t (id BIGINT PRIMARY KEY, v INT)");
            ExecuteResult r1 = s.execute("SHOW TABLES");
            assertThat(r1.getRows()).hasSize(1);

            s.execute("DROP TABLE volatile_t");
            ExecuteResult r2 = s.execute("SHOW TABLES");
            assertThat(r2.getRows()).isEmpty();

            s.execute("CREATE TABLE volatile_t (id BIGINT PRIMARY KEY, v2 VARCHAR(100))");
            ExecuteResult r3 = s.execute("SHOW TABLES");
            assertThat(r3.getRows()).hasSize(1);

            s.close();
        }

        @Test
        @DisplayName("DESCRIBE shows table columns")
        void describeTable() {
            Session s = harness.createSession();
            freshDb(s);
            s.execute("CREATE TABLE desc_test (id BIGINT PRIMARY KEY, name VARCHAR(255), age INT)");

            ExecuteResult result = s.execute("DESCRIBE desc_test");
            assertThat(result.isQuery()).isTrue();
            assertThat(result.getRows()).hasSize(3);

            s.close();
        }

        @Test
        @DisplayName("SHOW DATABASES lists all created databases")
        void showDatabases() {
            Session s = harness.createSession();
            String db1 = freshDb(s);

            ExecuteResult result = s.execute("SHOW DATABASES");
            assertThat(result.isQuery()).isTrue();
            boolean found = result.getRows().stream()
                    .anyMatch(r -> db1.equalsIgnoreCase(r.get(0).toStringValue()));
            assertThat(found).isTrue();

            s.close();
        }
    }

    // -----------------------------------------------------------------------
    // Transaction state transitions
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Transaction state transitions")
    class TransactionState {

        @Test
        @DisplayName("begin-commit cycle returns to IDLE")
        void beginCommit() {
            Session s = harness.createSession();
            freshDb(s);

            assertThat(s.state()).isEqualTo(SessionState.IDLE);
            s.begin(false);
            assertThat(s.state()).isEqualTo(SessionState.IN_TRANSACTION);
            s.commit();
            assertThat(s.state()).isEqualTo(SessionState.IDLE);

            s.close();
        }

        @Test
        @DisplayName("begin-rollback cycle returns to IDLE")
        void beginRollback() {
            Session s = harness.createSession();
            freshDb(s);

            s.begin(false);
            assertThat(s.state()).isEqualTo(SessionState.IN_TRANSACTION);
            s.rollback();
            assertThat(s.state()).isEqualTo(SessionState.IDLE);

            s.close();
        }

        @Test
        @DisplayName("close with active transaction")
        void closeWithActiveTransaction() {
            Session s = harness.createSession();
            freshDb(s);

            s.begin(false);
            s.close();
            assertThat(s.state()).isEqualTo(SessionState.CLOSED);
        }

        @Test
        @DisplayName("multiple begin-commit cycles")
        void multipleCycles() {
            Session s = harness.createSession();
            freshDb(s);

            for (int i = 0; i < 5; i++) {
                s.begin(false);
                assertThat(s.state()).isEqualTo(SessionState.IN_TRANSACTION);
                s.commit();
                assertThat(s.state()).isEqualTo(SessionState.IDLE);
            }

            s.close();
        }
    }

    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("USE nonexistent database throws")
        void useNonexistentDb() {
            Session s = harness.createSession();
            Assertions.assertThrows(Exception.class, () ->
                    s.useDatabase("nonexistent_db_xyz"));
            s.close();
        }

        @Test
        @DisplayName("CREATE TABLE without USE throws")
        void createTableWithoutUse() {
            Session s = harness.createSession();
            Assertions.assertThrows(Exception.class, () ->
                    s.execute("CREATE TABLE orphan (id BIGINT PRIMARY KEY)"));
            s.close();
        }

        @Test
        @DisplayName("DROP nonexistent TABLE throws")
        void dropNonexistentTable() {
            Session s = harness.createSession();
            freshDb(s);
            Assertions.assertThrows(Exception.class, () ->
                    s.execute("DROP TABLE nonexistent_table"));
            s.close();
        }

        @Test
        @DisplayName("CREATE duplicate DATABASE throws")
        void createDuplicateDb() {
            Session s = harness.createSession();
            String db = freshDb(s);
            Assertions.assertThrows(Exception.class, () ->
                    s.execute("CREATE DATABASE " + db));
            s.close();
        }

        @Test
        @DisplayName("CREATE duplicate TABLE throws")
        void createDuplicateTable() {
            Session s = harness.createSession();
            freshDb(s);
            s.execute("CREATE TABLE dup (id BIGINT PRIMARY KEY)");
            Assertions.assertThrows(Exception.class, () ->
                    s.execute("CREATE TABLE dup (id BIGINT PRIMARY KEY)"));
            s.close();
        }

        @Test
        @DisplayName("operations on closed session throw")
        void operationsOnClosedSession() {
            Session s = harness.createSession();
            s.close();

            Assertions.assertThrows(Exception.class, () ->
                    s.execute("SHOW DATABASES"));
            Assertions.assertThrows(Exception.class, () ->
                    s.begin(false));
        }
    }
}
