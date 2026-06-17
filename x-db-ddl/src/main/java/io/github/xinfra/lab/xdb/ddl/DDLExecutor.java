package io.github.xinfra.lab.xdb.ddl;

import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.DatabaseInfo;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.SchemaState;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-level API for submitting DDL operations.
 *
 * <p>Each method creates a {@link DDLJob}, enqueues it, and polls
 * until the job completes or times out. The actual execution is
 * performed asynchronously by the {@link DDLWorker}.
 */
public class DDLExecutor {
    private static final Logger log = LoggerFactory.getLogger(DDLExecutor.class);

    private static final long DEFAULT_POLL_INTERVAL_MS = 100;

    private final DDLJobQueue jobQueue;
    private final long waitTimeoutMs;

    public DDLExecutor(DDLJobQueue jobQueue, long waitTimeoutMs) {
        this.jobQueue = jobQueue;
        this.waitTimeoutMs = waitTimeoutMs;
    }

    public void executeCreateDatabase(DatabaseInfo db) {
        DDLJob job = new DDLJob();
        job.setType(DDLType.CREATE_DATABASE);
        job.setDbId(db.getId());
        job.setDbName(db.getName());
        job.setSchemaState(SchemaState.ABSENT);
        submitAndWait(job);
    }

    public void executeDropDatabase(long dbId, String dbName) {
        DDLJob job = new DDLJob();
        job.setType(DDLType.DROP_DATABASE);
        job.setDbId(dbId);
        job.setDbName(dbName);
        job.setSchemaState(SchemaState.PUBLIC);
        submitAndWait(job);
    }

    public void executeCreateTable(long dbId, TableInfo table) {
        DDLJob job = new DDLJob();
        job.setType(DDLType.CREATE_TABLE);
        job.setDbId(dbId);
        job.setTableId(table.getId());
        job.setTableName(table.getName());
        job.setTableInfo(table);
        job.setSchemaState(SchemaState.ABSENT);
        submitAndWait(job);
    }

    public void executeDropTable(long dbId, long tableId, String tableName) {
        DDLJob job = new DDLJob();
        job.setType(DDLType.DROP_TABLE);
        job.setDbId(dbId);
        job.setTableId(tableId);
        job.setTableName(tableName);
        job.setSchemaState(SchemaState.PUBLIC);
        submitAndWait(job);
    }

    public void executeAddColumn(long dbId, long tableId, ColumnInfo column) {
        DDLJob job = new DDLJob();
        job.setType(DDLType.ADD_COLUMN);
        job.setDbId(dbId);
        job.setTableId(tableId);
        job.setColumnInfo(column);
        job.setSchemaState(SchemaState.ABSENT);
        submitAndWait(job);
    }

    public void executeDropColumn(long dbId, long tableId, String columnName) {
        DDLJob job = new DDLJob();
        job.setType(DDLType.DROP_COLUMN);
        job.setDbId(dbId);
        job.setTableId(tableId);

        ColumnInfo columnInfo = new ColumnInfo();
        columnInfo.setName(columnName);
        job.setColumnInfo(columnInfo);
        job.setSchemaState(SchemaState.PUBLIC);
        submitAndWait(job);
    }

    public void executeAddIndex(long dbId, long tableId, IndexInfo index) {
        DDLJob job = new DDLJob();
        job.setType(DDLType.ADD_INDEX);
        job.setDbId(dbId);
        job.setTableId(tableId);
        job.setIndexInfo(index);
        job.setSchemaState(SchemaState.ABSENT);
        submitAndWait(job);
    }

    public void executeDropIndex(long dbId, long tableId, String indexName) {
        DDLJob job = new DDLJob();
        job.setType(DDLType.DROP_INDEX);
        job.setDbId(dbId);
        job.setTableId(tableId);

        IndexInfo indexInfo = new IndexInfo();
        indexInfo.setName(indexName);
        job.setIndexInfo(indexInfo);
        job.setSchemaState(SchemaState.PUBLIC);
        submitAndWait(job);
    }

    public void executeTruncateTable(long dbId, long tableId, String tableName) {
        DDLJob job = new DDLJob();
        job.setType(DDLType.TRUNCATE_TABLE);
        job.setDbId(dbId);
        job.setTableId(tableId);
        job.setTableName(tableName);
        job.setSchemaState(SchemaState.PUBLIC);
        submitAndWait(job);
    }

    /**
     * Submit a DDL job and wait for completion or timeout.
     *
     * @param job the DDL job to submit
     * @return the completed job
     * @throws XDBException if the job fails, is cancelled, or times out
     */
    private DDLJob submitAndWait(DDLJob job) {
        jobQueue.enqueue(job);
        log.info("Submitted DDL job: id={}, type={}", job.getId(), job.getType());

        long deadline = System.currentTimeMillis() + waitTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            DDLJob current = jobQueue.getJob(job.getId());
            if (current == null) {
                // Job was moved to history and completed
                return current;
            }
            if (current.getState() == DDLState.DONE) {
                return current;
            }
            if (current.getState() == DDLState.FAILED) {
                throw XDBException.ddlFailed(current.getError());
            }
            if (current.getState() == DDLState.CANCELLED) {
                throw XDBException.ddlCancelled(current.getError());
            }
            try {
                Thread.sleep(DEFAULT_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw XDBException.ddlInterrupted();
            }
        }
        throw XDBException.ddlTimeout(job.getId(), waitTimeoutMs);
    }
}
