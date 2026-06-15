package io.github.xinfra.lab.xdb.session;

import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.meta.DatabaseInfo;
import io.github.xinfra.lab.xdb.meta.MetaStore;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionImplTest {

    private AtomicInteger rollbackCount;
    private SessionImpl session;

    static MetaStore stubMetaStore() {
        return new MetaStore() {
            @Override public long getSchemaVersion() { return 0; }
            @Override public void setSchemaVersion(long version) {}
            @Override public long advanceSchemaVersion() { return 1; }
            @Override public void createDatabase(DatabaseInfo db) {}
            @Override public void dropDatabase(long dbId) {}
            @Override public DatabaseInfo getDatabase(long dbId) { return null; }
            @Override public List<Long> listDatabaseIds() { return List.of(); }
            @Override public void createTable(long dbId, TableInfo table) {}
            @Override public void updateTable(long dbId, TableInfo table) {}
            @Override public void dropTable(long dbId, long tableId) {}
            @Override public TableInfo getTable(long dbId, long tableId) { return null; }
            @Override public List<Long> listTableIds(long dbId) { return List.of(); }
            @Override public long allocAutoIncId(long tableId, int batchSize) { return 1; }
            @Override public long allocGlobalId() { return 1; }
        };
    }

    @BeforeEach
    void setUp() {
        rollbackCount = new AtomicInteger();
        MetaStore metaStore = stubMetaStore();
        InfoSchemaHolder schemaHolder = new InfoSchemaHolder(metaStore);

        TransactionManager txnManager = new TransactionManager(
                pessimistic -> new Object(),
                txn -> {},
                txn -> rollbackCount.incrementAndGet(),
                (txn, evalCtx) -> new TransactionContext(
                        (s, e, l) -> List.of(),
                        k -> null, (k, v) -> {}, k -> {}, evalCtx)
        );

        session = new SessionImpl(1L, schemaHolder, null, txnManager, metaStore);
    }

    @Test
    void closeTwiceIsIdempotent() {
        session.close();
        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        session.close();
        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
    }

    @Test
    void executeAfterCloseThrows() {
        session.close();
        assertThatThrownBy(() -> session.execute("SELECT 1"))
                .isInstanceOf(XDBException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void concurrentCloseOnlyRollsBackOnce() throws Exception {
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
    }

    @Test
    void currentDatabaseIsVolatileVisible() throws Exception {
        assertThat(session.currentDatabase()).isNull();

        CountDownLatch done = new CountDownLatch(1);
        new Thread(() -> {
            assertThat(session.state()).isEqualTo(SessionState.IDLE);
            assertThat(session.currentDatabase()).isNull();
            done.countDown();
        }).start();
        done.await();
    }
}
