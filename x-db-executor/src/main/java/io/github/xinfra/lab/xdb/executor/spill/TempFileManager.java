package io.github.xinfra.lab.xdb.executor.spill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages temporary files for spill-to-disk operations.
 * All files are created under a dedicated temp directory and cleaned up on close.
 */
public class TempFileManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TempFileManager.class);

    private final Path tmpDir;
    private final List<Path> files = new ArrayList<>();

    public TempFileManager() throws IOException {
        this.tmpDir = Files.createTempDirectory("xdb-spill-");
    }

    public Path createTempFile(String prefix) throws IOException {
        Path file = Files.createTempFile(tmpDir, prefix + "-", ".spill");
        files.add(file);
        return file;
    }

    public Path tmpDir() {
        return tmpDir;
    }

    @Override
    public void close() {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Failed to delete temp file: {}", file, e);
            }
        }
        try {
            Files.deleteIfExists(tmpDir);
        } catch (IOException e) {
            log.warn("Failed to delete temp directory: {}", tmpDir, e);
        }
    }
}
