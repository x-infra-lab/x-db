package io.github.xinfra.lab.xdb.executor.spill;

import io.github.xinfra.lab.xdb.expression.Row;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A sorted run stored on disk. Rows are written in sorted order and can be
 * read back sequentially via {@link #readNext()}.
 */
public class SortedRun implements AutoCloseable {

    private final Path file;
    private final long rowCount;
    private final RowSerializer serializer;

    private DataInputStream readStream;
    private long readPosition;

    private SortedRun(Path file, long rowCount, RowSerializer serializer) {
        this.file = file;
        this.rowCount = rowCount;
        this.serializer = serializer;
    }

    public static SortedRun write(List<Row> sortedRows, RowSerializer serializer,
                                  TempFileManager tmpMgr) throws IOException {
        Path file = tmpMgr.createTempFile("run");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file), 64 * 1024))) {
            out.writeLong(sortedRows.size());
            for (Row row : sortedRows) {
                serializer.writeRow(row, out);
            }
        }
        return new SortedRun(file, sortedRows.size(), serializer);
    }

    public void openForRead() throws IOException {
        readStream = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file), 64 * 1024));
        long count = readStream.readLong();
        assert count == rowCount;
        readPosition = 0;
    }

    public Row readNext() throws IOException {
        if (readStream == null) {
            openForRead();
        }
        if (readPosition >= rowCount) {
            return null;
        }
        readPosition++;
        return serializer.readRow(readStream);
    }

    public long rowCount() {
        return rowCount;
    }

    @Override
    public void close() throws IOException {
        if (readStream != null) {
            readStream.close();
            readStream = null;
        }
    }
}
