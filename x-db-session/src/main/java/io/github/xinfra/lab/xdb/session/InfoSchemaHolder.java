package io.github.xinfra.lab.xdb.session;

import io.github.xinfra.lab.xdb.meta.InfoSchema;
import io.github.xinfra.lab.xdb.meta.InfoSchemaBuilder;
import io.github.xinfra.lab.xdb.meta.MetaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the current {@link InfoSchema} snapshot and refreshes it
 * from the {@link MetaStore} when the schema version changes.
 * <p>
 * Thread-safe: the {@code current} field is {@code volatile} so
 * concurrent readers always see the latest snapshot.
 */
public class InfoSchemaHolder {

    private static final Logger log = LoggerFactory.getLogger(InfoSchemaHolder.class);
    private static final long REFRESH_INTERVAL_MS = 1000;

    private volatile InfoSchema current;
    private final MetaStore metaStore;
    private long lastVersion;
    private volatile long lastRefreshTime;

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
     * Refresh if the TTL has expired. Avoids hitting the MetaStore on every query.
     */
    public void refreshIfNeeded() {
        if (System.currentTimeMillis() - lastRefreshTime >= REFRESH_INTERVAL_MS) {
            refresh();
        }
    }

    /**
     * Force-reload the InfoSchema from MetaStore if the schema version has changed.
     */
    public synchronized void refresh() {
        long latestVersion = metaStore.getSchemaVersion();
        if (current == null || latestVersion != lastVersion) {
            log.debug("Refreshing InfoSchema: {} -> {}", lastVersion, latestVersion);
            this.current = InfoSchemaBuilder.build(metaStore);
            this.lastVersion = latestVersion;
        }
        this.lastRefreshTime = System.currentTimeMillis();
    }
}
