package io.github.xinfra.lab.xdb.session;

import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.DatabaseInfo;
import io.github.xinfra.lab.xdb.meta.IndexColumn;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.MetaStore;
import io.github.xinfra.lab.xdb.meta.SchemaState;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionImplTest {

    private AtomicInteger rollbackCount;
    private AtomicInteger commitCount;
    private AtomicInteger beginCount;
    private AtomicInteger onCloseCount;
    private SessionImpl session;
    private InMemoryMetaStore metaStore;

    static class InMemoryMetaStore implements MetaStore {
        private final AtomicLong schemaVersion = new AtomicLong(0);
        private final Map<Long, DatabaseInfo> databases = new HashMap<>();
        private final Map<Long, Map<Long, TableInfo>> tables = new HashMap<>();
        private final AtomicLong globalIdGen = new AtomicLong(1000);

        @Override public long getSchemaVersion() { return schemaVersion.get(); }
        @Override public void setSchemaVersion(long version) { schemaVersion.set(version); }
        @Override public long advanceSchemaVersion() { return schemaVersion.incrementAndGet(); }
        @Override public void createDatabase(DatabaseInfo db) {
            databases.put(db.getId(), db);
            tables.putIfAbsent(db.getId(), new HashMap<>());
        }
        @Override public void dropDatabase(long dbId) { databases.remove(dbId); tables.remove(dbId); }
        @Override public DatabaseInfo getDatabase(long dbId) { return databases.get(dbId); }
        @Override public List<Long> listDatabaseIds() { return new ArrayList<>(databases.keySet()); }
        @Override public void createTable(long dbId, TableInfo table) {
            tables.computeIfAbsent(dbId, k -> new HashMap<>()).put(table.getId(), table);
        }
        @Override public void updateTable(long dbId, TableInfo table) {
            Map<Long, TableInfo> m = tables.get(dbId);
            if (m != null) m.put(table.getId(), table);
        }
        @Override public void dropTable(long dbId, long tableId) {
            Map<Long, TableInfo> m = tables.get(dbId);
            if (m != null) m.remove(tableId);
        }
        @Override public TableInfo getTable(long dbId, long tableId) {
            Map<Long, TableInfo> m = tables.get(dbId);
            return m != null ? m.get(tableId) : null;
        }
        @Override public List<Long> listTableIds(long dbId) {
            Map<Long, TableInfo> m = tables.get(dbId);
            return m != null ? new ArrayList<>(m.keySet()) : new ArrayList<>();
        }
        @Override public long allocAutoIncId(long tableId, int batchSize) { return 1; }
        @Override public long allocGlobalId() { return globalIdGen.incrementAndGet(); }
        @Override public void putTableStats(long tableId, byte[] statsJson) {}
        @Override public byte[] getTableStats(long tableId) { return null; }
    }

    @BeforeEach
    void setUp() {
        rollbackCount = new AtomicInteger();
        commitCount = new AtomicInteger();
        beginCount = new AtomicInteger();
        onCloseCount = new AtomicInteger();
        metaStore = new InMemoryMetaStore();

        InfoSchemaHolder schemaHolder = new InfoSchemaHolder(metaStore);

        TransactionManager txnManager = new TransactionManager(
                pessimistic -> { beginCount.incrementAndGet(); return new Object(); },
                txn -> commitCount.incrementAndGet(),
                txn -> rollbackCount.incrementAndGet(),
                (txn, evalCtx) -> new TransactionContext(
                        (s, e, l) -> List.of(),
                        k -> null, (k, v) -> {}, k -> {}, evalCtx)
        );

        session = new SessionImpl(1L, schemaHolder, null, txnManager, metaStore,
                () -> onCloseCount.incrementAndGet());
    }

    // -----------------------------------------------------------------------
    // Session identity
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Session identity")
    class Identity {

        @Test
        @DisplayName("id returns the session ID")
        void id() {
            assertThat(session.id()).isEqualTo(1L);
        }

        @Test
        @DisplayName("initial state is IDLE")
        void initialState() {
            assertThat(session.state()).isEqualTo(SessionState.IDLE);
        }

        @Test
        @DisplayName("variables are accessible")
        void variables() {
            assertThat(session.variables()).isNotNull();
            assertThat(session.variables().isAutoCommit()).isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // Transaction lifecycle
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Transaction lifecycle")
    class TransactionLifecycle {

        @Test
        @DisplayName("begin transitions to IN_TRANSACTION")
        void begin() {
            session.begin(false);
            assertThat(session.state()).isEqualTo(SessionState.IN_TRANSACTION);
            assertThat(beginCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("commit transitions back to IDLE")
        void commit() {
            session.begin(false);
            session.commit();
            assertThat(session.state()).isEqualTo(SessionState.IDLE);
            assertThat(commitCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("rollback transitions back to IDLE")
        void rollback() {
            session.begin(false);
            session.rollback();
            assertThat(session.state()).isEqualTo(SessionState.IDLE);
            assertThat(rollbackCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("commit without transaction throws")
        void commitWithoutTxn() {
            assertThatThrownBy(() -> session.commit())
                    .isInstanceOf(XDBException.class)
                    .hasMessageContaining("No active transaction");
        }

        @Test
        @DisplayName("rollback without transaction throws")
        void rollbackWithoutTxn() {
            assertThatThrownBy(() -> session.rollback())
                    .isInstanceOf(XDBException.class)
                    .hasMessageContaining("No active transaction");
        }

        @Test
        @DisplayName("begin with optimistic=true")
        void beginOptimistic() {
            session.begin(true);
            assertThat(session.state()).isEqualTo(SessionState.IN_TRANSACTION);
            assertThat(beginCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("begin-commit-begin cycle")
        void beginCommitBeginCycle() {
            session.begin(false);
            session.commit();
            session.begin(false);
            assertThat(session.state()).isEqualTo(SessionState.IN_TRANSACTION);
            assertThat(beginCount.get()).isEqualTo(2);
            assertThat(commitCount.get()).isEqualTo(1);
        }
    }

    // -----------------------------------------------------------------------
    // Close behavior
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Close behavior")
    class CloseBehavior {

        @Test
        @DisplayName("close transitions to CLOSED")
        void closeTransition() {
            session.close();
            assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        }

        @Test
        @DisplayName("close twice is idempotent")
        void closeTwice() {
            session.close();
            session.close();
            assertThat(session.state()).isEqualTo(SessionState.CLOSED);
            assertThat(onCloseCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("close rolls back active transaction")
        void closeRollsBack() {
            session.begin(false);
            session.close();
            assertThat(rollbackCount.get()).isEqualTo(1);
            assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        }

        @Test
        @DisplayName("close without transaction does not rollback")
        void closeNoTxn() {
            session.close();
            assertThat(rollbackCount.get()).isEqualTo(0);
        }

        @Test
        @DisplayName("onClose callback is invoked")
        void onCloseCallback() {
            session.close();
            assertThat(onCloseCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("execute after close throws")
        void executeAfterClose() {
            session.close();
            assertThatThrownBy(() -> session.execute("SELECT 1"))
                    .isInstanceOf(XDBException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        @DisplayName("begin after close throws")
        void beginAfterClose() {
            session.close();
            assertThatThrownBy(() -> session.begin(false))
                    .isInstanceOf(XDBException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        @DisplayName("commit after close throws")
        void commitAfterClose() {
            session.close();
            assertThatThrownBy(() -> session.commit())
                    .isInstanceOf(XDBException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        @DisplayName("rollback after close throws")
        void rollbackAfterClose() {
            session.close();
            assertThatThrownBy(() -> session.rollback())
                    .isInstanceOf(XDBException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        @DisplayName("useDatabase after close throws")
        void useDatabaseAfterClose() {
            session.close();
            assertThatThrownBy(() -> session.useDatabase("testdb"))
                    .isInstanceOf(XDBException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        @DisplayName("concurrent close only rolls back once")
        void concurrentClose() throws Exception {
            session.begin(false);
            assertThat(session.state()).isEqualTo(SessionState.IN_TRANSACTION);

            int threads = 10;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            for (int i = 0; i < threads; i++) {
                new Thread(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException ignored) {}
                    session.close();
                    done.countDown();
                }).start();
            }

            ready.await();
            go.countDown();
            done.await();

            assertThat(session.state()).isEqualTo(SessionState.CLOSED);
            assertThat(rollbackCount.get()).isEqualTo(1);
            assertThat(onCloseCount.get()).isEqualTo(1);
        }
    }

    // -----------------------------------------------------------------------
    // USE database
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("USE database")
    class UseDatabase {

        @Test
        @DisplayName("currentDatabase is null initially")
        void initiallyNull() {
            assertThat(session.currentDatabase()).isNull();
        }

        @Test
        @DisplayName("useDatabase sets current database")
        void setsDatabase() {
            DatabaseInfo db = new DatabaseInfo(1L, "testdb", "utf8mb4",
                    "utf8mb4_general_ci", SchemaState.PUBLIC);
            metaStore.createDatabase(db);
            metaStore.advanceSchemaVersion();

            session.useDatabase("testdb");
            assertThat(session.currentDatabase()).isEqualTo("testdb");
        }

        @Test
        @DisplayName("useDatabase with non-existent database throws")
        void nonExistentThrows() {
            assertThatThrownBy(() -> session.useDatabase("nonexistent"))
                    .isInstanceOf(XDBException.class);
        }

        @Test
        @DisplayName("currentDatabase is visible across threads")
        void volatileVisible() throws Exception {
            DatabaseInfo db = new DatabaseInfo(1L, "mydb", "utf8mb4",
                    "utf8mb4_general_ci", SchemaState.PUBLIC);
            metaStore.createDatabase(db);
            metaStore.advanceSchemaVersion();
            session.useDatabase("mydb");

            CountDownLatch done = new CountDownLatch(1);
            Thread t = new Thread(() -> {
                assertThat(session.currentDatabase()).isEqualTo("mydb");
                done.countDown();
            });
            t.start();
            done.await();
        }
    }

    // -----------------------------------------------------------------------
    // DDL inside transaction rejection
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DDL inside transaction")
    class DDLInTransaction {

        @Test
        @DisplayName("DDL is rejected inside explicit transaction")
        void ddlRejectedInTransaction() {
            DatabaseInfo db = new DatabaseInfo(1L, "testdb", "utf8mb4",
                    "utf8mb4_general_ci", SchemaState.PUBLIC);
            metaStore.createDatabase(db);
            metaStore.advanceSchemaVersion();
            session.useDatabase("testdb");

            session.begin(false);

            assertThatThrownBy(() -> session.execute("CREATE TABLE t1 (id INT PRIMARY KEY)"))
                    .isInstanceOf(XDBException.class)
                    .hasMessageContaining("DDL");
        }
    }

    // -----------------------------------------------------------------------
    // onClose callback edge cases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("onClose callback")
    class OnCloseCallback {

        @Test
        @DisplayName("session without onClose callback works fine")
        void noCallback() {
            TransactionManager txnManager = new TransactionManager(
                    pessimistic -> new Object(),
                    txn -> {},
                    txn -> {},
                    (txn, evalCtx) -> new TransactionContext(
                            (s, e, l) -> List.of(),
                            k -> null, (k, v) -> {}, k -> {}, evalCtx)
            );
            SessionImpl s = new SessionImpl(99L, new InfoSchemaHolder(metaStore),
                    null, txnManager, metaStore);
            s.close();
            assertThat(s.state()).isEqualTo(SessionState.CLOSED);
        }

        @Test
        @DisplayName("onClose exception does not prevent state transition")
        void callbackException() {
            TransactionManager txnManager = new TransactionManager(
                    pessimistic -> new Object(),
                    txn -> {},
                    txn -> {},
                    (txn, evalCtx) -> new TransactionContext(
                            (s, e, l) -> List.of(),
                            k -> null, (k, v) -> {}, k -> {}, evalCtx)
            );
            SessionImpl s = new SessionImpl(99L, new InfoSchemaHolder(metaStore),
                    null, txnManager, metaStore,
                    () -> { throw new RuntimeException("callback failed"); });
            s.close();
            assertThat(s.state()).isEqualTo(SessionState.CLOSED);
        }
    }
}
