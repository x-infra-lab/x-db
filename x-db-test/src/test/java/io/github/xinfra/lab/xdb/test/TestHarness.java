package io.github.xinfra.lab.xdb.test;

import io.github.xinfra.lab.xdb.ddl.DDLExecutor;
import io.github.xinfra.lab.xdb.ddl.DDLJob;
import io.github.xinfra.lab.xdb.ddl.DDLJobQueue;
import io.github.xinfra.lab.xdb.ddl.DDLOwnerManager;
import io.github.xinfra.lab.xdb.ddl.DDLState;
import io.github.xinfra.lab.xdb.ddl.DDLWorker;
import io.github.xinfra.lab.xdb.ddl.SchemaChangeExecutor;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.meta.DatabaseInfo;
import io.github.xinfra.lab.xdb.meta.MetaStore;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.session.InfoSchemaHolder;
import io.github.xinfra.lab.xdb.session.Session;
import io.github.xinfra.lab.xdb.session.SessionManagerImpl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class TestHarness implements AutoCloseable {

    private final InMemoryKVStore kvStore;
    private final InMemoryMetaStore metaStore;
    private final InMemoryDDLJobQueue jobQueue;
    private final DDLOwnerManager ownerManager;
    private final SchemaChangeExecutor schemaChangeExecutor;
    private final DDLWorker ddlWorker;
    private final InfoSchemaHolder schemaHolder;
    private final DDLExecutor ddlExecutor;
    private final SessionManagerImpl sessionManager;
    private final Thread workerThread;

    public TestHarness() {
        this.kvStore = new InMemoryKVStore();
        this.metaStore = new InMemoryMetaStore();
        this.jobQueue = new InMemoryDDLJobQueue();

        Map<byte[], byte[]> ownerKV = new HashMap<>();
        this.ownerManager = new DDLOwnerManager(
                "test-node",
                k -> {
                    synchronized (ownerKV) {
                        for (var entry : ownerKV.entrySet()) {
                            if (java.util.Arrays.equals(entry.getKey(), k)) {
                                return entry.getValue();
                            }
                        }
                        return null;
                    }
                },
                (k, v) -> {
                    synchronized (ownerKV) {
                        ownerKV.entrySet().removeIf(e -> java.util.Arrays.equals(e.getKey(), k));
                        ownerKV.put(k, v);
                    }
                }
        );

        this.schemaHolder = new InfoSchemaHolder(metaStore);
        this.schemaChangeExecutor = new SchemaChangeExecutor(metaStore);
        this.ddlWorker = new DDLWorker(ownerManager, jobQueue, schemaChangeExecutor,
                schemaHolder::refresh);
        this.ddlExecutor = new DDLExecutor(jobQueue, 60_000);

        this.sessionManager = new SessionManagerImpl(
                schemaHolder,
                ddlExecutor,
                metaStore,
                this::beginTxn,
                this::commitTxn,
                this::rollbackTxn,
                this::createTxnContext
        );

        this.workerThread = new Thread(ddlWorker, "ddl-worker-harness");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    public Session createSession() {
        return sessionManager.createSession();
    }

    public MetaStore metaStore() {
        return metaStore;
    }

    public InfoSchemaHolder schemaHolder() {
        return schemaHolder;
    }

    @Override
    public void close() {
        ddlWorker.shutdown();
        workerThread.interrupt();
        try {
            workerThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sessionManager.close();
    }

    private Object beginTxn(boolean pessimistic) {
        return new InMemoryTransaction(kvStore);
    }

    private void commitTxn(Object txn) {
        ((InMemoryTransaction) txn).commit();
    }

    private void rollbackTxn(Object txn) {
        ((InMemoryTransaction) txn).rollback();
    }

    private TransactionContext createTxnContext(Object txn, EvalContext evalContext) {
        InMemoryTransaction t = (InMemoryTransaction) txn;
        return new TransactionContext(
                t::scan,
                t::get,
                t::put,
                t::delete,
                evalContext
        );
    }

    static class InMemoryTransaction {
        private final InMemoryKVStore store;
        private boolean committed = false;

        InMemoryTransaction(InMemoryKVStore store) {
            this.store = store;
        }

        byte[] get(byte[] key) {
            return store.get(key);
        }

        void put(byte[] key, byte[] value) {
            store.put(key, value);
        }

        void delete(byte[] key) {
            store.delete(key);
        }

        java.util.List<TransactionContext.KVPair> scan(byte[] startKey, byte[] endKey, int limit) {
            return store.scan(startKey, endKey, limit);
        }

        void commit() {
            committed = true;
        }

        void rollback() {
            // In-memory store writes are immediate, so rollback is a no-op.
            // A more realistic implementation would buffer writes until commit.
        }
    }

    static class InMemoryMetaStore implements MetaStore {
        private long schemaVersion;
        private final Map<Long, DatabaseInfo> databases = new HashMap<>();
        private final Map<Long, Map<Long, TableInfo>> tables = new HashMap<>();
        private final Map<Long, AtomicLong> autoIncIds = new HashMap<>();
        private final AtomicLong globalIdGen = new AtomicLong(1000);

        @Override public long getSchemaVersion() { return schemaVersion; }
        @Override public void setSchemaVersion(long version) { this.schemaVersion = version; }
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
        @Override public long allocAutoIncId(long tableId, int batchSize) {
            return autoIncIds.computeIfAbsent(tableId, k -> new AtomicLong(0)).addAndGet(batchSize);
        }
        @Override public long allocGlobalId() { return globalIdGen.incrementAndGet(); }
    }

    static class InMemoryDDLJobQueue implements DDLJobQueue {
        private final ConcurrentHashMap<Long, DDLJob> queue = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, DDLJob> history = new ConcurrentHashMap<>();
        private final AtomicLong jobIdGen = new AtomicLong(0);

        @Override public void enqueue(DDLJob job) {
            if (job.getId() == 0) job.setId(allocJobId());
            if (job.getState() == null || job.getState() == DDLState.NONE) job.setState(DDLState.QUEUED);
            job.setStartTs(System.currentTimeMillis());
            queue.put(job.getId(), job);
        }
        @Override public DDLJob dequeue() {
            return queue.values().stream()
                    .filter(j -> j.getState() == DDLState.QUEUED)
                    .min(Comparator.comparingLong(DDLJob::getId))
                    .orElse(null);
        }
        @Override public DDLJob getJob(long jobId) {
            DDLJob job = queue.get(jobId);
            return job != null ? job : history.get(jobId);
        }
        @Override public void updateJob(DDLJob job) { queue.put(job.getId(), job); }
        @Override public List<DDLJob> listJobs() { return new ArrayList<>(queue.values()); }
        @Override public void moveToHistory(DDLJob job) { queue.remove(job.getId()); history.put(job.getId(), job); }
        @Override public List<DDLJob> listHistory(int limit) {
            return history.values().stream()
                    .sorted(Comparator.comparingLong(DDLJob::getFinishTs).reversed())
                    .limit(limit).toList();
        }
        @Override public long allocJobId() { return jobIdGen.incrementAndGet(); }
    }
}
