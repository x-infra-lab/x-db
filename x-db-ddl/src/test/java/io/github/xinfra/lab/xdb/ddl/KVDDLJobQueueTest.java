package io.github.xinfra.lab.xdb.ddl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class KVDDLJobQueueTest {

    private TreeMap<String, byte[]> kvStore;
    private KVDDLJobQueue queue;

    @BeforeEach
    void setUp() {
        kvStore = new TreeMap<>();

        queue = new KVDDLJobQueue(
                key -> kvStore.get(new String(key)),
                (key, value) -> kvStore.put(new String(key), value),
                key -> kvStore.remove(new String(key)),
                (start, end, limit) -> {
                    String s = new String(start);
                    String e = new String(end);
                    List<byte[][]> result = new ArrayList<>();
                    for (var entry : kvStore.subMap(s, e).entrySet()) {
                        if (result.size() >= limit) break;
                        result.add(new byte[][]{entry.getKey().getBytes(), entry.getValue()});
                    }
                    return result;
                },
                (key, expected, newValue) -> {
                    String k = new String(key);
                    synchronized (kvStore) {
                        byte[] current = kvStore.get(k);
                        if (expected == null && current == null) {
                            kvStore.put(k, newValue);
                            return true;
                        }
                        if (expected != null && current != null && Arrays.equals(expected, current)) {
                            kvStore.put(k, newValue);
                            return true;
                        }
                        return false;
                    }
                }
        );
    }

    @Nested
    @DisplayName("Enqueue")
    class Enqueue {

        @Test
        @DisplayName("enqueue assigns job ID if zero")
        void assignsId() {
            DDLJob job = new DDLJob();
            job.setType(DDLType.CREATE_DATABASE);
            queue.enqueue(job);
            assertThat(job.getId()).isGreaterThan(0);
        }

        @Test
        @DisplayName("enqueue sets QUEUED state")
        void setsQueuedState() {
            DDLJob job = new DDLJob();
            job.setType(DDLType.CREATE_DATABASE);
            queue.enqueue(job);
            assertThat(job.getState()).isEqualTo(DDLState.QUEUED);
        }

        @Test
        @DisplayName("enqueue sets startTs")
        void setsStartTs() {
            DDLJob job = new DDLJob();
            job.setType(DDLType.CREATE_DATABASE);
            queue.enqueue(job);
            assertThat(job.getStartTs()).isGreaterThan(0);
        }

        @Test
        @DisplayName("enqueue preserves existing ID")
        void preservesExistingId() {
            DDLJob job = new DDLJob();
            job.setId(42);
            job.setType(DDLType.CREATE_TABLE);
            queue.enqueue(job);
            assertThat(job.getId()).isEqualTo(42);
        }

        @Test
        @DisplayName("enqueue preserves non-NONE state")
        void preservesExistingState() {
            DDLJob job = new DDLJob();
            job.setId(1);
            job.setState(DDLState.RUNNING);
            job.setType(DDLType.CREATE_TABLE);
            queue.enqueue(job);
            assertThat(job.getState()).isEqualTo(DDLState.RUNNING);
        }
    }

    @Nested
    @DisplayName("Dequeue")
    class Dequeue {

        @Test
        @DisplayName("dequeue returns null when empty")
        void emptyReturnsNull() {
            assertThat(queue.dequeue()).isNull();
        }

        @Test
        @DisplayName("dequeue returns the first QUEUED job")
        void returnsFirstQueued() {
            DDLJob job = new DDLJob();
            job.setType(DDLType.CREATE_DATABASE);
            job.setDbName("testdb");
            queue.enqueue(job);

            DDLJob dequeued = queue.dequeue();
            assertThat(dequeued).isNotNull();
            assertThat(dequeued.getDbName()).isEqualTo("testdb");
            assertThat(dequeued.getState()).isEqualTo(DDLState.QUEUED);
        }

        @Test
        @DisplayName("dequeue skips RUNNING jobs")
        void skipsRunning() {
            DDLJob job1 = new DDLJob();
            job1.setType(DDLType.CREATE_DATABASE);
            job1.setDbName("db1");
            queue.enqueue(job1);
            job1.setState(DDLState.RUNNING);
            queue.updateJob(job1);

            DDLJob job2 = new DDLJob();
            job2.setType(DDLType.CREATE_DATABASE);
            job2.setDbName("db2");
            queue.enqueue(job2);

            DDLJob dequeued = queue.dequeue();
            assertThat(dequeued).isNotNull();
            assertThat(dequeued.getDbName()).isEqualTo("db2");
        }
    }

    @Nested
    @DisplayName("GetJob")
    class GetJob {

        @Test
        @DisplayName("getJob returns null for unknown ID")
        void unknownReturnsNull() {
            assertThat(queue.getJob(999)).isNull();
        }

        @Test
        @DisplayName("getJob returns job from queue")
        void returnsFromQueue() {
            DDLJob job = new DDLJob();
            job.setType(DDLType.CREATE_DATABASE);
            job.setDbName("mydb");
            queue.enqueue(job);

            DDLJob found = queue.getJob(job.getId());
            assertThat(found).isNotNull();
            assertThat(found.getDbName()).isEqualTo("mydb");
        }

        @Test
        @DisplayName("getJob returns job from history")
        void returnsFromHistory() {
            DDLJob job = new DDLJob();
            job.setType(DDLType.CREATE_DATABASE);
            job.setDbName("histdb");
            queue.enqueue(job);
            job.setState(DDLState.DONE);
            queue.moveToHistory(job);

            DDLJob found = queue.getJob(job.getId());
            assertThat(found).isNotNull();
            assertThat(found.getDbName()).isEqualTo("histdb");
            assertThat(found.getState()).isEqualTo(DDLState.DONE);
        }
    }

    @Nested
    @DisplayName("UpdateJob")
    class UpdateJob {

        @Test
        @DisplayName("updateJob persists state changes")
        void persistsChanges() {
            DDLJob job = new DDLJob();
            job.setType(DDLType.CREATE_TABLE);
            queue.enqueue(job);

            job.setState(DDLState.RUNNING);
            queue.updateJob(job);

            DDLJob found = queue.getJob(job.getId());
            assertThat(found.getState()).isEqualTo(DDLState.RUNNING);
        }
    }

    @Nested
    @DisplayName("ListJobs")
    class ListJobs {

        @Test
        @DisplayName("listJobs returns empty when no jobs")
        void emptyList() {
            assertThat(queue.listJobs()).isEmpty();
        }

        @Test
        @DisplayName("listJobs returns all enqueued jobs")
        void returnsAll() {
            for (int i = 0; i < 3; i++) {
                DDLJob job = new DDLJob();
                job.setType(DDLType.CREATE_DATABASE);
                queue.enqueue(job);
            }
            assertThat(queue.listJobs()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("MoveToHistory")
    class MoveToHistory {

        @Test
        @DisplayName("moveToHistory removes from queue and adds to history")
        void movesCorrectly() {
            DDLJob job = new DDLJob();
            job.setType(DDLType.CREATE_DATABASE);
            queue.enqueue(job);
            long jobId = job.getId();

            assertThat(queue.listJobs()).hasSize(1);

            job.setState(DDLState.DONE);
            queue.moveToHistory(job);

            assertThat(queue.listJobs()).isEmpty();
            assertThat(queue.listHistory(10)).hasSize(1);
            assertThat(queue.listHistory(10).get(0).getId()).isEqualTo(jobId);
        }
    }

    @Nested
    @DisplayName("ListHistory")
    class ListHistory {

        @Test
        @DisplayName("listHistory returns empty when no history")
        void emptyHistory() {
            assertThat(queue.listHistory(10)).isEmpty();
        }

        @Test
        @DisplayName("listHistory respects limit")
        void respectsLimit() {
            for (int i = 0; i < 5; i++) {
                DDLJob job = new DDLJob();
                job.setType(DDLType.CREATE_DATABASE);
                queue.enqueue(job);
                job.setState(DDLState.DONE);
                queue.moveToHistory(job);
            }
            assertThat(queue.listHistory(3)).hasSize(3);
        }
    }

    @Nested
    @DisplayName("AllocJobId")
    class AllocJobId {

        @Test
        @DisplayName("allocJobId returns monotonically increasing IDs")
        void monotonicIds() {
            long id1 = queue.allocJobId();
            long id2 = queue.allocJobId();
            long id3 = queue.allocJobId();
            assertThat(id2).isGreaterThan(id1);
            assertThat(id3).isGreaterThan(id2);
        }
    }

    @Nested
    @DisplayName("Serialization round-trip")
    class SerializationRoundTrip {

        @Test
        @DisplayName("job survives serialization round-trip with all fields")
        void fullRoundTrip() {
            DDLJob job = new DDLJob();
            job.setType(DDLType.ADD_COLUMN);
            job.setDbId(10);
            job.setDbName("testdb");
            job.setTableId(20);
            job.setTableName("users");
            job.setError("some error");
            queue.enqueue(job);

            DDLJob found = queue.getJob(job.getId());
            assertThat(found.getType()).isEqualTo(DDLType.ADD_COLUMN);
            assertThat(found.getDbId()).isEqualTo(10);
            assertThat(found.getDbName()).isEqualTo("testdb");
            assertThat(found.getTableId()).isEqualTo(20);
            assertThat(found.getTableName()).isEqualTo("users");
            assertThat(found.getError()).isEqualTo("some error");
        }
    }
}
