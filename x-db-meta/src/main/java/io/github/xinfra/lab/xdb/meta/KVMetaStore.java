package io.github.xinfra.lab.xdb.meta;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.table.MetaCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of {@link MetaStore} backed by KV operations.
 * Uses functional interfaces so it can work with any KV transaction implementation
 * without introducing a compile-time dependency on x-kv.
 */
public class KVMetaStore implements MetaStore {

    @FunctionalInterface
    public interface KVGetter {
        byte[] get(byte[] key);
    }

    @FunctionalInterface
    public interface KVPutter {
        void put(byte[] key, byte[] value);
    }

    @FunctionalInterface
    public interface KVDeleter {
        void delete(byte[] key);
    }

    @FunctionalInterface
    public interface KVCas {
        boolean cas(byte[] key, byte[] expected, byte[] newValue);
    }

    private final KVGetter getter;
    private final KVPutter putter;
    private final KVDeleter deleter;
    private final KVCas cas;
    private final ObjectMapper objectMapper;

    public KVMetaStore(KVGetter getter, KVPutter putter, KVDeleter deleter, KVCas cas) {
        this.getter = getter;
        this.putter = putter;
        this.deleter = deleter;
        this.cas = cas;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public long getSchemaVersion() {
        byte[] value = getter.get(MetaCodec.schemaVersionKey());
        if (value == null || value.length == 0) {
            return 0;
        }
        return ByteBuffer.wrap(value).getLong();
    }

    @Override
    public void setSchemaVersion(long version) {
        byte[] value = new byte[8];
        ByteBuffer.wrap(value).putLong(version);
        putter.put(MetaCodec.schemaVersionKey(), value);
    }

    @Override
    public void createDatabase(DatabaseInfo db) {
        // Store the database info
        byte[] key = MetaCodec.dbKey(db.getId());
        putter.put(key, toJson(db));

        // Add database ID to the list
        List<Long> ids = listDatabaseIds();
        ids = new ArrayList<>(ids);
        ids.add(db.getId());
        putter.put(MetaCodec.dbListKey(), toJson(ids));
    }

    @Override
    public void dropDatabase(long dbId) {
        // Remove the database info
        deleter.delete(MetaCodec.dbKey(dbId));

        // Remove from the ID list
        List<Long> ids = listDatabaseIds();
        ids = new ArrayList<>(ids);
        ids.remove(Long.valueOf(dbId));
        putter.put(MetaCodec.dbListKey(), toJson(ids));

        // Remove table list for this database
        deleter.delete(MetaCodec.tableListKey(dbId));
    }

    @Override
    public DatabaseInfo getDatabase(long dbId) {
        byte[] value = getter.get(MetaCodec.dbKey(dbId));
        if (value == null || value.length == 0) {
            return null;
        }
        return fromJson(value, DatabaseInfo.class);
    }

    @Override
    public List<Long> listDatabaseIds() {
        byte[] value = getter.get(MetaCodec.dbListKey());
        if (value == null || value.length == 0) {
            return new ArrayList<>();
        }
        return fromJson(value, new TypeReference<List<Long>>() {});
    }

    @Override
    public void createTable(long dbId, TableInfo table) {
        // Store table info
        byte[] key = MetaCodec.tableKey(dbId, table.getId());
        putter.put(key, toJson(table));

        // Add table ID to the list for this database
        List<Long> ids = listTableIds(dbId);
        ids = new ArrayList<>(ids);
        ids.add(table.getId());
        putter.put(MetaCodec.tableListKey(dbId), toJson(ids));
    }

    @Override
    public void updateTable(long dbId, TableInfo table) {
        byte[] key = MetaCodec.tableKey(dbId, table.getId());
        putter.put(key, toJson(table));
    }

    @Override
    public void dropTable(long dbId, long tableId) {
        // Remove table info
        deleter.delete(MetaCodec.tableKey(dbId, tableId));

        // Remove from the table ID list
        List<Long> ids = listTableIds(dbId);
        ids = new ArrayList<>(ids);
        ids.remove(Long.valueOf(tableId));
        putter.put(MetaCodec.tableListKey(dbId), toJson(ids));
    }

    @Override
    public TableInfo getTable(long dbId, long tableId) {
        byte[] value = getter.get(MetaCodec.tableKey(dbId, tableId));
        if (value == null || value.length == 0) {
            return null;
        }
        return fromJson(value, TableInfo.class);
    }

    @Override
    public List<Long> listTableIds(long dbId) {
        byte[] value = getter.get(MetaCodec.tableListKey(dbId));
        if (value == null || value.length == 0) {
            return new ArrayList<>();
        }
        return fromJson(value, new TypeReference<List<Long>>() {});
    }

    @Override
    public long advanceSchemaVersion() {
        byte[] key = MetaCodec.schemaVersionKey();
        while (true) {
            byte[] value = getter.get(key);
            long current = (value != null && value.length > 0)
                    ? ByteBuffer.wrap(value).getLong() : 0;
            long next = current + 1;
            byte[] newValue = new byte[8];
            ByteBuffer.wrap(newValue).putLong(next);
            if (cas.cas(key, value, newValue)) {
                return next;
            }
        }
    }

    @Override
    public long allocAutoIncId(long tableId, int batchSize) {
        byte[] key = MetaCodec.autoIncKey(tableId);
        while (true) {
            byte[] value = getter.get(key);
            long current = (value != null && value.length > 0)
                    ? ByteBuffer.wrap(value).getLong() : 0;
            long newBase = current + batchSize;
            byte[] newValue = new byte[8];
            ByteBuffer.wrap(newValue).putLong(newBase);
            if (cas.cas(key, value, newValue)) {
                return current;
            }
        }
    }

    @Override
    public long allocGlobalId() {
        byte[] key = MetaCodec.globalIdKey();
        while (true) {
            byte[] value = getter.get(key);
            long current = (value != null && value.length > 0)
                    ? ByteBuffer.wrap(value).getLong() : 0;
            long next = current + 1;
            byte[] newValue = new byte[8];
            ByteBuffer.wrap(newValue).putLong(next);
            if (cas.cas(key, value, newValue)) {
                return next;
            }
        }
    }

    @Override
    public void putTableStats(long tableId, byte[] statsJson) {
        putter.put(MetaCodec.statsKey(tableId), statsJson);
    }

    @Override
    public byte[] getTableStats(long tableId) {
        return getter.get(MetaCodec.statsKey(tableId));
    }

    // --- JSON helpers ---

    private byte[] toJson(Object obj) {
        try {
            return objectMapper.writeValueAsBytes(obj);
        } catch (JsonProcessingException e) {
            throw XDBException.internal("Failed to serialize metadata to JSON", e);
        }
    }

    private <T> T fromJson(byte[] data, Class<T> clazz) {
        try {
            return objectMapper.readValue(data, clazz);
        } catch (IOException e) {
            throw XDBException.internal("Failed to deserialize metadata from JSON", e);
        }
    }

    private <T> T fromJson(byte[] data, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(data, typeRef);
        } catch (IOException e) {
            throw XDBException.internal("Failed to deserialize metadata from JSON", e);
        }
    }
}
