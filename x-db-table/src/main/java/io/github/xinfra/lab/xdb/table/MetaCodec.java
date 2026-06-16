package io.github.xinfra.lab.xdb.table;

import java.nio.charset.StandardCharsets;

public final class MetaCodec {
    private MetaCodec() {}

    public static byte[] schemaVersionKey() {
        return KeyPrefix.SCHEMA_VERSION_KEY.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] dbKey(long dbId) {
        return (KeyPrefix.DB_PREFIX + dbId).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] tableKey(long dbId, long tableId) {
        return (KeyPrefix.TABLE_META_PREFIX + dbId + ":" + tableId).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] dbListKey() {
        return KeyPrefix.DB_LIST_KEY.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] tableListKey(long dbId) {
        return (KeyPrefix.TABLE_LIST_PREFIX + dbId + KeyPrefix.TABLE_LIST_SUFFIX)
                .getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] ddlJobKey(long jobId) {
        return (KeyPrefix.DDL_JOB_PREFIX + jobId).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] ddlJobQueueKey() {
        return KeyPrefix.DDL_JOB_QUEUE_KEY.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] ddlHistoryKey(long jobId) {
        return (KeyPrefix.DDL_HISTORY_PREFIX + jobId).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] ddlOwnerKey() {
        return KeyPrefix.DDL_OWNER_KEY.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] autoIncKey(long tableId) {
        return (KeyPrefix.AUTO_INC_PREFIX + tableId).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] globalIdKey() {
        return KeyPrefix.GLOBAL_ID_KEY.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] statsKey(long tableId) {
        return (KeyPrefix.STATS_PREFIX + tableId).getBytes(StandardCharsets.UTF_8);
    }
}
