package io.github.xinfra.lab.xdb.meta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class IndexColumn {

    @JsonProperty("columnName")
    private String columnName;

    @JsonProperty("columnId")
    private long columnId;

    @JsonProperty("length")
    private int length;

    public IndexColumn() {
    }

    public IndexColumn(String columnName, long columnId, int length) {
        this.columnName = columnName;
        this.columnId = columnId;
        this.length = length;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public long getColumnId() {
        return columnId;
    }

    public void setColumnId(long columnId) {
        this.columnId = columnId;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    @Override
    public String toString() {
        return "IndexColumn{columnName='" + columnName + "', columnId=" + columnId
                + ", length=" + length + "}";
    }
}
