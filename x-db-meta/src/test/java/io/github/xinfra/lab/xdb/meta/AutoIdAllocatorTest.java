package io.github.xinfra.lab.xdb.meta;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AutoIdAllocatorTest {

    private MetaStore fakeMetaStore() {
        return new MetaStore() {
            private final AtomicLong autoInc = new AtomicLong(0);
            private final AtomicLong globalId = new AtomicLong(0);

            @Override public long getSchemaVersion() { return 0; }
            @Override public void setSchemaVersion(long version) {}
            @Override public long advanceSchemaVersion() { return 1; }
            @Override public void createDatabase(DatabaseInfo db) {}
            @Override public void dropDatabase(long dbId) {}
            @Override public DatabaseInfo getDatabase(long dbId) { return null; }
            @Override public java.util.List<Long> listDatabaseIds() { return java.util.List.of(); }
            @Override public void createTable(long dbId, TableInfo table) {}
            @Override public void updateTable(long dbId, TableInfo table) {}
            @Override public void dropTable(long dbId, long tableId) {}
            @Override public TableInfo getTable(long dbId, long tableId) { return null; }
            @Override public java.util.List<Long> listTableIds(long dbId) { return java.util.List.of(); }
            @Override public long allocGlobalId() { return globalId.incrementAndGet(); }

            @Override
            public long allocAutoIncId(long tableId, int batchSize) {
                return autoInc.getAndAdd(batchSize);
            }
        };
    }

    @Test
    void singleThreadSequentialIds() {
        AutoIdAllocator alloc = new AutoIdAllocator(fakeMetaStore(), 1, 10);
        for (int i = 1; i <= 20; i++) {
            assertThat(alloc.nextId()).isEqualTo(i);
        }
    }

    @Test
    void concurrentIdsAreUniqueAndPositive() throws Exception {
        int threadCount = 8;
        int idsPerThread = 10_000;
        AutoIdAllocator alloc = new AutoIdAllocator(fakeMetaStore(), 1, 100);

        Set<Long> ids = Collections.newSetFromMap(new ConcurrentHashMap<>());
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < idsPerThread; i++) {
                        long id = alloc.nextId();
                        assertThat(id).isPositive();
                        boolean added = ids.add(id);
                        assertThat(added).as("Duplicate ID: %d", id).isTrue();
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
        assertThat(ids).hasSize(threadCount * idsPerThread);
        pool.shutdown();
    }

    @Test
    void batchRefillHappensTransparently() {
        int batchSize = 5;
        AutoIdAllocator alloc = new AutoIdAllocator(fakeMetaStore(), 1, batchSize);
        long prev = 0;
        for (int i = 0; i < 25; i++) {
            long id = alloc.nextId();
            assertThat(id).isGreaterThan(prev);
            prev = id;
        }
    }

    @Test
    void highContention() throws Exception {
        int threadCount = 16;
        int idsPerThread = 50_000;
        AutoIdAllocator alloc = new AutoIdAllocator(fakeMetaStore(), 1, 64);

        Set<Long> ids = Collections.newSetFromMap(new ConcurrentHashMap<>());
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < idsPerThread; i++) {
                        ids.add(alloc.nextId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(ids).hasSize(threadCount * idsPerThread);
        pool.shutdown();
    }
}
