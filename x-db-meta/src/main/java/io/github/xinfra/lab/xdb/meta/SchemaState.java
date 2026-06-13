package io.github.xinfra.lab.xdb.meta;

public enum SchemaState {
    ABSENT,
    DELETE_ONLY,
    WRITE_ONLY,
    WRITE_REORGANIZATION,
    PUBLIC
}
