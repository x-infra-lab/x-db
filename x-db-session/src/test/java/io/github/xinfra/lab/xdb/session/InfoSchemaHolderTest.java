package io.github.xinfra.lab.xdb.session;

import io.github.xinfra.lab.xdb.meta.DatabaseInfo;
import io.github.xinfra.lab.xdb.meta.InfoSchema;
import io.github.xinfra.lab.xdb.meta.MetaStore;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InfoSchemaHolderTest {

    private MetaStore stubMetaStore(AtomicLong version, AtomicInteger buildCount) {
        return new MetaStore() {
            @Override public long getSchemaVersion() { return version.get(); }
            @Override public void setSchemaVersion(long v) { version.set(v); }
            @Override public long advanceSchemaVersion() { return version.incrementAndGet(); }
            @Override public void createDatabase(DatabaseInfo db) {}
            @Override public void dropDatabase(long dbId) {}
            @Override public DatabaseInfo getDatabase(long dbId) { return null; }
            @Override public List<Long> listDatabaseIds() {
                buildCount.incrementAndGet();
                return List.of();
            }
            @Override public void createTable(long dbId, TableInfo table) {}
            @Override public void updateTable(long dbId, TableInfo table) {}
            @Override public void dropTable(long dbId, long tableId) {}
            @Override public TableInfo getTable(long dbId, long tableId) { return null; }
            @Override public List<Long> listTableIds(long dbId) { return List.of(); }
            @Override public long allocAutoIncId(long tableId, int batchSize) { return 0; }
            @Override public long allocGlobalId() { return 0; }
        };
    }

    @Test
    void getReturnsNonNull() {
        AtomicLong version = new AtomicLong(1);
        AtomicInteger buildCount = new AtomicInteger(0);
        InfoSchemaHolder holder = new InfoSchemaHolder(stubMetaStore(version, buildCount));

        assertThat(holder.get()).isNotNull();
    }

    @Test
    void refreshIfNeededSkipsWithinTTL() {
        AtomicLong version = new AtomicLong(1);
        AtomicInteger buildCount = new AtomicInteger(0);
        InfoSchemaHolder holder = new InfoSchemaHolder(stubMetaStore(version, buildCount));

        int countAfterInit = buildCount.get();
        holder.refreshIfNeeded();
        assertThat(buildCount.get()).isEqualTo(countAfterInit);
    }

    @Test
    void forceRefreshAlwaysReloads() {
        AtomicLong version = new AtomicLong(1);
        AtomicInteger buildCount = new AtomicInteger(0);
        InfoSchemaHolder holder = new InfoSchemaHolder(stubMetaStore(version, buildCount));

        version.set(2);
        holder.refresh();
        InfoSchema schema = holder.get();
        assertThat(schema).isNotNull();
        assertThat(schema.schemaVersion()).isEqualTo(2);
    }

    @Test
    void concurrentReadsNeverSeeNull() throws Exception {
        int threadCount = 16;
        AtomicLong version = new AtomicLong(1);
        AtomicInteger buildCount = new AtomicInteger(0);
        InfoSchemaHolder holder = new InfoSchemaHolder(stubMetaStore(version, buildCount));

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger nullCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            final boolean isRefresher = (t == 0);
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 10_000; i++) {
                        if (isRefresher) {
                            version.incrementAndGet();
                            holder.refresh();
                        } else {
                            if (holder.get() == null) {
                                nullCount.incrementAndGet();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(nullCount.get()).isZero();
        pool.shutdown();
    }

    @Test
    void thunderingHerdPrevented() throws Exception {
        int threadCount = 32;
        AtomicLong version = new AtomicLong(1);
        AtomicInteger buildCount = new AtomicInteger(0);

        MetaStore slowStore = new MetaStore() {
            private final MetaStore delegate = stubMetaStore(version, buildCount);

            @Override public long getSchemaVersion() { return delegate.getSchemaVersion(); }
            @Override public void setSchemaVersion(long v) { delegate.setSchemaVersion(v); }
            @Override public long advanceSchemaVersion() { return delegate.advanceSchemaVersion(); }
            @Override public void createDatabase(DatabaseInfo db) {}
            @Override public void dropDatabase(long dbId) {}
            @Override public DatabaseInfo getDatabase(long dbId) { return null; }
            @Override public List<Long> listDatabaseIds() {
                buildCount.incrementAndGet();
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return List.of();
            }
            @Override public void createTable(long dbId, TableInfo table) {}
            @Override public void updateTable(long dbId, TableInfo table) {}
            @Override public void dropTable(long dbId, long tableId) {}
            @Override public TableInfo getTable(long dbId, long tableId) { return null; }
            @Override public List<Long> listTableIds(long dbId) { return List.of(); }
            @Override public long allocAutoIncId(long tableId, int batchSize) { return 0; }
            @Override public long allocGlobalId() { return 0; }
        };

        InfoSchemaHolder holder = new InfoSchemaHolder(slowStore);
        int countAfterInit = buildCount.get();

        // Force TTL expiry
        Thread.sleep(1100);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    holder.refreshIfNeeded();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();

        // With tryLock, only 1 thread should have done the actual build.
        // Without tryLock (old code), all 32 would serialize and build.
        // Allow up to 2 for timing tolerance (version unchanged → skips rebuild).
        int builds = buildCount.get() - countAfterInit;
        assertThat(builds)
                .as("Only one thread should rebuild; got %d", builds)
                .isLessThanOrEqualTo(2);

        pool.shutdown();
    }
}
