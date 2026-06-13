package io.github.xinfra.lab.xdb.ddl;

import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.meta.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DDLWorkerTest {

    private InMemoryMetaStore metaStore;
    private InMemoryDDLJobQueue jobQueue;
    private DDLOwnerManager ownerManager;
    private SchemaChangeExecutor schemaChangeExecutor;
    private DDLWorker worker;
    private Thread workerThread;
    private final Map<byte[], byte[]> kvStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        metaStore = new InMemoryMetaStore();
        jobQueue = new InMemoryDDLJobQueue();
        ownerManager = new DDLOwnerManager(
                "test-node",
                k -> {
                    synchronized (kvStore) {
                        for (var entry : kvStore.entrySet()) {
                            if (java.util.Arrays.equals(entry.getKey(), k)) {
                                return entry.getValue();
                            }
                        }
                        return null;
                    }
                },
                (k, v) -> {
                    synchronized (kvStore) {
                        // Remove old key if exists (byte[] equality)
                        kvStore.entrySet().removeIf(e -> java.util.Arrays.equals(e.getKey(), k));
                        kvStore.put(k, v);
                    }
                }
        );
        schemaChangeExecutor = new SchemaChangeExecutor(metaStore);
        worker = new DDLWorker(ownerManager, jobQueue, schemaChangeExecutor, () -> {});
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (worker != null) {
            worker.shutdown();
        }
        if (workerThread != null && workerThread.isAlive()) {
            workerThread.interrupt();
            workerThread.join(5000);
        }
    }

    private void startWorker() {
        workerThread = new Thread(worker, "ddl-worker-test");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    // -----------------------------------------------------------------------
    // Owner election
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Worker becomes owner and processes jobs")
    void workerBecomesOwner() throws InterruptedException {
        DDLJob job = new DDLJob();
        job.setType(DDLType.CREATE_DATABASE);
        job.setDbId(1);
        job.setDbName("testdb");
        job.setSchemaState(SchemaState.ABSENT);
        jobQueue.enqueue(job);
        long jobId = job.getId();

        startWorker();

        assertJobDone(jobId, 10_000);

        DDLJob completed = jobQueue.getJob(jobId);
        assertThat(completed.getState()).isEqualTo(DDLState.DONE);
        assertThat(metaStore.getDatabase(1)).isNotNull();
        assertThat(metaStore.getDatabase(1).getName()).isEqualTo("testdb");
    }

    // -----------------------------------------------------------------------
    // Single-step DDL (CREATE DATABASE)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CREATE DATABASE job completes in one cycle")
    void createDatabaseJob() throws InterruptedException {
        DDLJob job = new DDLJob();
        job.setType(DDLType.CREATE_DATABASE);
        job.setDbId(2);
        job.setDbName("mydb");
        job.setSchemaState(SchemaState.ABSENT);
        jobQueue.enqueue(job);
        long jobId = job.getId();

        startWorker();
        assertJobDone(jobId, 10_000);

        DDLJob done = jobQueue.getJob(jobId);
        assertThat(done.getState()).isEqualTo(DDLState.DONE);
        assertThat(done.getSchemaState()).isNull();
        assertThat(done.getVersion()).isGreaterThan(0);
        assertThat(done.getFinishTs()).isGreaterThan(0);
    }

    // -----------------------------------------------------------------------
    // Multi-step DDL (CREATE TABLE, then DROP TABLE)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CREATE TABLE followed by DROP TABLE processes through full state machine")
    void createThenDropTable() throws InterruptedException {
        // First, create a database
        DDLJob createDbJob = new DDLJob();
        createDbJob.setType(DDLType.CREATE_DATABASE);
        createDbJob.setDbId(1);
        createDbJob.setDbName("testdb");
        createDbJob.setSchemaState(SchemaState.ABSENT);
        jobQueue.enqueue(createDbJob);

        startWorker();
        assertJobDone(createDbJob.getId(), 10_000);

        // Create a table
        TableInfo tableInfo = buildSimpleTable(100, "users");
        DDLJob createTableJob = new DDLJob();
        createTableJob.setType(DDLType.CREATE_TABLE);
        createTableJob.setDbId(1);
        createTableJob.setTableId(100);
        createTableJob.setTableInfo(tableInfo);
        createTableJob.setSchemaState(SchemaState.ABSENT);
        jobQueue.enqueue(createTableJob);

        assertJobDone(createTableJob.getId(), 10_000);
        assertThat(metaStore.getTable(1, 100)).isNotNull();
        assertThat(metaStore.getTable(1, 100).getState()).isEqualTo(SchemaState.PUBLIC);

        // Drop the table (multi-step: PUBLIC → WRITE_ONLY → DELETE_ONLY → removed)
        DDLJob dropTableJob = new DDLJob();
        dropTableJob.setType(DDLType.DROP_TABLE);
        dropTableJob.setDbId(1);
        dropTableJob.setTableId(100);
        dropTableJob.setSchemaState(SchemaState.PUBLIC);
        jobQueue.enqueue(dropTableJob);

        // DROP TABLE has 3 steps with STATE_WAIT_MS=4000 between each, so allow longer timeout
        assertJobDone(dropTableJob.getId(), 30_000);

        DDLJob done = jobQueue.getJob(dropTableJob.getId());
        assertThat(done.getState()).isEqualTo(DDLState.DONE);
        assertThat(metaStore.getTable(1, 100)).isNull();
    }

    // -----------------------------------------------------------------------
    // Schema version advancement
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Schema version advances with each DDL operation")
    void schemaVersionAdvances() throws InterruptedException {
        long initialVersion = metaStore.getSchemaVersion();

        DDLJob job = new DDLJob();
        job.setType(DDLType.CREATE_DATABASE);
        job.setDbId(1);
        job.setDbName("db1");
        job.setSchemaState(SchemaState.ABSENT);
        jobQueue.enqueue(job);

        startWorker();
        assertJobDone(job.getId(), 10_000);

        assertThat(metaStore.getSchemaVersion()).isGreaterThan(initialVersion);
    }

    // -----------------------------------------------------------------------
    // Job moved to history
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Completed jobs are moved to history")
    void jobMovedToHistory() throws InterruptedException {
        DDLJob job = new DDLJob();
        job.setType(DDLType.CREATE_DATABASE);
        job.setDbId(1);
        job.setDbName("db1");
        job.setSchemaState(SchemaState.ABSENT);
        jobQueue.enqueue(job);
        long jobId = job.getId();

        startWorker();
        assertJobDone(jobId, 10_000);

        assertThat(jobQueue.listJobs()).isEmpty();
        assertThat(jobQueue.listHistory(10)).hasSize(1);
        assertThat(jobQueue.listHistory(10).get(0).getId()).isEqualTo(jobId);
    }

    // -----------------------------------------------------------------------
    // Shutdown
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Worker stops gracefully on shutdown")
    void workerShutdown() throws InterruptedException {
        startWorker();
        Thread.sleep(500);

        assertThat(worker.isRunning()).isTrue();
        worker.shutdown();
        workerThread.join(5000);
        assertThat(worker.isRunning()).isFalse();
    }

    // -----------------------------------------------------------------------
    // Multiple jobs processed sequentially
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Multiple jobs processed in order")
    void multipleJobs() throws InterruptedException {
        DDLJob job1 = new DDLJob();
        job1.setType(DDLType.CREATE_DATABASE);
        job1.setDbId(1);
        job1.setDbName("db1");
        job1.setSchemaState(SchemaState.ABSENT);
        jobQueue.enqueue(job1);

        DDLJob job2 = new DDLJob();
        job2.setType(DDLType.CREATE_DATABASE);
        job2.setDbId(2);
        job2.setDbName("db2");
        job2.setSchemaState(SchemaState.ABSENT);
        jobQueue.enqueue(job2);

        startWorker();
        assertJobDone(job1.getId(), 10_000);
        assertJobDone(job2.getId(), 10_000);

        assertThat(metaStore.getDatabase(1)).isNotNull();
        assertThat(metaStore.getDatabase(2)).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void assertJobDone(long jobId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            DDLJob job = jobQueue.getJob(jobId);
            if (job != null && (job.getState() == DDLState.DONE || job.getState() == DDLState.FAILED)) {
                return;
            }
            Thread.sleep(100);
        }
        DDLJob job = jobQueue.getJob(jobId);
        assertThat(job).as("Job %d should exist", jobId).isNotNull();
        assertThat(job.getState())
                .as("Job %d should be DONE or FAILED, was %s", jobId, job.getState())
                .isIn(DDLState.DONE, DDLState.FAILED);
    }

    private TableInfo buildSimpleTable(long id, String name) {
        ColumnInfo idCol = new ColumnInfo();
        idCol.setId(1);
        idCol.setName("id");
        idCol.setType(DataType.BIGINT);
        idCol.setOffset(0);

        ColumnInfo nameCol = new ColumnInfo();
        nameCol.setId(2);
        nameCol.setName("name");
        nameCol.setType(DataType.VARCHAR);
        nameCol.setOffset(1);

        IndexInfo pk = new IndexInfo();
        pk.setId(1);
        pk.setName("PRIMARY");
        pk.setTableId(id);
        pk.setPrimary(true);
        pk.setUnique(true);
        pk.setColumns(List.of(new IndexColumn("id", 1, 0)));

        TableInfo table = new TableInfo();
        table.setId(id);
        table.setName(name);
        table.setDbId(1);
        table.setColumns(new ArrayList<>(List.of(idCol, nameCol)));
        table.setIndices(new ArrayList<>(List.of(pk)));
        table.setMaxColumnId(2);
        table.setMaxIndexId(1);
        return table;
    }
}
