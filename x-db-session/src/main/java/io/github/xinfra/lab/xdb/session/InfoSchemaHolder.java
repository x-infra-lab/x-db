package io.github.xinfra.lab.xdb.session;

import io.github.xinfra.lab.xdb.meta.InfoSchema;
import io.github.xinfra.lab.xdb.meta.InfoSchemaBuilder;
import io.github.xinfra.lab.xdb.meta.MetaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Holds the current {@link InfoSchema} snapshot and refreshes it
 * from the {@link MetaStore} when the schema version changes.
 * <p>
 * Thread-safe: the {@code current} field is {@code volatile} so
 * concurrent readers always see the latest snapshot. Refresh uses
 * {@code tryLock()} to avoid thundering-herd: when the TTL expires,
 * only one thread rebuilds while others proceed with the current snapshot.
 */
public class InfoSchemaHolder {

    private static final Logger log = LoggerFactory.getLogger(InfoSchemaHolder.class);
    private static final long REFRESH_INTERVAL_MS = 1000;

    private volatile InfoSchema current;
    private final MetaStore metaStore;
    private long lastVersion;
    private volatile long lastRefreshTime;
    private final ReentrantLock refreshLock = new ReentrantLock();

    public InfoSchemaHolder(MetaStore metaStore) {
        this.metaStore = metaStore;
        refresh();
    }

    /**
     * Return the current InfoSchema snapshot.
     */
    public InfoSchema get() {
        return current;
    }

    /**
     * Refresh if the TTL has expired. Uses tryLock so only one thread
     * does the actual refresh while others proceed with the current
     * (slightly stale) snapshot — avoids thundering-herd on the MetaStore.
     */
    public void refreshIfNeeded() {
        if (System.currentTimeMillis() - lastRefreshTime < REFRESH_INTERVAL_MS) {
            return;
        }
        if (refreshLock.tryLock()) {
            try {
                if (System.currentTimeMillis() - lastRefreshTime >= REFRESH_INTERVAL_MS) {
                    doRefresh();
                }
            } finally {
                refreshLock.unlock();
            }
        }
    }

    /**
     * Force-reload the InfoSchema from MetaStore if the schema version has changed.
     * Blocks until the refresh completes.
     */
    public void refresh() {
        refreshLock.lock();
        try {
            doRefresh();
        } finally {
            refreshLock.unlock();
        }
    }

    private void doRefresh() {
        long latestVersion = metaStore.getSchemaVersion();
        if (current == null || latestVersion != lastVersion) {
            log.debug("Refreshing InfoSchema: {} -> {}", lastVersion, latestVersion);
            this.current = InfoSchemaBuilder.build(metaStore);
            this.lastVersion = latestVersion;
        }
        this.lastRefreshTime = System.currentTimeMillis();
    }
}
