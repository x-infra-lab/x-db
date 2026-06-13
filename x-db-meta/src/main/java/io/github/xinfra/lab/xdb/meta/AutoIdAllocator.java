package io.github.xinfra.lab.xdb.meta;

/**
 * Allocates auto-increment IDs in batches from {@link MetaStore}.
 * <p>
 * Similar to TiDB's allocator, this fetches a batch of IDs from the meta store
 * and hands them out locally, reducing round-trips to the KV store.
 * </p>
 * Thread-safe: all public methods are synchronized.
 */
public class AutoIdAllocator {

    private final MetaStore metaStore;
    private final long tableId;
    private final int batchSize;

    private long currentId;
    private long endId;

    public AutoIdAllocator(MetaStore metaStore, long tableId, int batchSize) {
        this.metaStore = metaStore;
        this.tableId = tableId;
        this.batchSize = batchSize;
        this.currentId = 0;
        this.endId = 0;
    }

    /**
     * Returns the next auto-increment ID. Allocates a new batch from the meta store
     * when the current batch is exhausted.
     */
    public synchronized long nextId() {
        if (currentId >= endId) {
            currentId = metaStore.allocAutoIncId(tableId, batchSize);
            endId = currentId + batchSize;
        }
        return ++currentId;
    }
}
