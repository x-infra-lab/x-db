package io.github.xinfra.lab.xdb.session;

import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.meta.DatabaseInfo;
import io.github.xinfra.lab.xdb.meta.MetaStore;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SessionManagerImplTest {

    private SessionManagerImpl manager;
    private AtomicInteger rollbackCount;

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
            @Override public void putTableStats(long tableId, byte[] statsJson) {}
            @Override public byte[] getTableStats(long tableId) { return null; }
        };
    }

    @BeforeEach
    void setUp() {
        rollbackCount = new AtomicInteger();
        MetaStore metaStore = stubMetaStore();
        InfoSchemaHolder schemaHolder = new InfoSchemaHolder(metaStore);

        manager = new SessionManagerImpl(
                schemaHolder, null, metaStore,
                pessimistic -> new Object(),
                txn -> {},
                txn -> rollbackCount.incrementAndGet(),
                (txn, evalCtx) -> new TransactionContext(
                        (s, e, l) -> List.of(),
                        k -> null, (k, v) -> {}, k -> {}, evalCtx)
        );
    }

    // -----------------------------------------------------------------------
    // Session creation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Session creation")
    class SessionCreation {

        @Test
        @DisplayName("createSession returns non-null session")
        void createsSession() {
            Session session = manager.createSession();
            assertThat(session).isNotNull();
            assertThat(session.state()).isEqualTo(SessionState.IDLE);
        }

        @Test
        @DisplayName("each session gets a unique ID")
        void uniqueIds() {
            Session s1 = manager.createSession();
            Session s2 = manager.createSession();
            Session s3 = manager.createSession();
            assertThat(s1.id()).isNotEqualTo(s2.id());
            assertThat(s2.id()).isNotEqualTo(s3.id());
        }

        @Test
        @DisplayName("session IDs are monotonically increasing")
        void monotonicIds() {
            Session s1 = manager.createSession();
            Session s2 = manager.createSession();
            assertThat(s2.id()).isGreaterThan(s1.id());
        }

        @Test
        @DisplayName("created session is tracked in active sessions")
        void trackedOnCreate() {
            Session session = manager.createSession();
            assertThat(manager.getSession(session.id())).isSameAs(session);
            assertThat(manager.activeCount()).isEqualTo(1);
        }
    }

    // -----------------------------------------------------------------------
    // Session retrieval
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Session retrieval")
    class SessionRetrieval {

        @Test
        @DisplayName("getSession returns null for unknown ID")
        void unknownId() {
            assertThat(manager.getSession(999L)).isNull();
        }

        @Test
        @DisplayName("allSessions returns all active sessions")
        void allSessions() {
            manager.createSession();
            manager.createSession();
            manager.createSession();
            assertThat(manager.allSessions()).hasSize(3);
        }

        @Test
        @DisplayName("activeCount reflects session count")
        void activeCount() {
            assertThat(manager.activeCount()).isEqualTo(0);
            manager.createSession();
            assertThat(manager.activeCount()).isEqualTo(1);
            manager.createSession();
            assertThat(manager.activeCount()).isEqualTo(2);
        }
    }

    // -----------------------------------------------------------------------
    // Session removal via onClose callback
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Session removal")
    class SessionRemoval {

        @Test
        @DisplayName("closing a session removes it from the manager")
        void removedOnClose() {
            Session session = manager.createSession();
            long id = session.id();
            assertThat(manager.activeCount()).isEqualTo(1);

            session.close();
            assertThat(manager.getSession(id)).isNull();
            assertThat(manager.activeCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("removeSession for unknown ID is safe")
        void removeUnknownIsSafe() {
            manager.removeSession(999L);
            // no exception
        }

        @Test
        @DisplayName("closing one session does not affect others")
        void closeOneKeepsOthers() {
            Session s1 = manager.createSession();
            Session s2 = manager.createSession();

            s1.close();
            assertThat(manager.activeCount()).isEqualTo(1);
            assertThat(manager.getSession(s2.id())).isSameAs(s2);
        }
    }

    // -----------------------------------------------------------------------
    // close() — shutdown all sessions
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Manager close")
    class ManagerClose {

        @Test
        @DisplayName("close shuts down all sessions")
        void closesAll() {
            Session s1 = manager.createSession();
            Session s2 = manager.createSession();
            Session s3 = manager.createSession();

            manager.close();

            assertThat(s1.state()).isEqualTo(SessionState.CLOSED);
            assertThat(s2.state()).isEqualTo(SessionState.CLOSED);
            assertThat(s3.state()).isEqualTo(SessionState.CLOSED);
            assertThat(manager.activeCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("close rolls back active transactions")
        void rollsBackOnClose() {
            Session session = manager.createSession();
            session.begin(false);

            manager.close();

            assertThat(rollbackCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("close on empty manager is safe")
        void closeEmptyIsSafe() {
            manager.close();
            assertThat(manager.activeCount()).isEqualTo(0);
        }
    }

    // -----------------------------------------------------------------------
    // Concurrent operations
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Concurrent operations")
    class ConcurrentOps {

        @Test
        @DisplayName("concurrent session creation produces unique IDs")
        void concurrentCreate() throws Exception {
            int threadCount = 16;
            int sessionsPerThread = 100;
            Set<Long> ids = ConcurrentHashMap.newKeySet();
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);

            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            for (int t = 0; t < threadCount; t++) {
                pool.submit(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException ignored) {}
                    try {
                        for (int i = 0; i < sessionsPerThread; i++) {
                            Session s = manager.createSession();
                            ids.add(s.id());
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(ids).hasSize(threadCount * sessionsPerThread);
            assertThat(manager.activeCount()).isEqualTo(threadCount * sessionsPerThread);
            pool.shutdown();
        }

        @Test
        @DisplayName("concurrent close does not throw")
        void concurrentClose() throws Exception {
            for (int i = 0; i < 50; i++) {
                manager.createSession();
            }

            int threads = 8;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException ignored) {}
                    try {
                        manager.close();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(manager.activeCount()).isEqualTo(0);
            pool.shutdown();
        }
    }
}
