package io.github.xinfra.lab.xdb.meta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.xinfra.lab.xdb.expression.DataType;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ColumnInfo {

    @JsonProperty("id")
    private long id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("type")
    private DataType type;

    @JsonProperty("fieldLength")
    private int fieldLength;

    @JsonProperty("decimal")
    private int decimal;

    @JsonProperty("nullable")
    private boolean nullable = true;

    @JsonProperty("unsigned")
    private boolean unsigned;

    @JsonProperty("autoIncrement")
    private boolean autoIncrement;

    @JsonProperty("defaultValue")
    private String defaultValue;

    @JsonProperty("comment")
    private String comment;

    @JsonProperty("offset")
    private int offset;

    @JsonProperty("state")
    private SchemaState state;

    public ColumnInfo() {
    }

    public ColumnInfo(long id, String name, DataType type, int fieldLength, int decimal,
                      boolean nullable, boolean unsigned, boolean autoIncrement,
                      String defaultValue, String comment, int offset, SchemaState state) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.fieldLength = fieldLength;
        this.decimal = decimal;
        this.nullable = nullable;
        this.unsigned = unsigned;
        this.autoIncrement = autoIncrement;
        this.defaultValue = defaultValue;
        this.comment = comment;
        this.offset = offset;
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

    public DataType getType() {
        return type;
    }

    public void setType(DataType type) {
        this.type = type;
    }

    public int getFieldLength() {
        return fieldLength;
    }

    public void setFieldLength(int fieldLength) {
        this.fieldLength = fieldLength;
    }

    public int getDecimal() {
        return decimal;
    }

    public void setDecimal(int decimal) {
        this.decimal = decimal;
    }

    public boolean isNullable() {
        return nullable;
    }

    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }

    public boolean isUnsigned() {
        return unsigned;
    }

    public void setUnsigned(boolean unsigned) {
        this.unsigned = unsigned;
    }

    public boolean isAutoIncrement() {
        return autoIncrement;
    }

    public void setAutoIncrement(boolean autoIncrement) {
        this.autoIncrement = autoIncrement;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public SchemaState getState() {
        return state;
    }

    public void setState(SchemaState state) {
        this.state = state;
    }

    @Override
    public String toString() {
        return "ColumnInfo{id=" + id + ", name='" + name + "', type=" + type
                + ", offset=" + offset + ", state=" + state + "}";
    }
}
