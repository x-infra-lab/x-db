package io.github.xinfra.lab.xdb.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Hierarchical memory tracker inspired by TiDB's memory.Tracker.
 * <p>
 * Each tracker has an optional parent, forming a tree:
 * Session Tracker → Statement Tracker → Operator Tracker (sort, hash_join, hash_agg).
 * <p>
 * When {@code consume()} pushes the total past {@code hardLimit},
 * the configured {@link ActionOnExceed} fires (cancel query, spill to disk, or log).
 */
public class MemoryTracker {

    private static final Logger log = LoggerFactory.getLogger(MemoryTracker.class);

    public static final long UNLIMITED = -1;
    private static final MemoryTracker NOOP = new MemoryTracker("noop", null, UNLIMITED);

    private final String label;
    private final MemoryTracker parent;
    private final AtomicLong bytesConsumed = new AtomicLong();
    private final long hardLimit;
    private volatile ActionOnExceed action;

    public MemoryTracker(String label, MemoryTracker parent, long hardLimit) {
        this.label = label;
        this.parent = parent;
        this.hardLimit = hardLimit;
    }

    /**
     * Returns a no-op tracker with no limit and no parent.
     */
    public static MemoryTracker noopTracker() {
        return NOOP;
    }

    /**
     * Create a child tracker that reports to this tracker as parent.
     */
    public MemoryTracker newChild(String childLabel, long childLimit) {
        return new MemoryTracker(childLabel, this, childLimit);
    }

    /**
     * Create a child tracker with no limit of its own (inherits parent limit).
     */
    public MemoryTracker newChild(String childLabel) {
        return new MemoryTracker(childLabel, this, UNLIMITED);
    }

    /**
     * Record memory consumption. Propagates to parent.
     * If hard limit is exceeded, triggers {@link ActionOnExceed}.
     *
     * @param bytes number of bytes consumed (must be &gt;= 0)
     */
    public void consume(long bytes) {
        if (bytes <= 0 || this == NOOP) {
            return;
        }
        long total = bytesConsumed.addAndGet(bytes);

        if (parent != null) {
            parent.consume(bytes);
        }

        if (hardLimit > 0 && total > hardLimit) {
            ActionOnExceed a = this.action;
            if (a != null) {
                boolean handled = a.act(this);
                if (!handled) {
                    throw XDBException.memoryExceeded(label, total, hardLimit);
                }
            } else {
                throw XDBException.memoryExceeded(label, total, hardLimit);
            }
        }
    }

    /**
     * Release previously consumed memory. Propagates to parent.
     *
     * @param bytes number of bytes to release (must be &gt;= 0)
     */
    public void release(long bytes) {
        if (bytes <= 0 || this == NOOP) {
            return;
        }
        bytesConsumed.addAndGet(-bytes);
        if (parent != null) {
            parent.release(bytes);
        }
    }

    /**
     * Returns the total bytes consumed by this tracker (excluding parent).
     */
    public long bytesConsumed() {
        return bytesConsumed.get();
    }

    /**
     * Returns the hard limit in bytes, or {@link #UNLIMITED} if no limit.
     */
    public long hardLimit() {
        return hardLimit;
    }

    public String label() {
        return label;
    }

    public void setActionOnExceed(ActionOnExceed action) {
        this.action = action;
    }

    public ActionOnExceed actionOnExceed() {
        return action;
    }

    /**
     * Reset the consumed bytes counter to zero without propagating to parent.
     */
    public void reset() {
        bytesConsumed.set(0);
    }

    @Override
    public String toString() {
        return String.format("MemoryTracker[%s, consumed=%d, limit=%d]",
                label, bytesConsumed.get(), hardLimit);
    }

    // -- Built-in actions --------------------------------------------------

    /**
     * Action that throws {@link XDBException} when memory is exceeded.
     */
    public static class CancelAction implements ActionOnExceed {
        @Override
        public boolean act(MemoryTracker tracker) {
            return false;
        }
    }

    /**
     * Action that invokes a spill callback when memory is exceeded.
     */
    public static class SpillDiskAction implements ActionOnExceed {
        private final Runnable spillCallback;

        public SpillDiskAction(Runnable spillCallback) {
            this.spillCallback = spillCallback;
        }

        @Override
        public boolean act(MemoryTracker tracker) {
            spillCallback.run();
            return true;
        }
    }

    /**
     * Action that only logs a warning when memory is exceeded.
     */
    public static class LogOnlyAction implements ActionOnExceed {
        @Override
        public boolean act(MemoryTracker tracker) {
            log.warn("Memory exceeded for {}: consumed={} bytes, limit={} bytes",
                    tracker.label(), tracker.bytesConsumed(), tracker.hardLimit());
            return true;
        }
    }
}
