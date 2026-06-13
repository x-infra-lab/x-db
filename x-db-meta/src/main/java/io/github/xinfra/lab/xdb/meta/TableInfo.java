package io.github.xinfra.lab.xdb.meta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TableInfo {

    @JsonProperty("id")
    private long id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("dbId")
    private long dbId;

    @JsonProperty("charset")
    private String charset;

    @JsonProperty("collation")
    private String collation;

    @JsonProperty("comment")
    private String comment;

    @JsonProperty("engine")
    private String engine;

    @JsonProperty("columns")
    private List<ColumnInfo> columns = new ArrayList<>();

    @JsonProperty("indices")
    private List<IndexInfo> indices = new ArrayList<>();

    @JsonProperty("autoIncId")
    private long autoIncId;

    @JsonProperty("state")
    private SchemaState state;

    @JsonProperty("maxColumnId")
    private long maxColumnId;

    @JsonProperty("maxIndexId")
    private long maxIndexId;

    public TableInfo() {
    }

    public TableInfo(long id, String name, long dbId, String charset, String collation,
                     String comment, String engine, List<ColumnInfo> columns,
                     List<IndexInfo> indices, long autoIncId, SchemaState state,
                     long maxColumnId, long maxIndexId) {
        this.id = id;
        this.name = name;
        this.dbId = dbId;
        this.charset = charset;
        this.collation = collation;
        this.comment = comment;
        this.engine = engine;
        this.columns = columns != null ? columns : new ArrayList<>();
        this.indices = indices != null ? indices : new ArrayList<>();
        this.autoIncId = autoIncId;
        this.state = state;
        this.maxColumnId = maxColumnId;
        this.maxIndexId = maxIndexId;
    }

    // --- Getters and Setters ---

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getDbId() {
        return dbId;
    }

    public void setDbId(long dbId) {
        this.dbId = dbId;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public String getCollation() {
        return collation;
    }

    public void setCollation(String collation) {
        this.collation = collation;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public List<ColumnInfo> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnInfo> columns) {
        this.columns = columns != null ? columns : new ArrayList<>();
    }

    public List<IndexInfo> getIndices() {
        return indices;
    }

    public void setIndices(List<IndexInfo> indices) {
        this.indices = indices != null ? indices : new ArrayList<>();
    }

    public long getAutoIncId() {
        return autoIncId;
    }

    public void setAutoIncId(long autoIncId) {
        this.autoIncId = autoIncId;
    }

    public SchemaState getState() {
        return state;
    }

    public void setState(SchemaState state) {
        this.state = state;
    }

    public long getMaxColumnId() {
        return maxColumnId;
    }

    public void setMaxColumnId(long maxColumnId) {
        this.maxColumnId = maxColumnId;
    }

    public long getMaxIndexId() {
        return maxIndexId;
    }

    public void setMaxIndexId(long maxIndexId) {
        this.maxIndexId = maxIndexId;
    }

    // --- Lookup methods ---

    /**
     * Find a column by name (case-insensitive).
     */
    public ColumnInfo getColumn(String name) {
        if (name == null) {
            return null;
        }
        for (ColumnInfo col : columns) {
            if (name.equalsIgnoreCase(col.getName())) {
                return col;
            }
        }
        return null;
    }

    /**
     * Find a column by ID.
     */
    public ColumnInfo getColumn(long id) {
        for (ColumnInfo col : columns) {
            if (col.getId() == id) {
                return col;
            }
        }
        return null;
    }

    /**
     * Find the primary key index.
     */
    public IndexInfo getPrimaryIndex() {
        for (IndexInfo idx : indices) {
            if (idx.isPrimary()) {
                return idx;
            }
        }
        return null;
    }

    /**
     * Find an index by name.
     */
    public IndexInfo getIndex(String name) {
        if (name == null) {
            return null;
        }
        for (IndexInfo idx : indices) {
            if (name.equalsIgnoreCase(idx.getName())) {
                return idx;
            }
        }
        return null;
    }

    /**
     * Return all columns in PUBLIC state.
     */
    public List<ColumnInfo> getPublicColumns() {
        return columns.stream()
                .filter(c -> c.getState() == SchemaState.PUBLIC)
                .collect(Collectors.toList());
    }

    /**
     * Increment and return the next column ID.
     */
    public long nextColumnId() {
        return ++maxColumnId;
    }

    /**
     * Increment and return the next index ID.
     */
    public long nextIndexId() {
        return ++maxIndexId;
    }

    @Override
    public String toString() {
        return "TableInfo{id=" + id + ", name='" + name + "', dbId=" + dbId
                + ", columns=" + columns.size() + ", indices=" + indices.size()
                + ", state=" + state + "}";
    }
}
