package io.github.xinfra.lab.xdb.executor.spill;

import io.github.xinfra.lab.xdb.expression.Row;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * File-backed row buffer for hash join/agg partition spill.
 * Rows are appended sequentially and can be read back in bulk.
 */
public class PartitionFile implements AutoCloseable {

    private final Path file;
    private final RowSerializer serializer;
    private DataOutputStream out;
    private long rowCount;

    public PartitionFile(Path file, RowSerializer serializer) {
        this.file = file;
        this.serializer = serializer;
    }

    public void appendRow(Row row) throws IOException {
        if (out == null) {
            out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(file), 64 * 1024));
        }
        serializer.writeRow(row, out);
        rowCount++;
    }

    public void finishWriting() throws IOException {
        if (out != null) {
            out.close();
            out = null;
        }
    }

    public List<Row> readAll() throws IOException {
        finishWriting();
        if (rowCount == 0) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>((int) Math.min(rowCount, Integer.MAX_VALUE));
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file), 64 * 1024))) {
            for (long i = 0; i < rowCount; i++) {
                rows.add(serializer.readRow(in));
            }
        }
        return rows;
    }

    public long rowCount() {
        return rowCount;
    }

    @Override
    public void close() throws IOException {
        if (out != null) {
            out.close();
            out = null;
        }
    }
}
