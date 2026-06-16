package io.github.xinfra.lab.xdb.test;

import io.github.xinfra.lab.xdb.ddl.DDLExecutor;
import io.github.xinfra.lab.xdb.ddl.DDLJob;
import io.github.xinfra.lab.xdb.ddl.DDLJobQueue;
import io.github.xinfra.lab.xdb.ddl.DDLOwnerManager;
import io.github.xinfra.lab.xdb.ddl.DDLState;
import io.github.xinfra.lab.xdb.ddl.DDLWorker;
import io.github.xinfra.lab.xdb.ddl.SchemaChangeExecutor;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.AggFunction;
import io.github.xinfra.lab.xdb.expression.AggFunctions;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.DatumComparator;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.DatabaseInfo;
import io.github.xinfra.lab.xdb.table.CopRequestCodec;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.AggFuncDesc;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.AggGroupResult;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.ColumnDesc;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.CopRequest;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.CopResponse;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.PartialAggState;
import io.github.xinfra.lab.xdb.table.KvPairDecoder;
import io.github.xinfra.lab.xdb.table.RowCodec;
import io.github.xinfra.lab.xdb.table.TableCodec;
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
                },
                (k, expected, newVal) -> {
                    synchronized (ownerKV) {
                        byte[] cur = null;
                        for (var entry : ownerKV.entrySet()) {
                            if (java.util.Arrays.equals(entry.getKey(), k)) { cur = entry.getValue(); break; }
                        }
                        if (java.util.Arrays.equals(cur, expected)) {
                            ownerKV.entrySet().removeIf(e -> java.util.Arrays.equals(e.getKey(), k));
                            ownerKV.put(k, newVal);
                            return true;
                        }
                        return false;
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
        TransactionContext.KVCopProcessor copProcessor =
                new InMemoryCopProcessor(t, evalContext);
        return new TransactionContext(
                t::scan,
                t::get,
                t::put,
                t::delete,
                copProcessor,
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
        @Override public long advanceSchemaVersion() { return ++schemaVersion; }
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

    static class InMemoryCopProcessor implements TransactionContext.KVCopProcessor {
        private final InMemoryTransaction txn;
        private final EvalContext evalCtx;

        InMemoryCopProcessor(InMemoryTransaction txn, EvalContext evalCtx) {
            this.txn = txn;
            this.evalCtx = evalCtx;
        }

        @Override
        public java.util.Iterator<CopRegionResult> scanParallel(
                int requestType, byte[] data, long startTs,
                byte[] startKey, byte[] endKey, int concurrency) {
            if (requestType != 1) return java.util.Collections.emptyIterator();

            CopRequest copReq = CopRequestCodec.decodeRequest(data);
            List<TransactionContext.KVPair> kvPairs = txn.scan(startKey, endKey, 100_000);

            List<Row> filteredRows = new ArrayList<>();
            List<TransactionContext.KVPair> filteredPairs = new ArrayList<>();
            for (TransactionContext.KVPair pair : kvPairs) {
                Row row = decodeRow(pair.key(), pair.value(), copReq);
                if (passesFilter(row, copReq.conditions())) {
                    filteredRows.add(row);
                    filteredPairs.add(pair);
                }
            }

            boolean hasAgg = copReq.aggFuncs() != null && !copReq.aggFuncs().isEmpty();
            boolean hasTopN = copReq.topNLimit() >= 0;

            byte[] responseData;
            if (hasAgg) {
                responseData = handleAggregation(filteredRows, copReq);
            } else if (hasTopN) {
                responseData = handleTopN(filteredRows, filteredPairs, copReq);
            } else {
                responseData = handleSelection(filteredPairs);
            }
            return List.of(new CopRegionResult(1, responseData, "")).iterator();
        }

        private byte[] handleSelection(List<TransactionContext.KVPair> filteredPairs) {
            List<KvPairDecoder.KvPair> result = new ArrayList<>(filteredPairs.size());
            for (TransactionContext.KVPair pair : filteredPairs) {
                result.add(new KvPairDecoder.KvPair(pair.key(), pair.value()));
            }
            byte[] kvData = KvPairDecoder.encode(result);
            return CopRequestCodec.encodeResponse(new CopResponse.FilteredRows(kvData));
        }

        private byte[] handleAggregation(List<Row> rows, CopRequest copReq) {
            List<Expression> groupByExprs = copReq.groupByExprs();
            List<AggFuncDesc> aggDescs = copReq.aggFuncs();

            Map<String, List<Datum>> groupKeyMap = new HashMap<>();
            Map<String, AggFunction[]> groupAggs = new HashMap<>();

            for (Row row : rows) {
                List<Datum> gk = new ArrayList<>();
                if (groupByExprs != null) {
                    for (Expression expr : groupByExprs) {
                        gk.add(expr.eval(evalCtx, row));
                    }
                }
                String key = groupKeyString(gk);
                groupKeyMap.putIfAbsent(key, gk);
                AggFunction[] aggs = groupAggs.computeIfAbsent(key,
                        k -> createAggFunctions(aggDescs));
                for (int i = 0; i < aggs.length; i++) {
                    Expression arg = aggDescs.get(i).arg();
                    Datum value = arg != null ? arg.eval(evalCtx, row) : Datum.of(1L);
                    aggs[i].update(value);
                }
            }

            List<AggGroupResult> resultGroups = new ArrayList<>();
            if (groupAggs.isEmpty() && (groupByExprs == null || groupByExprs.isEmpty())) {
                AggFunction[] emptyAggs = createAggFunctions(aggDescs);
                List<PartialAggState> states = new ArrayList<>();
                for (int i = 0; i < emptyAggs.length; i++) {
                    states.add(extractPartialState(emptyAggs[i], aggDescs.get(i)));
                }
                resultGroups.add(new AggGroupResult(List.of(), states));
            } else {
                for (var entry : groupAggs.entrySet()) {
                    List<Datum> keys = groupKeyMap.get(entry.getKey());
                    AggFunction[] aggs = entry.getValue();
                    List<PartialAggState> states = new ArrayList<>();
                    for (int i = 0; i < aggs.length; i++) {
                        states.add(extractPartialState(aggs[i], aggDescs.get(i)));
                    }
                    resultGroups.add(new AggGroupResult(keys, states));
                }
            }
            return CopRequestCodec.encodeResponse(new CopResponse.AggResult(resultGroups));
        }

        private byte[] handleTopN(List<Row> filteredRows,
                                  List<TransactionContext.KVPair> filteredPairs,
                                  CopRequest copReq) {
            long topNLimit = copReq.topNLimit();
            long topNOffset = copReq.topNOffset();
            List<Expression> orderByExprs = copReq.orderByExprs();
            List<Boolean> orderByAsc = copReq.orderByAsc();

            record IndexedRow(int idx, Datum[] sortKeys) {}
            List<IndexedRow> indexed = new ArrayList<>();
            for (int i = 0; i < filteredRows.size(); i++) {
                Row row = filteredRows.get(i);
                Datum[] sortKeys = new Datum[orderByExprs.size()];
                for (int j = 0; j < orderByExprs.size(); j++) {
                    sortKeys[j] = orderByExprs.get(j).eval(evalCtx, row);
                }
                indexed.add(new IndexedRow(i, sortKeys));
            }

            Comparator<IndexedRow> cmp = (a, b) -> {
                for (int i = 0; i < orderByExprs.size(); i++) {
                    int c = DatumComparator.compare(a.sortKeys[i], b.sortKeys[i]);
                    if (!orderByAsc.get(i)) c = -c;
                    if (c != 0) return c;
                }
                return 0;
            };
            indexed.sort(cmp);

            int start = (int) Math.min(topNOffset, indexed.size());
            int end = (int) Math.min(topNOffset + topNLimit, indexed.size());
            List<KvPairDecoder.KvPair> result = new ArrayList<>();
            for (int i = start; i < end; i++) {
                int idx = indexed.get(i).idx;
                TransactionContext.KVPair pair = filteredPairs.get(idx);
                result.add(new KvPairDecoder.KvPair(pair.key(), pair.value()));
            }
            byte[] kvData = KvPairDecoder.encode(result);
            return CopRequestCodec.encodeResponse(new CopResponse.FilteredRows(kvData));
        }

        private Row decodeRow(byte[] key, byte[] value, CopRequest copReq) {
            long handle = TableCodec.decodeRowHandle(key);
            Map<Long, Datum> colValues = RowCodec.decode(value);
            List<ColumnDesc> columns = copReq.columns();
            List<Integer> outputIndices = copReq.outputColumnIndices();

            Datum[] values = new Datum[outputIndices.size()];
            for (int i = 0; i < outputIndices.size(); i++) {
                ColumnDesc col = columns.get(outputIndices.get(i));
                Datum v = colValues.get(col.id());
                if (v != null) {
                    values[i] = v;
                } else if (col.autoIncrement()) {
                    values[i] = Datum.of(handle);
                } else {
                    values[i] = Datum.nil();
                }
            }
            return new Row(values);
        }

        private boolean passesFilter(Row row, List<Expression> conditions) {
            if (conditions == null || conditions.isEmpty()) return true;
            for (Expression cond : conditions) {
                Datum result = cond.eval(evalCtx, row);
                if (!result.toBoolean()) return false;
            }
            return true;
        }

        private AggFunction[] createAggFunctions(List<AggFuncDesc> descs) {
            AggFunction[] result = new AggFunction[descs.size()];
            for (int i = 0; i < descs.size(); i++) {
                AggFuncDesc desc = descs.get(i);
                result[i] = AggFunctions.create(desc.type(), desc.arg(), desc.distinct());
            }
            return result;
        }

        private PartialAggState extractPartialState(AggFunction agg, AggFuncDesc desc) {
            List<Datum> state = switch (agg.type()) {
                case COUNT -> List.of(Datum.of(agg.partialCount()));
                case SUM -> List.of(agg.result());
                case AVG -> List.of(Datum.of(agg.partialCount()),
                        agg.partialSum() != null ? Datum.of(agg.partialSum()) : Datum.nil());
                case MIN, MAX -> List.of(agg.result());
                case GROUP_CONCAT -> List.of(agg.result());
            };
            return new PartialAggState(desc.type(), desc.distinct(), state);
        }

        private String groupKeyString(List<Datum> keys) {
            StringBuilder sb = new StringBuilder();
            for (Datum d : keys) {
                sb.append(d.isNull() ? "NULL" : d.toStringValue()).append("|");
            }
            return sb.toString();
        }
    }
}
