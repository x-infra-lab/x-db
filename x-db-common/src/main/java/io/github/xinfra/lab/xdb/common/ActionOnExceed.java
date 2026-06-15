package io.github.xinfra.lab.xdb.common;

/**
 * Action triggered when a {@link MemoryTracker} exceeds its hard limit.
 */
public interface ActionOnExceed {

    /**
     * Called when memory consumption exceeds the tracker's hard limit.
     *
     * @param tracker the tracker that exceeded its limit
     * @return {@code true} if the action handled the excess (e.g. spilled to disk),
     *         {@code false} if the caller should throw an exception
     */
    boolean act(MemoryTracker tracker);
}
