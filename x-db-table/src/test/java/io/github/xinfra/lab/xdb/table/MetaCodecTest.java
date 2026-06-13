package io.github.xinfra.lab.xdb.table;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MetaCodecTest {

    @Test
    void schemaVersionKey() {
        byte[] key = MetaCodec.schemaVersionKey();
        assertThat(new String(key, StandardCharsets.UTF_8)).isEqualTo("m_SchemaVersion");
    }

    @Test
    void dbKey() {
        byte[] key = MetaCodec.dbKey(1);
        assertThat(new String(key, StandardCharsets.UTF_8)).isEqualTo("m_DB:1");
    }

    @Test
    void dbKeyDifferentIds() {
        byte[] key1 = MetaCodec.dbKey(1);
        byte[] key2 = MetaCodec.dbKey(2);
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void tableKey() {
        byte[] key = MetaCodec.tableKey(1, 2);
        assertThat(new String(key, StandardCharsets.UTF_8)).isEqualTo("m_TBL:1:2");
    }

    @Test
    void tableKeyDifferentIds() {
        byte[] key1 = MetaCodec.tableKey(1, 1);
        byte[] key2 = MetaCodec.tableKey(1, 2);
        byte[] key3 = MetaCodec.tableKey(2, 1);
        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1).isNotEqualTo(key3);
    }

    @Test
    void dbListKey() {
        byte[] key = MetaCodec.dbListKey();
        assertThat(new String(key, StandardCharsets.UTF_8)).isEqualTo("m_DBs");
    }

    @Test
    void tableListKey() {
        byte[] key = MetaCodec.tableListKey(3);
        assertThat(new String(key, StandardCharsets.UTF_8)).isEqualTo("m_DB:3:tables");
    }

    @Test
    void ddlJobKey() {
        byte[] key = MetaCodec.ddlJobKey(42);
        assertThat(new String(key, StandardCharsets.UTF_8)).isEqualTo("m_DDLJob:42");
    }

    @Test
    void ddlJobQueueKey() {
        byte[] key = MetaCodec.ddlJobQueueKey();
        assertThat(new String(key, StandardCharsets.UTF_8)).isEqualTo("m_DDLJobQueue");
    }

    @Test
    void ddlHistoryKey() {
        byte[] key = MetaCodec.ddlHistoryKey(10);
        assertThat(new String(key, StandardCharsets.UTF_8)).isEqualTo("m_DDLHistory:10");
    }

    @Test
    void ddlOwnerKey() {
        byte[] key = MetaCodec.ddlOwnerKey();
        assertThat(new String(key, StandardCharsets.UTF_8)).isEqualTo("m_DDLOwner");
    }

    @Test
    void autoIncKey() {
        byte[] key = MetaCodec.autoIncKey(5);
        assertThat(new String(key, StandardCharsets.UTF_8)).isEqualTo("m_AutoInc:5");
    }

    @Test
    void globalIdKey() {
        byte[] key = MetaCodec.globalIdKey();
        assertThat(new String(key, StandardCharsets.UTF_8)).isEqualTo("m_GlobalId");
    }

    @Test
    void allKeysAreDistinct() {
        // Verify meta keys don't collide with each other
        byte[] k1 = MetaCodec.schemaVersionKey();
        byte[] k2 = MetaCodec.dbListKey();
        byte[] k3 = MetaCodec.ddlJobQueueKey();
        byte[] k4 = MetaCodec.ddlOwnerKey();
        byte[] k5 = MetaCodec.globalIdKey();

        assertThat(k1).isNotEqualTo(k2);
        assertThat(k2).isNotEqualTo(k3);
        assertThat(k3).isNotEqualTo(k4);
        assertThat(k4).isNotEqualTo(k5);
    }
}
