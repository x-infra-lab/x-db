package io.github.xinfra.lab.xdb.executor;

import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.List;

/**
 * Volcano-model iterator interface. Each executor produces rows one at a time.
 */
public interface Executor {

    /**
     * Initialize the executor and acquire resources.
     */
    void open() throws Exception;

    /**
     * Return the next row, or {@code null} if no more rows.
     */
    Row next() throws Exception;

    /**
     * Release resources.
     */
    void close() throws Exception;

    /**
     * Return the output column schema.
     */
    List<ColumnInfo> outputSchema();
}
