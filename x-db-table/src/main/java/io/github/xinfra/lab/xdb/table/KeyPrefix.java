package io.github.xinfra.lab.xdb.table;

public final class KeyPrefix {
    private KeyPrefix() {}

    public static final byte TABLE_PREFIX = 0x74;       // 't'
    public static final byte[] RECORD_PREFIX_SEP = {0x5F, 0x72}; // '_r'
    public static final byte[] INDEX_PREFIX_SEP  = {0x5F, 0x69}; // '_i'
    public static final byte META_PREFIX = 0x6D;        // 'm'

    public static final String SCHEMA_VERSION_KEY = "m_SchemaVersion";
    public static final String DB_PREFIX = "m_DB:";
    public static final String TABLE_META_PREFIX = "m_TBL:";
    public static final String DDL_JOB_PREFIX = "m_DDLJob:";
    public static final String DDL_JOB_QUEUE_KEY = "m_DDLJobQueue";
    public static final String DDL_HISTORY_PREFIX = "m_DDLHistory:";
    public static final String DDL_OWNER_KEY = "m_DDLOwner";
    public static final String AUTO_INC_PREFIX = "m_AutoInc:";
    public static final String DB_LIST_KEY = "m_DBs";
    public static final String TABLE_LIST_PREFIX = "m_DB:";
    public static final String TABLE_LIST_SUFFIX = ":tables";
    public static final String GLOBAL_ID_KEY = "m_GlobalId";
    public static final String STATS_PREFIX = "m_Stats:";
}
