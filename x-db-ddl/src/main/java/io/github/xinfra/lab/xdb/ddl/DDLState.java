package io.github.xinfra.lab.xdb.ddl;

public enum DDLState {
    NONE,
    QUEUED,
    RUNNING,
    DONE,
    CANCELLED,
    FAILED,
    ROLLING_BACK,
    ROLLED_BACK
}
