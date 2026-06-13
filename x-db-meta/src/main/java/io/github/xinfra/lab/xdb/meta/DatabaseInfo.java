package io.github.xinfra.lab.xdb.meta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DatabaseInfo {

    @JsonProperty("id")
    private long id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("charset")
    private String charset = "utf8mb4";

    @JsonProperty("collation")
    private String collation = "utf8mb4_general_ci";

    @JsonProperty("state")
    private SchemaState state;

    public DatabaseInfo() {
    }

    public DatabaseInfo(long id, String name, String charset, String collation, SchemaState state) {
        this.id = id;
        this.name = name;
        this.charset = charset;
        this.collation = collation;
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

    public SchemaState getState() {
        return state;
    }

    public void setState(SchemaState state) {
        this.state = state;
    }

    @Override
    public String toString() {
        return "DatabaseInfo{id=" + id + ", name='" + name + "', state=" + state + "}";
    }
}
