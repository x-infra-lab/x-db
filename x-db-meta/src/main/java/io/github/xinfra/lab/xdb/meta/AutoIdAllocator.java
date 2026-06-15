package io.github.xinfra.lab.xdb.meta;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Allocates auto-increment IDs in batches from {@link MetaStore}.
 * <p>
 * Similar to TiDB's allocator, this fetches a batch of IDs from the meta store
 * and hands them out locally, reducing round-trips to the KV store.
 * </p>
 * Thread-safe: fast path uses CAS on a per-batch counter; batch refill uses a
 * lock so only one thread hits the MetaStore while others spin on the CAS path.
 */
public class AutoIdAllocator {

    private final MetaStore metaStore;
    private final long tableId;
    private final int batchSize;

    private static final class Batch {
        final AtomicLong counter;
        final long end;

        Batch(long start, long end) {
            this.counter = new AtomicLong(start);
            this.end = end;
        }
    }

    private volatile Batch currentBatch = new Batch(0, 0);
    private final ReentrantLock refillLock = new ReentrantLock();

    public AutoIdAllocator(MetaStore metaStore, long tableId, int batchSize) {
        this.metaStore = metaStore;
        this.tableId = tableId;
        this.batchSize = batchSize;
    }

    /**
     * Returns the next auto-increment ID. Uses CAS on the fast path;
     * only acquires a lock when the current batch is exhausted and a
     * new batch must be fetched from the MetaStore.
     */
    public long nextId() {
        while (true) {
            Batch batch = currentBatch;
            long id = batch.counter.get();
            if (id < batch.end && batch.counter.compareAndSet(id, id + 1)) {
                return id;
            }
            refillBatch();
        }
    }

    private void refillBatch() {
        refillLock.lock();
        try {
            Batch batch = currentBatch;
            if (batch.counter.get() >= batch.end) {
                long base = metaStore.allocAutoIncId(tableId, batchSize);
                currentBatch = new Batch(base + 1, base + batchSize + 1);
            }
        } finally {
            refillLock.unlock();
        }
    }
}
