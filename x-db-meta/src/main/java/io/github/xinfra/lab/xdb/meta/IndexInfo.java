package io.github.xinfra.lab.xdb.meta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class IndexInfo {

    @JsonProperty("id")
    private long id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("tableId")
    private long tableId;

    @JsonProperty("columns")
    private List<IndexColumn> columns = new ArrayList<>();

    @JsonProperty("unique")
    private boolean unique;

    @JsonProperty("primary")
    private boolean primary;

    @JsonProperty("state")
    private SchemaState state;

    public IndexInfo() {
    }

    public IndexInfo(long id, String name, long tableId, List<IndexColumn> columns,
                     boolean unique, boolean primary, SchemaState state) {
        this.id = id;
        this.name = name;
        this.tableId = tableId;
        this.columns = columns != null ? columns : new ArrayList<>();
        this.unique = unique;
        this.primary = primary;
        this.state = state;
    }

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

    public long getTableId() {
        return tableId;
    }

    public void setTableId(long tableId) {
        this.tableId = tableId;
    }

    public List<IndexColumn> getColumns() {
        return columns;
    }

    public void setColumns(List<IndexColumn> columns) {
        this.columns = columns != null ? columns : new ArrayList<>();
    }

    public boolean isUnique() {
        return unique;
    }

    public void setUnique(boolean unique) {
        this.unique = unique;
    }

    public boolean isPrimary() {
        return primary;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public SchemaState getState() {
        return state;
    }

    public void setState(SchemaState state) {
        this.state = state;
    }

    @Override
    public String toString() {
        return "IndexInfo{id=" + id + ", name='" + name + "', tableId=" + tableId
                + ", primary=" + primary + ", unique=" + unique + ", state=" + state + "}";
    }
}
