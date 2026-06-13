package io.github.xinfra.lab.xdb.ddl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.SchemaState;
import io.github.xinfra.lab.xdb.meta.TableInfo;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DDLJob {
    private long id;
    private DDLType type;
    private DDLState state;
    private long dbId;
    private String dbName;
    private long tableId;
    private String tableName;
    private TableInfo tableInfo;
    private ColumnInfo columnInfo;
    private IndexInfo indexInfo;
    private SchemaState schemaState;
    private String error;
    private long startTs;
    private long finishTs;
    private long version;

    public DDLJob() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public DDLType getType() {
        return type;
    }

    public void setType(DDLType type) {
        this.type = type;
    }

    public DDLState getState() {
        return state;
    }

    public void setState(DDLState state) {
        this.state = state;
    }

    public long getDbId() {
        return dbId;
    }

    public void setDbId(long dbId) {
        this.dbId = dbId;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public long getTableId() {
        return tableId;
    }

    public void setTableId(long tableId) {
        this.tableId = tableId;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public TableInfo getTableInfo() {
        return tableInfo;
    }

    public void setTableInfo(TableInfo tableInfo) {
        this.tableInfo = tableInfo;
    }

    public ColumnInfo getColumnInfo() {
        return columnInfo;
    }

    public void setColumnInfo(ColumnInfo columnInfo) {
        this.columnInfo = columnInfo;
    }

    public IndexInfo getIndexInfo() {
        return indexInfo;
    }

    public void setIndexInfo(IndexInfo indexInfo) {
        this.indexInfo = indexInfo;
    }

    public SchemaState getSchemaState() {
        return schemaState;
    }

    public void setSchemaState(SchemaState schemaState) {
        this.schemaState = schemaState;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public long getStartTs() {
        return startTs;
    }

    public void setStartTs(long startTs) {
        this.startTs = startTs;
    }

    public long getFinishTs() {
        return finishTs;
    }

    public void setFinishTs(long finishTs) {
        this.finishTs = finishTs;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
