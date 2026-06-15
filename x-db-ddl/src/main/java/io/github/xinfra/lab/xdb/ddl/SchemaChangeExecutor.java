package io.github.xinfra.lab.xdb.ddl;

import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.DatabaseInfo;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.MetaStore;
import io.github.xinfra.lab.xdb.meta.SchemaState;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.ddl.backfill.IndexBackfiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Executes F1 online schema state transitions for each DDL type.
 *
 * <p>The F1 protocol transitions schema elements through a series of intermediate
 * states so that at any given time, at most two adjacent schema versions are in
 * use across the cluster (the 2-version invariant).
 *
 * <p>State machines for each DDL type:
 * <ul>
 *   <li>CREATE_DATABASE: ABSENT -> PUBLIC (single step)</li>
 *   <li>DROP_DATABASE: PUBLIC -> ABSENT (single step)</li>
 *   <li>CREATE_TABLE: ABSENT -> PUBLIC (single step)</li>
 *   <li>DROP_TABLE: PUBLIC -> WRITE_ONLY -> DELETE_ONLY -> ABSENT (3 steps)</li>
 *   <li>ADD_COLUMN: ABSENT -> DELETE_ONLY -> WRITE_ONLY -> WRITE_REORGANIZATION -> PUBLIC</li>
 *   <li>DROP_COLUMN: PUBLIC -> WRITE_ONLY -> DELETE_ONLY -> ABSENT</li>
 *   <li>ADD_INDEX: ABSENT -> DELETE_ONLY -> WRITE_ONLY -> WRITE_REORGANIZATION -> PUBLIC</li>
 *   <li>DROP_INDEX: PUBLIC -> WRITE_ONLY -> DELETE_ONLY -> ABSENT</li>
 *   <li>TRUNCATE_TABLE: handled as a single-step operation</li>
 * </ul>
 */
public class SchemaChangeExecutor {
    private static final Logger log = LoggerFactory.getLogger(SchemaChangeExecutor.class);

    private final MetaStore metaStore;
    private final IndexBackfiller indexBackfiller;

    public SchemaChangeExecutor(MetaStore metaStore, IndexBackfiller indexBackfiller) {
        this.metaStore = metaStore;
        this.indexBackfiller = indexBackfiller;
    }

    public SchemaChangeExecutor(MetaStore metaStore) {
        this(metaStore, null);
    }

    /**
     * Execute one state transition for the given DDL job.
     * After execution, the job's schemaState is advanced to the next state,
     * or set to null if the operation is complete.
     */
    public void execute(DDLJob job) {
        switch (job.getType()) {
            case CREATE_DATABASE:
                executeCreateDatabase(job);
                break;
            case DROP_DATABASE:
                executeDropDatabase(job);
                break;
            case CREATE_TABLE:
                executeCreateTable(job);
                break;
            case DROP_TABLE:
                executeDropTable(job);
                break;
            case ADD_COLUMN:
                executeAddColumn(job);
                break;
            case DROP_COLUMN:
                executeDropColumn(job);
                break;
            case ADD_INDEX:
                executeAddIndex(job);
                break;
            case DROP_INDEX:
                executeDropIndex(job);
                break;
            case TRUNCATE_TABLE:
                executeTruncateTable(job);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported DDL type: " + job.getType());
        }
    }

    private void executeCreateDatabase(DDLJob job) {
        DatabaseInfo dbInfo = new DatabaseInfo();
        dbInfo.setId(job.getDbId());
        dbInfo.setName(job.getDbName());
        dbInfo.setState(SchemaState.PUBLIC);

        metaStore.createDatabase(dbInfo);
        long version = metaStore.advanceSchemaVersion();
        job.setVersion(version);
        job.setSchemaState(null); // done
        log.info("Created database: dbId={}, dbName={}, version={}", job.getDbId(), job.getDbName(), version);
    }

    private void executeDropDatabase(DDLJob job) {
        metaStore.dropDatabase(job.getDbId());
        long version = metaStore.advanceSchemaVersion();
        job.setVersion(version);
        job.setSchemaState(null); // done
        log.info("Dropped database: dbId={}, dbName={}, version={}", job.getDbId(), job.getDbName(), version);
    }

    private void executeCreateTable(DDLJob job) {
        TableInfo tableInfo = job.getTableInfo();
        tableInfo.setState(SchemaState.PUBLIC);

        // Set all columns to PUBLIC
        if (tableInfo.getColumns() != null) {
            for (ColumnInfo col : tableInfo.getColumns()) {
                col.setState(SchemaState.PUBLIC);
            }
        }

        // Set all indexes to PUBLIC
        if (tableInfo.getIndices() != null) {
            for (IndexInfo idx : tableInfo.getIndices()) {
                idx.setState(SchemaState.PUBLIC);
            }
        }

        metaStore.createTable(job.getDbId(), tableInfo);
        long version = metaStore.advanceSchemaVersion();
        job.setVersion(version);
        job.setSchemaState(null); // done
        log.info("Created table: dbId={}, tableId={}, tableName={}, version={}",
                job.getDbId(), tableInfo.getId(), tableInfo.getName(), version);
    }

    private void executeDropTable(DDLJob job) {
        SchemaState current = job.getSchemaState();
        SchemaState next = nextState(DDLType.DROP_TABLE, current);

        if (next == null) {
            // Final step: remove the table
            metaStore.dropTable(job.getDbId(), job.getTableId());
            long version = metaStore.advanceSchemaVersion();
            job.setVersion(version);
            job.setSchemaState(null); // done
            log.info("Dropped table: dbId={}, tableId={}, version={}", job.getDbId(), job.getTableId(), version);
        } else {
            // Intermediate step: update table state
            TableInfo tableInfo = metaStore.getTable(job.getDbId(), job.getTableId());
            if (tableInfo == null) {
                throw new RuntimeException("Table not found: dbId=" + job.getDbId() + ", tableId=" + job.getTableId());
            }
            tableInfo.setState(next);
            metaStore.updateTable(job.getDbId(), tableInfo);
            long version = metaStore.advanceSchemaVersion();
            job.setVersion(version);
            job.setSchemaState(next);
            log.info("Drop table state transition: tableId={}, {} -> {}, version={}",
                    job.getTableId(), current, next, version);
        }
    }

    private void executeAddColumn(DDLJob job) {
        SchemaState current = job.getSchemaState();
        SchemaState next = nextState(DDLType.ADD_COLUMN, current);

        TableInfo tableInfo = metaStore.getTable(job.getDbId(), job.getTableId());
        if (tableInfo == null) {
            throw new RuntimeException("Table not found: dbId=" + job.getDbId() + ", tableId=" + job.getTableId());
        }

        ColumnInfo columnInfo = job.getColumnInfo();

        if (next == null) {
            // Final step: column is now PUBLIC
            updateColumnState(tableInfo, columnInfo.getName(), SchemaState.PUBLIC);
            metaStore.updateTable(job.getDbId(), tableInfo);
            long version = metaStore.advanceSchemaVersion();
            job.setVersion(version);
            job.setSchemaState(null);
            log.info("Add column completed: tableId={}, column={}, version={}",
                    job.getTableId(), columnInfo.getName(), version);
        } else if (current == SchemaState.ABSENT) {
            // First step: add the column in DELETE_ONLY state
            columnInfo.setState(next);
            List<ColumnInfo> columns = tableInfo.getColumns();
            columns.add(columnInfo);
            tableInfo.setColumns(columns);
            metaStore.updateTable(job.getDbId(), tableInfo);
            long version = metaStore.advanceSchemaVersion();
            job.setVersion(version);
            job.setSchemaState(next);
            log.info("Add column state transition: tableId={}, column={}, ABSENT -> {}, version={}",
                    job.getTableId(), columnInfo.getName(), next, version);
        } else {
            // Intermediate steps: update column state
            updateColumnState(tableInfo, columnInfo.getName(), next);
            metaStore.updateTable(job.getDbId(), tableInfo);
            long version = metaStore.advanceSchemaVersion();
            job.setVersion(version);
            job.setSchemaState(next);
            log.info("Add column state transition: tableId={}, column={}, {} -> {}, version={}",
                    job.getTableId(), columnInfo.getName(), current, next, version);
        }
    }

    private void executeDropColumn(DDLJob job) {
        SchemaState current = job.getSchemaState();
        SchemaState next = nextState(DDLType.DROP_COLUMN, current);

        TableInfo tableInfo = metaStore.getTable(job.getDbId(), job.getTableId());
        if (tableInfo == null) {
            throw new RuntimeException("Table not found: dbId=" + job.getDbId() + ", tableId=" + job.getTableId());
        }

        ColumnInfo columnInfo = job.getColumnInfo();

        if (next == null) {
            // Final step: remove the column
            List<ColumnInfo> columns = tableInfo.getColumns();
            columns.removeIf(c -> c.getName().equals(columnInfo.getName()));
            tableInfo.setColumns(columns);
            metaStore.updateTable(job.getDbId(), tableInfo);
            long version = metaStore.advanceSchemaVersion();
            job.setVersion(version);
            job.setSchemaState(null);
            log.info("Drop column completed: tableId={}, column={}, version={}",
                    job.getTableId(), columnInfo.getName(), version);
        } else {
            // Intermediate steps: update column state
            updateColumnState(tableInfo, columnInfo.getName(), next);
            metaStore.updateTable(job.getDbId(), tableInfo);
            long version = metaStore.advanceSchemaVersion();
            job.setVersion(version);
            job.setSchemaState(next);
            log.info("Drop column state transition: tableId={}, column={}, {} -> {}, version={}",
                    job.getTableId(), columnInfo.getName(), current, next, version);
        }
    }

    private void executeAddIndex(DDLJob job) {
        SchemaState current = job.getSchemaState();
        SchemaState next = nextState(DDLType.ADD_INDEX, current);

        TableInfo tableInfo = metaStore.getTable(job.getDbId(), job.getTableId());
        if (tableInfo == null) {
            throw new RuntimeException("Table not found: dbId=" + job.getDbId() + ", tableId=" + job.getTableId());
        }

        IndexInfo indexInfo = job.getIndexInfo();

        if (next == null) {
            // Final step: index is now PUBLIC
            // Before going PUBLIC, backfill index data
            if (current == SchemaState.WRITE_REORGANIZATION && indexBackfiller != null) {
                log.info("Backfilling index: tableId={}, index={}", job.getTableId(), indexInfo.getName());
                indexBackfiller.backfill(tableInfo, indexInfo);
            }
            updateIndexState(tableInfo, indexInfo.getName(), SchemaState.PUBLIC);
            metaStore.updateTable(job.getDbId(), tableInfo);
            long version = metaStore.advanceSchemaVersion();
            job.setVersion(version);
            job.setSchemaState(null);
            log.info("Add index completed: tableId={}, index={}, version={}",
                    job.getTableId(), indexInfo.getName(), version);
        } else if (current == SchemaState.ABSENT) {
            // First step: add the index in DELETE_ONLY state
            indexInfo.setState(next);
            List<IndexInfo> indices = tableInfo.getIndices();
            indices.add(indexInfo);
            tableInfo.setIndices(indices);
            metaStore.updateTable(job.getDbId(), tableInfo);
            long version = metaStore.advanceSchemaVersion();
            job.setVersion(version);
            job.setSchemaState(next);
            log.info("Add index state transition: tableId={}, index={}, ABSENT -> {}, version={}",
                    job.getTableId(), indexInfo.getName(), next, version);
        } else {
            // Intermediate steps: update index state
            updateIndexState(tableInfo, indexInfo.getName(), next);
            metaStore.updateTable(job.getDbId(), tableInfo);
            long version = metaStore.advanceSchemaVersion();
            job.setVersion(version);
            job.setSchemaState(next);
            log.info("Add index state transition: tableId={}, index={}, {} -> {}, version={}",
                    job.getTableId(), indexInfo.getName(), current, next, version);
        }
    }

    private void executeDropIndex(DDLJob job) {
        SchemaState current = job.getSchemaState();
        SchemaState next = nextState(DDLType.DROP_INDEX, current);

        TableInfo tableInfo = metaStore.getTable(job.getDbId(), job.getTableId());
        if (tableInfo == null) {
            throw new RuntimeException("Table not found: dbId=" + job.getDbId() + ", tableId=" + job.getTableId());
        }

        IndexInfo indexInfo = job.getIndexInfo();

        if (next == null) {
            // Final step: remove the index
            List<IndexInfo> indices = tableInfo.getIndices();
            indices.removeIf(idx -> idx.getName().equals(indexInfo.getName()));
            tableInfo.setIndices(indices);
            metaStore.updateTable(job.getDbId(), tableInfo);
            long version = metaStore.advanceSchemaVersion();
            job.setVersion(version);
            job.setSchemaState(null);
            log.info("Drop index completed: tableId={}, index={}, version={}",
                    job.getTableId(), indexInfo.getName(), version);
        } else {
            // Intermediate steps: update index state
            updateIndexState(tableInfo, indexInfo.getName(), next);
            metaStore.updateTable(job.getDbId(), tableInfo);
            long version = metaStore.advanceSchemaVersion();
            job.setVersion(version);
            job.setSchemaState(next);
            log.info("Drop index state transition: tableId={}, index={}, {} -> {}, version={}",
                    job.getTableId(), indexInfo.getName(), current, next, version);
        }
    }

    private void executeTruncateTable(DDLJob job) {
        // Truncate is handled as a single-step operation:
        // drop all data but keep the table metadata
        metaStore.truncateTable(job.getDbId(), job.getTableId());
        long version = metaStore.advanceSchemaVersion();
        job.setVersion(version);
        job.setSchemaState(null); // done
        log.info("Truncated table: dbId={}, tableId={}, version={}", job.getDbId(), job.getTableId(), version);
    }

    /**
     * Roll back a failed DDL job to ABSENT (for ADD operations) or restore to PUBLIC
     * (for DROP operations), undoing any partially-applied schema changes.
     */
    public void rollback(DDLJob job, SchemaState failedAt) {
        switch (job.getType()) {
            case CREATE_DATABASE:
            case DROP_DATABASE:
            case CREATE_TABLE:
            case TRUNCATE_TABLE:
                break;

            case ADD_COLUMN:
                rollbackAddColumn(job);
                break;
            case ADD_INDEX:
                rollbackAddIndex(job);
                break;

            case DROP_TABLE:
                rollbackDropTable(job, failedAt);
                break;
            case DROP_COLUMN:
                rollbackDropColumn(job, failedAt);
                break;
            case DROP_INDEX:
                rollbackDropIndex(job, failedAt);
                break;

            default:
                log.warn("No rollback handler for DDL type: {}", job.getType());
        }
    }

    private void rollbackAddColumn(DDLJob job) {
        TableInfo tableInfo = metaStore.getTable(job.getDbId(), job.getTableId());
        if (tableInfo == null) return;
        ColumnInfo columnInfo = job.getColumnInfo();
        List<ColumnInfo> columns = tableInfo.getColumns();
        if (columns != null) {
            columns.removeIf(c -> c.getName().equals(columnInfo.getName()));
            tableInfo.setColumns(columns);
            metaStore.updateTable(job.getDbId(), tableInfo);
            metaStore.advanceSchemaVersion();
            log.info("Rolled back ADD_COLUMN: removed column '{}' from table {}", columnInfo.getName(), job.getTableId());
        }
    }

    private void rollbackAddIndex(DDLJob job) {
        TableInfo tableInfo = metaStore.getTable(job.getDbId(), job.getTableId());
        if (tableInfo == null) return;
        IndexInfo indexInfo = job.getIndexInfo();
        List<IndexInfo> indices = tableInfo.getIndices();
        if (indices != null) {
            indices.removeIf(idx -> idx.getName().equals(indexInfo.getName()));
            tableInfo.setIndices(indices);
            metaStore.updateTable(job.getDbId(), tableInfo);
            metaStore.advanceSchemaVersion();
            log.info("Rolled back ADD_INDEX: removed index '{}' from table {}", indexInfo.getName(), job.getTableId());
        }
    }

    private void rollbackDropTable(DDLJob job, SchemaState failedAt) {
        TableInfo tableInfo = metaStore.getTable(job.getDbId(), job.getTableId());
        if (tableInfo == null) return;
        tableInfo.setState(SchemaState.PUBLIC);
        metaStore.updateTable(job.getDbId(), tableInfo);
        metaStore.advanceSchemaVersion();
        log.info("Rolled back DROP_TABLE: restored table {} to PUBLIC from {}", job.getTableId(), failedAt);
    }

    private void rollbackDropColumn(DDLJob job, SchemaState failedAt) {
        TableInfo tableInfo = metaStore.getTable(job.getDbId(), job.getTableId());
        if (tableInfo == null) return;
        ColumnInfo columnInfo = job.getColumnInfo();
        updateColumnState(tableInfo, columnInfo.getName(), SchemaState.PUBLIC);
        metaStore.updateTable(job.getDbId(), tableInfo);
        metaStore.advanceSchemaVersion();
        log.info("Rolled back DROP_COLUMN: restored column '{}' to PUBLIC from {}", columnInfo.getName(), failedAt);
    }

    private void rollbackDropIndex(DDLJob job, SchemaState failedAt) {
        TableInfo tableInfo = metaStore.getTable(job.getDbId(), job.getTableId());
        if (tableInfo == null) return;
        IndexInfo indexInfo = job.getIndexInfo();
        updateIndexState(tableInfo, indexInfo.getName(), SchemaState.PUBLIC);
        metaStore.updateTable(job.getDbId(), tableInfo);
        metaStore.advanceSchemaVersion();
        log.info("Rolled back DROP_INDEX: restored index '{}' to PUBLIC from {}", indexInfo.getName(), failedAt);
    }

    /**
     * Returns the next schema state in the F1 state machine for the given DDL type
     * and current state. Returns null if the current state is the final state.
     */
    SchemaState nextState(DDLType type, SchemaState current) {
        switch (type) {
            case CREATE_DATABASE:
            case CREATE_TABLE:
            case TRUNCATE_TABLE:
                // Single-step: always done
                return null;

            case DROP_DATABASE:
                return null;

            case DROP_TABLE:
                // PUBLIC -> WRITE_ONLY -> DELETE_ONLY -> ABSENT (null = remove)
                if (current == SchemaState.PUBLIC) return SchemaState.WRITE_ONLY;
                if (current == SchemaState.WRITE_ONLY) return SchemaState.DELETE_ONLY;
                if (current == SchemaState.DELETE_ONLY) return null;
                return null;

            case ADD_COLUMN:
            case ADD_INDEX:
                // ABSENT -> DELETE_ONLY -> WRITE_ONLY -> WRITE_REORGANIZATION -> PUBLIC (null = done)
                if (current == SchemaState.ABSENT) return SchemaState.DELETE_ONLY;
                if (current == SchemaState.DELETE_ONLY) return SchemaState.WRITE_ONLY;
                if (current == SchemaState.WRITE_ONLY) return SchemaState.WRITE_REORGANIZATION;
                if (current == SchemaState.WRITE_REORGANIZATION) return null;
                return null;

            case DROP_COLUMN:
            case DROP_INDEX:
                // PUBLIC -> WRITE_ONLY -> DELETE_ONLY -> ABSENT (null = remove)
                if (current == SchemaState.PUBLIC) return SchemaState.WRITE_ONLY;
                if (current == SchemaState.WRITE_ONLY) return SchemaState.DELETE_ONLY;
                if (current == SchemaState.DELETE_ONLY) return null;
                return null;

            default:
                return null;
        }
    }

    private void updateColumnState(TableInfo tableInfo, String columnName, SchemaState state) {
        if (tableInfo.getColumns() == null) {
            return;
        }
        for (ColumnInfo col : tableInfo.getColumns()) {
            if (col.getName().equals(columnName)) {
                col.setState(state);
                return;
            }
        }
        throw new RuntimeException("Column not found: " + columnName + " in table " + tableInfo.getName());
    }

    private void updateIndexState(TableInfo tableInfo, String indexName, SchemaState state) {
        if (tableInfo.getIndices() == null) {
            return;
        }
        for (IndexInfo idx : tableInfo.getIndices()) {
            if (idx.getName().equals(indexName)) {
                idx.setState(state);
                return;
            }
        }
        throw new RuntimeException("Index not found: " + indexName + " in table " + tableInfo.getName());
    }
}
