# x-db 架构设计文档

> 基于 x-kv 构建的分布式 SQL 数据库，兼容 MySQL 协议

## 1. 系统概述

x-db 是一个构建在 x-kv（类 TiKV 分布式事务 KV 存储）之上的 SQL 层。x-kv 提供 MVCC、Percolator 2PC、PD（Placement Driver + TSO）、Region 分片和 Coprocessor 算子下推能力。x-db 在此之上实现：SQL 解析、Schema 管理、查询优化、执行引擎、MySQL 协议和分布式事务协调。

### 1.1 x-kv 关键接口

| 接口 | 说明 |
|------|------|
| `TxnClient` | 事务客户端，`begin()` / `beginPessimistic()` / `executeWithRetry()` |
| `Transaction` | 事务句柄，`get/put/delete/scan/commit/rollback`，`startTs()` 获取开始时间戳 |
| `PdClient` | PD Leader 发现与 failover，`blockingStub()` 调用 AllocID / GetTimestamp |
| `RegionCache` | 客户端 Region 路由缓存 |
| `TsoBatcher` | 异步 TSO 分配（bidi stream） |
| `RegionRequestSender` | KV 请求路由 + 自动重试 + 退避 |

### 1.2 整体架构图

```
                          ┌─────────────────────────────────┐
                          │          MySQL Client            │
                          │    (mysql CLI / JDBC Driver)     │
                          └──────────────┬──────────────────┘
                                         │ MySQL Protocol (TCP 4000)
                          ┌──────────────▼──────────────────┐
                          │        x-db-server               │
                          │   (Netty MySQL Protocol 实现)     │
                          ├──────────────────────────────────┤
                          │        x-db-session              │
                          │  (Session 管理 / 事务协调)        │
                          ├──────────┬───────────┬───────────┤
                          │ x-db-   │ x-db-     │ x-db-     │
                          │ parser  │ planner   │ executor  │
                          │ (SQL    │ (查询优化) │ (执行引擎) │
                          │  解析)  │           │           │
                          ├─────────┴───────────┴───────────┤
                          │        x-db-ddl                  │
                          │  (F1 Online Schema Change)       │
                          ├──────────────────────────────────┤
                          │   x-db-meta    │   x-db-table    │
                          │ (Schema 元数据) │ (表编码/解码)    │
                          ├────────────────┴─────────────────┤
                          │      x-db-expression             │
                          │  (类型系统 / 表达式求值)           │
                          ├──────────────────────────────────┤
                          │        x-db-common               │
                          │   (字节编码 / 工具类)              │
                          └──────────────┬──────────────────┘
                                         │ gRPC
                    ┌────────────────────┼────────────────────┐
                    │                    │                    │
              ┌─────▼─────┐       ┌─────▼─────┐       ┌─────▼─────┐
              │  x-kv      │       │  x-kv      │       │  x-kv      │
              │  Store-1   │       │  Store-2   │       │  Store-3   │
              └─────┬──────┘       └─────┬──────┘       └─────┬──────┘
                    │                    │                    │
              ┌─────▼──────────────────────▼──────────────────▼─────┐
              │                     PD Cluster                      │
              │            (TSO / Region 调度 / 元数据)               │
              └─────────────────────────────────────────────────────┘
```

---

## 2. 模块设计

### 2.1 Maven 模块结构

```
x-db/
├── pom.xml                          # Parent POM
├── x-db-common/                     # 共享类型、字节编码工具
├── x-db-parser/                     # ANTLR4 MySQL SQL 解析器
├── x-db-expression/                 # 类型系统 & 表达式求值
├── x-db-table/                      # 表编码器（行/索引 ↔ KV 编码）
├── x-db-meta/                       # Schema 元数据（Catalog、InfoSchema）
├── x-db-ddl/                        # F1 在线 Schema 变更
├── x-db-planner/                    # 查询优化器（逻辑计划 → 物理计划）
├── x-db-executor/                   # Volcano 模型执行引擎
├── x-db-session/                    # Session 管理 & 分布式事务协调
├── x-db-server/                     # MySQL 协议服务器（Netty）
└── x-db-test/                       # 集成测试
```

### 2.2 模块依赖关系

```
x-db-server
  └── x-db-session
        ├── x-db-executor
        │     ├── x-db-planner
        │     │     ├── x-db-expression
        │     │     └── x-db-meta
        │     ├── x-db-table
        │     │     └── x-db-common
        │     └── x-db-expression
        ├── x-db-ddl
        │     └── x-db-meta
        ├── x-db-parser
        └── x-db-meta
              └── x-db-common
```

---

## 3. 表编码器 (x-db-table)

### 3.1 编码方案

采用 TiDB 的编码方案，将 SQL 表数据映射到有序 KV 键值对：

#### 行数据编码

```
Row Key:   t{tableID}_r{rowHandle}
Row Value: [col1_value, col2_value, ..., colN_value]  (column-aware encoding)
```

- `tablePrefix` = `0x74` ('t')
- `recordPrefixSep` = `0x5F72` ('_r')
- tableID 和 rowHandle 使用 8 字节大端序编码，符号位翻转以保证无符号排序

#### 索引数据编码

```
Non-unique Index Key:  t{tableID}_i{indexID}_{indexCol1}_{indexCol2}_{rowHandle}
Non-unique Index Val:  null (空)

Unique Index Key:      t{tableID}_i{indexID}_{indexCol1}_{indexCol2}
Unique Index Val:      rowHandle
```

- `indexPrefixSep` = `0x5F69` ('_i')

#### 元数据编码

```
Schema Version:    m_SchemaVersion              → int64
Database Info:     m_DB:{dbID}                  → JSON(DatabaseInfo)
Table Info:        m_TBL:{dbID}:{tableID}       → JSON(TableInfo)
DDL Job:           m_DDLJob:{jobID}             → JSON(DDLJob)
DDL Job Queue:     m_DDLJobQueue                → JSON(List<Long>)
Auto Increment:    m_AutoInc:{tableID}          → int64
```

### 3.2 Memcomparable 编码规则

所有索引列值使用 memcomparable 编码（保证字节序 = 逻辑序）：

| 类型 | 编码方式 |
|------|---------|
| NULL | `0x00` 单字节（排在所有值之前） |
| INT64 | `0x03` + 8 字节大端序，符号位翻转 |
| UINT64 | `0x04` + 8 字节大端序 |
| FLOAT64 | `0x05` + IEEE 754 转换后 8 字节（正数翻转符号位，负数全部翻转） |
| BYTES/STRING | `0x06` + 每 8 字节一组，末尾补 `0x00`，组标记 = `0xFF - padding_count`，全 8 字节时标记 `0xFF` |
| DECIMAL | `0x07` + 自定义编码（整数部分 + 小数部分，9 位一组） |
| DATETIME | `0x08` + 转换为 uint64 的紧凑表示 |
| MAX | `0xFE` 单字节（排在所有值之后，用于范围扫描上界） |

### 3.3 行值编码（非索引列）

行值使用紧凑列编码（不需要 memcomparable）：

```
RowValue = [flag, colID1, colValue1, colID2, colValue2, ...]
```

- flag: 1 字节，`0x80` 表示新编码格式
- colID: varint 编码
- colValue: 类型前缀 + 值（长度前缀的字符串，固定长度的整数等）

### 3.4 关键类

```
x-db-table/
└── src/main/java/io/github/xinfra/lab/xdb/table/
    ├── TableCodec.java          # 行键/索引键编解码入口
    ├── RowCodec.java            # 行值编解码
    ├── DatumCodec.java          # 单值 memcomparable 编码
    ├── MetaCodec.java           # 元数据键编解码
    └── KeyPrefix.java           # 键前缀常量
```

---

## 4. SQL 解析器 (x-db-parser)

### 4.1 技术选型

使用 ANTLR4 构建 MySQL 兼容的 SQL 解析器。

### 4.2 支持的语法

#### DDL
```sql
CREATE DATABASE [IF NOT EXISTS] db_name
    [CHARACTER SET charset_name] [COLLATE collation_name]
DROP DATABASE [IF EXISTS] db_name
CREATE TABLE [IF NOT EXISTS] table_name (
    column_def, ...
    [, PRIMARY KEY (col, ...)]
    [, KEY|INDEX index_name (col, ...)]
    [, UNIQUE KEY index_name (col, ...)]
) [ENGINE=...] [DEFAULT CHARSET=...] [AUTO_INCREMENT=...]
DROP TABLE [IF EXISTS] table_name
ALTER TABLE table_name
    ADD COLUMN column_def [FIRST | AFTER col]
    | DROP COLUMN col_name
    | ADD INDEX index_name (col, ...)
    | ADD UNIQUE INDEX index_name (col, ...)
    | DROP INDEX index_name
TRUNCATE TABLE table_name
```

#### DML
```sql
INSERT INTO table_name [(col, ...)] VALUES (val, ...) [, (val, ...)]
INSERT INTO table_name SET col=val [, col=val]
SELECT [DISTINCT] select_expr, ...
    FROM table_references
    [WHERE where_condition]
    [GROUP BY col, ... [HAVING having_condition]]
    [ORDER BY col [ASC|DESC], ...]
    [LIMIT count [OFFSET offset]]
UPDATE table_name SET col=val [, col=val] [WHERE where_condition]
DELETE FROM table_name [WHERE where_condition]
```

#### Utility
```sql
USE db_name
SHOW DATABASES
SHOW TABLES [FROM db_name]
SHOW COLUMNS FROM table_name
SHOW CREATE TABLE table_name
SHOW INDEX FROM table_name
EXPLAIN select_statement
DESCRIBE table_name
SET [SESSION|GLOBAL] variable = value
BEGIN [WORK] | START TRANSACTION
COMMIT [WORK]
ROLLBACK [WORK]
```

### 4.3 AST 节点体系

```
Statement (sealed interface)
├── DDLStatement
│   ├── CreateDatabaseStmt   { name, ifNotExists, charset, collation }
│   ├── DropDatabaseStmt     { name, ifExists }
│   ├── CreateTableStmt      { dbName, tableName, columns, indices, options }
│   ├── DropTableStmt        { tableName, ifExists }
│   ├── AlterTableStmt       { tableName, List<AlterSpec> }
│   └── TruncateTableStmt    { tableName }
├── DMLStatement
│   ├── SelectStmt           { fields, from, where, groupBy, having, orderBy, limit }
│   ├── InsertStmt           { tableName, columns, values | setList }
│   ├── UpdateStmt           { tableName, setList, where }
│   └── DeleteStmt           { tableName, where }
├── TransactionStmt
│   ├── BeginStmt            { pessimistic }
│   ├── CommitStmt
│   └── RollbackStmt
└── UtilityStmt
    ├── UseStmt              { dbName }
    ├── ShowStmt             { showType, dbName, tableName }
    ├── ExplainStmt          { statement }
    ├── SetStmt              { variable, value, scope }
    └── DescribeStmt         { tableName }
```

### 4.4 表达式 AST

```
ExprNode (sealed interface)
├── ColumnRefExpr       { table, column }
├── LiteralExpr         { value, type }
├── BinaryOpExpr        { left, op, right }
├── UnaryOpExpr         { op, operand }
├── FunctionCallExpr    { name, args, distinct }
├── AggFunctionExpr     { name, args, distinct }
├── BetweenExpr         { expr, low, high, not }
├── InExpr              { expr, list, not }
├── LikeExpr            { expr, pattern, not }
├── IsNullExpr          { expr, not }
├── ExistsExpr          { subquery }
├── SubqueryExpr        { select }
├── CastExpr            { expr, targetType }
├── CaseWhenExpr        { compareExpr, whenClauses, elseExpr }
├── ParenExpr           { expr }
└── StarExpr            { table }
```

### 4.5 关键类

```
x-db-parser/
├── src/main/antlr4/io/github/xinfra/lab/xdb/parser/
│   ├── MySQLLexer.g4            # 词法规则
│   └── MySQLParser.g4           # 语法规则
└── src/main/java/io/github/xinfra/lab/xdb/parser/
    ├── SQLParser.java            # 解析入口：parse(sql) → Statement
    ├── AstBuilder.java           # ANTLR Visitor → AST 节点
    ├── ast/                      # AST 节点定义
    │   ├── Statement.java
    │   ├── expr/                 # 表达式节点
    │   ├── ddl/                  # DDL 语句节点
    │   ├── dml/                  # DML 语句节点
    │   └── util/                 # Utility 语句节点
    └── ParseException.java       # 解析错误
```

---

## 5. 类型系统与表达式 (x-db-expression)

### 5.1 数据类型

```java
enum DataType {
    // 整数
    TINYINT(1), SMALLINT(2), INT(4), BIGINT(8),
    // 浮点
    FLOAT(4), DOUBLE(8),
    // 定点
    DECIMAL(-1),
    // 字符串
    CHAR(-1), VARCHAR(-1), TEXT(-1),
    // 二进制
    BINARY(-1), VARBINARY(-1), BLOB(-1),
    // 日期时间
    DATE(3), DATETIME(8), TIMESTAMP(4), TIME(3), YEAR(1),
    // 布尔
    BOOLEAN(1),
    // NULL
    NULL(0);
}
```

### 5.2 Datum — 通用值容器

```java
sealed interface Datum {
    record IntDatum(long value) implements Datum {}
    record DoubleDatum(double value) implements Datum {}
    record DecimalDatum(BigDecimal value) implements Datum {}
    record StringDatum(String value) implements Datum {}
    record BytesDatum(byte[] value) implements Datum {}
    record DateTimeDatum(LocalDateTime value) implements Datum {}
    record NullDatum() implements Datum {}
}
```

### 5.3 表达式求值

```java
interface Expression {
    Datum eval(EvalContext ctx, Row row);
    DataType returnType();
}
```

### 5.4 内置函数

**聚合函数：** COUNT, SUM, AVG, MIN, MAX, GROUP_CONCAT
**数学函数：** ABS, CEIL, FLOOR, ROUND, MOD, POWER, SQRT, RAND
**字符串函数：** CONCAT, SUBSTRING, LENGTH, CHAR_LENGTH, UPPER, LOWER, TRIM, REPLACE, LIKE pattern matching
**日期函数：** NOW, CURDATE, CURTIME, DATE_FORMAT, DATE_ADD, DATE_SUB, DATEDIFF, YEAR, MONTH, DAY
**控制流函数：** IF, IFNULL, NULLIF, COALESCE, CASE WHEN
**类型转换函数：** CAST, CONVERT

### 5.5 类型转换规则

```
INT → BIGINT → DOUBLE → DECIMAL → STRING
DATE → DATETIME → STRING
BOOLEAN → INT
```

### 5.6 关键类

```
x-db-expression/
└── src/main/java/io/github/xinfra/lab/xdb/expression/
    ├── DataType.java
    ├── Datum.java                # sealed interface + record implementations
    ├── DatumComparator.java      # 类型感知比较
    ├── TypeCoercion.java         # 隐式类型转换规则
    ├── Expression.java           # 表达式求值接口
    ├── EvalContext.java          # 求值上下文（当前时间、时区等）
    ├── Row.java                  # 行数据：Datum[] values
    ├── ColumnRef.java
    ├── Constant.java
    ├── BinaryOp.java
    ├── UnaryOp.java
    ├── CompareOp.java
    ├── LogicalOp.java
    ├── CastExpr.java
    ├── InExpr.java
    ├── BetweenExpr.java
    ├── LikeExpr.java
    ├── IsNullExpr.java
    ├── CaseWhenExpr.java
    ├── agg/                      # 聚合函数
    │   ├── AggFunction.java
    │   ├── CountAgg.java
    │   ├── SumAgg.java
    │   ├── AvgAgg.java
    │   ├── MinAgg.java
    │   └── MaxAgg.java
    └── func/                     # 内置标量函数
        ├── ScalarFunction.java
        ├── MathFunctions.java
        ├── StringFunctions.java
        └── DateFunctions.java
```

---

## 6. Schema 元数据 (x-db-meta)

### 6.1 Schema 模型

```java
record DatabaseInfo(
    long id,
    String name,
    String charset,         // default: utf8mb4
    String collation,       // default: utf8mb4_general_ci
    SchemaState state
)

record TableInfo(
    long id,
    String name,
    long dbId,
    List<ColumnInfo> columns,
    List<IndexInfo> indices,
    long autoIncId,
    String charset,
    String collation,
    String comment,
    SchemaState state,
    long schemaVersion      // 此表最后变更时的 schema version
)

record ColumnInfo(
    long id,
    String name,
    int offset,             // 在行中的位置
    DataType type,
    int fieldLength,
    int decimal,
    boolean nullable,
    boolean autoIncrement,
    boolean unsigned,
    Expression defaultValue,
    String comment,
    SchemaState state
)

record IndexInfo(
    long id,
    String name,
    long tableId,
    List<IndexColumn> columns,
    boolean unique,
    boolean primary,
    String comment,
    SchemaState state
)

record IndexColumn(
    String name,
    int offset,             // 列在 TableInfo.columns 中的偏移
    int length              // 前缀长度，0 = 全列
)

enum SchemaState {
    ABSENT,
    DELETE_ONLY,
    WRITE_ONLY,
    WRITE_REORGANIZATION,
    PUBLIC
}
```

### 6.2 InfoSchema — 内存 Schema 快照

```java
interface InfoSchema {
    long schemaVersion();

    Optional<DatabaseInfo> databaseByName(String name);
    Optional<DatabaseInfo> databaseById(long id);
    List<DatabaseInfo> allDatabases();

    Optional<TableInfo> tableByName(String dbName, String tableName);
    Optional<TableInfo> tableById(long tableId);
    List<TableInfo> tablesInDatabase(String dbName);

    Optional<IndexInfo> indexByName(String dbName, String tableName, String indexName);
}
```

- 不可变快照，版本号递增
- 所有节点定期（每 1 秒）检查 `m_SchemaVersion`，版本变化时重新加载

### 6.3 元数据存储层

```java
class MetaStore {
    private final TxnClient txnClient;

    // Database CRUD
    void createDatabase(DatabaseInfo db);
    Optional<DatabaseInfo> getDatabase(long dbId);
    List<DatabaseInfo> listDatabases();

    // Table CRUD
    void createTable(TableInfo table);
    Optional<TableInfo> getTable(long dbId, long tableId);
    void updateTable(TableInfo table);
    List<TableInfo> listTables(long dbId);

    // Schema version
    long getSchemaVersion();
    long incrementSchemaVersion();

    // Auto-increment ID
    long allocAutoIncId(long tableId, int batchSize);

    // Global ID allocation (via PD)
    long allocGlobalId();
}
```

### 6.4 关键类

```
x-db-meta/
└── src/main/java/io/github/xinfra/lab/xdb/meta/
    ├── model/
    │   ├── DatabaseInfo.java
    │   ├── TableInfo.java
    │   ├── ColumnInfo.java
    │   ├── IndexInfo.java
    │   ├── IndexColumn.java
    │   └── SchemaState.java
    ├── InfoSchema.java           # 接口
    ├── InfoSchemaImpl.java       # 不可变快照实现
    ├── InfoSchemaCache.java      # 版本感知缓存 + 后台刷新
    ├── MetaStore.java            # KV 元数据读写
    └── AutoIdAllocator.java      # 自增 ID 批量分配
```

---

## 7. F1 在线 Schema 变更 (x-db-ddl)

### 7.1 设计原理

参考 Google F1 论文和 TiDB 实现：

**核心不变式：** 在任意时刻，集群中最多存在两个相邻的 schema 版本。这保证了：
- 不会出现两个节点对同一数据的可见性理解差距超过一个状态的情况
- 避免了"幽灵写入"和"数据丢失"问题

**状态转换保证：**
- 每次只推进一个状态
- 推进前等待所有节点追赶到前一个版本（通过等待 2 × lease_duration）
- schema version 是全局单调递增的

### 7.2 DDL Job 模型

```java
record DDLJob(
    long id,
    DDLType type,
    long schemaId,          // 目标数据库 ID
    long tableId,           // 目标表 ID（如适用）
    SchemaState schemaState,// 当前状态
    String args,            // JSON 编码的参数（列定义、索引定义等）
    JobState state,         // QUEUED / RUNNING / DONE / CANCELLED / FAILED
    String error,           // 失败原因
    long createTime,
    long finishTime,
    long schemaVersion      // 此 job 对应的 schema version
)

enum DDLType {
    CREATE_DATABASE, DROP_DATABASE,
    CREATE_TABLE, DROP_TABLE, TRUNCATE_TABLE,
    ADD_COLUMN, DROP_COLUMN,
    ADD_INDEX, DROP_INDEX
}

enum JobState {
    QUEUED, RUNNING, DONE, CANCELLED, FAILED
}
```

### 7.3 各 DDL 操作的状态转换

| 操作 | 状态转换路径 |
|------|-------------|
| CREATE DATABASE | ABSENT → PUBLIC（单步） |
| DROP DATABASE | PUBLIC → ABSENT（单步，删除所有表） |
| CREATE TABLE | ABSENT → PUBLIC（单步，表初始为空） |
| DROP TABLE | PUBLIC → WRITE_ONLY → DELETE_ONLY → ABSENT |
| ADD COLUMN | ABSENT → DELETE_ONLY → WRITE_ONLY → WRITE_REORG → PUBLIC |
| DROP COLUMN | PUBLIC → WRITE_ONLY → DELETE_ONLY → ABSENT |
| ADD INDEX | ABSENT → DELETE_ONLY → WRITE_ONLY → WRITE_REORG → PUBLIC |
| DROP INDEX | PUBLIC → WRITE_ONLY → DELETE_ONLY → ABSENT |

### 7.4 DDL Owner 选举

```java
class DDLOwnerManager {
    private static final String OWNER_KEY = "m_DDLOwner";
    private static final long LEASE_DURATION_MS = 10_000;
    private static final long RENEW_INTERVAL_MS = 2_000;

    // 使用 KV CAS 实现 lease 选举
    // Owner 定期续约；其他节点检测到 lease 过期后竞选
    boolean tryBecomeOwner();
    boolean renewLease();
    boolean isOwner();
}
```

### 7.5 DDL Worker

```java
class DDLWorker {
    // 后台线程，每个节点运行一个
    // 只有 owner 节点执行 DDL job
    //
    // 工作循环：
    // 1. 检查是否是 owner
    // 2. 从 m_DDLJobQueue 取出待执行的 job
    // 3. 执行一步状态转换
    // 4. 更新 table info + schema version
    // 5. 等待 2 × lease_duration（确保所有节点追赶）
    // 6. 重复直到 job 完成
    void runWorker();
    void handleJob(DDLJob job);

    // 各 DDL 类型的状态转换实现
    void onCreateTable(DDLJob job);
    void onDropTable(DDLJob job);
    void onAddColumn(DDLJob job);
    void onDropColumn(DDLJob job);
    void onAddIndex(DDLJob job);   // 包含 backfill 逻辑
    void onDropIndex(DDLJob job);
}
```

### 7.6 Index Backfill（ADD INDEX 的 WRITE_REORG 阶段）

```
1. 将 index 状态设为 WRITE_REORGANIZATION
2. 此后所有新写入都会同时维护索引
3. 扫描所有现有行，批量构建索引 KV 对
4. 扫描使用 snapshot（start_ts），保证一致性
5. 批量写入索引 KV（通过事务）
6. 完成后推进到 PUBLIC
```

### 7.7 关键类

```
x-db-ddl/
└── src/main/java/io/github/xinfra/lab/xdb/ddl/
    ├── DDLJob.java
    ├── DDLType.java
    ├── JobState.java
    ├── DDLWorker.java            # DDL 执行引擎
    ├── DDLOwnerManager.java      # Owner 选举
    ├── DDLJobQueue.java          # Job 队列管理
    ├── handler/
    │   ├── CreateTableHandler.java
    │   ├── DropTableHandler.java
    │   ├── AddColumnHandler.java
    │   ├── DropColumnHandler.java
    │   ├── AddIndexHandler.java  # 包含 backfill
    │   └── DropIndexHandler.java
    └── backfill/
        └── IndexBackfiller.java  # 索引回填
```

---

## 8. 查询优化器 (x-db-planner)

### 8.1 优化流程

```
SQL AST (Statement)
  → [语义分析 / 名称解析]
  → Logical Plan (逻辑计划)
  → [逻辑优化 (RBO)]
  → Optimized Logical Plan
  → [物理优化 (CBO)]
  → Physical Plan (物理计划)
```

### 8.2 逻辑计划节点

```java
sealed interface LogicalPlan {
    Schema outputSchema();        // 输出列定义
    List<LogicalPlan> children(); // 子计划
}

// 数据源
record LogicalTableScan(TableInfo table, List<ColumnInfo> outputColumns) implements LogicalPlan {}
record LogicalIndexScan(TableInfo table, IndexInfo index, Range scanRange,
                        List<ColumnInfo> outputColumns) implements LogicalPlan {}
record LogicalDual() implements LogicalPlan {}

// 关系运算
record LogicalSelection(LogicalPlan child, List<Expression> conditions) implements LogicalPlan {}
record LogicalProjection(LogicalPlan child, List<Expression> exprs,
                         List<String> aliases) implements LogicalPlan {}
record LogicalSort(LogicalPlan child, List<SortItem> sortItems) implements LogicalPlan {}
record LogicalLimit(LogicalPlan child, long count, long offset) implements LogicalPlan {}
record LogicalJoin(LogicalPlan left, LogicalPlan right, JoinType type,
                   Expression condition) implements LogicalPlan {}
record LogicalAggregation(LogicalPlan child, List<AggFunction> aggFuncs,
                          List<Expression> groupByExprs) implements LogicalPlan {}

// DML
record LogicalInsert(TableInfo table, List<ColumnInfo> columns,
                     List<List<Expression>> rows) implements LogicalPlan {}
record LogicalUpdate(LogicalPlan child, TableInfo table,
                     List<Assignment> assignments) implements LogicalPlan {}
record LogicalDelete(LogicalPlan child, TableInfo table) implements LogicalPlan {}

// Utility
record LogicalShowStmt(ShowType type, String dbName, String tableName) implements LogicalPlan {}
record LogicalExplain(LogicalPlan plan) implements LogicalPlan {}
```

### 8.3 逻辑优化规则 (Rule-Based Optimization)

按固定顺序应用：

#### 1. 列裁剪 (Column Pruning)
- 从上往下传播所需列集合
- Scan 只读取需要的列（减少 decode 开销）
- 特别重要：跳过不需要的大列（TEXT/BLOB）

#### 2. 谓词下推 (Predicate Pushdown)
- 将 WHERE 条件尽可能推到数据源（TableScan / IndexScan）
- Selection 条件拆分：AND 连接的条件独立下推
- Join 条件下推：等值条件下推到 Join，非等值条件下推到子节点
- 不下推包含聚合函数或子查询的条件

#### 3. 常量折叠 (Constant Folding)
- 编译期计算纯常量表达式：`1 + 2` → `3`
- 简化条件：`true AND x` → `x`，`false OR x` → `x`
- 消除无效条件：`WHERE false` → empty result

#### 4. TopN 下推
- `Sort + Limit` 合并为 `TopN` 算子
- TopN 下推到数据源（如果排序键是索引前缀）

### 8.4 物理计划节点

```java
sealed interface PhysicalPlan {
    Schema outputSchema();
    void open();
    Row next();
    void close();
}

// Scan
record PhysicalTableScan(TableInfo table, List<ColumnInfo> columns,
                          List<Expression> pushdownConditions,
                          Transaction txn) implements PhysicalPlan {}
record PhysicalIndexScan(TableInfo table, IndexInfo index,
                          Range scanRange, boolean needTableLookup,
                          Transaction txn) implements PhysicalPlan {}
record PhysicalIndexLookup(PhysicalIndexScan indexScan,
                            TableInfo table, Transaction txn) implements PhysicalPlan {}
record PhysicalPointGet(TableInfo table, long handle,
                         Transaction txn) implements PhysicalPlan {}

// Relational
record PhysicalSelection(PhysicalPlan child, Expression condition) implements PhysicalPlan {}
record PhysicalProjection(PhysicalPlan child, List<Expression> exprs) implements PhysicalPlan {}
record PhysicalSort(PhysicalPlan child, List<SortItem> sortItems) implements PhysicalPlan {}
record PhysicalLimit(PhysicalPlan child, long count, long offset) implements PhysicalPlan {}
record PhysicalTopN(PhysicalPlan child, List<SortItem> sortItems,
                     long count, long offset) implements PhysicalPlan {}
record PhysicalHashJoin(PhysicalPlan build, PhysicalPlan probe,
                         JoinType type, Expression condition) implements PhysicalPlan {}
record PhysicalNestedLoopJoin(PhysicalPlan outer, PhysicalPlan inner,
                               JoinType type, Expression condition) implements PhysicalPlan {}
record PhysicalHashAgg(PhysicalPlan child, List<AggFunction> aggFuncs,
                        List<Expression> groupByExprs) implements PhysicalPlan {}
record PhysicalStreamAgg(PhysicalPlan child, List<AggFunction> aggFuncs,
                          List<Expression> groupByExprs) implements PhysicalPlan {}
```

### 8.5 物理优化策略

#### 访问路径选择
```
对每个 TableScan：
1. 收集可用索引
2. 对每个索引，检查 WHERE 条件是否匹配索引前缀
3. 计算每个访问路径的代价：
   - TableScan: cost = tableRowCount × scanCostPerRow
   - IndexScan: cost = matchedRows × indexScanCostPerRow + lookupRows × lookupCostPerRow
   - PointGet: cost = 1 (直接 KV get，最优)
4. 选择代价最低的路径
```

#### Join 算法选择
```
- 小表 × 大表（小表 < 10000 行）：HashJoin（小表 build, 大表 probe）
- 等值 Join 且双方都有索引：MergeJoin（未来实现）
- 其他：NestedLoopJoin
```

### 8.6 语义分析 (Name Resolution & Type Check)

```java
class Analyzer {
    // 名称解析：将 ColumnRefExpr 绑定到具体的 table.column
    // 类型检查：验证表达式类型兼容性
    // 权限检查（未来）
    LogicalPlan analyze(Statement stmt, InfoSchema schema, String currentDb);
}
```

### 8.7 关键类

```
x-db-planner/
└── src/main/java/io/github/xinfra/lab/xdb/planner/
    ├── Analyzer.java             # 语义分析
    ├── Planner.java              # 优化器入口
    ├── logical/                  # 逻辑计划节点
    ├── physical/                 # 物理计划节点
    ├── optimize/
    │   ├── LogicalOptimizer.java # RBO 规则引擎
    │   ├── ColumnPruner.java
    │   ├── PredicatePushDown.java
    │   ├── ConstantFolder.java
    │   └── TopNPushDown.java
    ├── cost/
    │   ├── CostModel.java        # 代价模型
    │   └── Statistics.java       # 统计信息（行数、NDV）
    └── plan/
        ├── Schema.java           # 输出 schema 定义
        └── SortItem.java
```

---

## 9. 执行引擎 (x-db-executor)

### 9.1 Volcano 迭代器模型

```java
interface Executor extends AutoCloseable {
    void open();
    Row next();           // 返回 null 表示 EOF
    void close();
    List<ColumnInfo> outputSchema();
}
```

### 9.2 Executor 实现

#### 扫描算子

**TableScanExecutor**
```
open():
  1. 计算 scan range: [t{tableID}_r{MIN}, t{tableID}_r{MAX})
  2. 调用 txn.scan(startKey, endKey, batchSize)
  3. 初始化行解码器

next():
  1. 从 scan iterator 取下一个 KV pair
  2. 解码 key → rowHandle
  3. 解码 value → Row (只解码需要的列)
  4. 如有 pushdown conditions，在此过滤
  5. 返回 Row
```

**IndexScanExecutor**
```
open():
  1. 根据索引范围计算 scan range
  2. 调用 txn.scan(startKey, endKey, batchSize)

next():
  1. 从 scan iterator 取下一个 index KV
  2. 解码 index key → (indexValues, rowHandle)
  3. 如果 needTableLookup=false（覆盖索引），直接返回
  4. 否则 txn.get(rowKey) 获取完整行
```

**IndexLookupExecutor**
```
批量模式：
  1. 从 IndexScan 批量取 N 个 rowHandle
  2. 调用 txn.batchGet(rowKeys) 批量获取
  3. 逐行输出
```

#### 关系算子

**SelectionExecutor** — 过滤：`if (condition.eval(row) == true) return row; else skip`
**ProjectionExecutor** — 投影：`return new Row(exprs.map(e -> e.eval(row)))`
**SortExecutor** — 排序：`open() 时全部读入内存 → Arrays.sort → next() 逐行输出`
**LimitExecutor** — 限制：计数器，达到 limit 后返回 null
**TopNExecutor** — 堆排序：维护大小为 N 的优先队列

**HashJoinExecutor**
```
open():
  1. 读取 build 端所有行，按 join key 建 HashMap<JoinKey, List<Row>>
  
next():
  1. 从 probe 端取一行
  2. 计算 join key
  3. 在 HashMap 中查找匹配行
  4. 逐个输出匹配的 (probe_row, build_row) 组合
  5. LEFT JOIN: probe 行无匹配时输出 (probe_row, nulls)
```

**HashAggExecutor**
```
open():
  1. 读取所有输入行
  2. 按 group-by key 分组到 HashMap<GroupKey, AggState[]>
  3. 每行更新对应 group 的聚合状态

next():
  1. 从 HashMap 迭代器取下一个 (groupKey, aggStates)
  2. 计算最终聚合值
  3. 返回 Row(groupKey columns + agg results)
```

#### DML 算子

**InsertExecutor**
```
open():
  1. 分配 auto-increment ID（如需要）
  2. 对每行：
     a. 编码 row → KV (TableCodec.encodeRow)
     b. 编码 index → KV (TableCodec.encodeIndex) for each index
     c. 调用 txn.put(rowKey, rowValue)
     d. 调用 txn.put(indexKey, indexValue) for each index
  3. 唯一索引冲突检测：先 txn.get(indexKey)，存在则报 DuplicateEntry

next(): 返回 affected rows count
```

**UpdateExecutor**
```
open():
  1. 从子 Executor 读取待更新的行

next():
  1. 取下一个旧行
  2. 计算新值（应用 SET 赋值）
  3. 删除旧索引 KV
  4. 写入新行 KV + 新索引 KV
  5. 返回 affected rows count
```

**DeleteExecutor**
```
next():
  1. 从子 Executor 读取行
  2. 删除行 KV: txn.delete(rowKey)
  3. 删除所有索引 KV: txn.delete(indexKey) for each index
  4. 返回 affected rows count
```

### 9.3 ExecutorBuilder

```java
class ExecutorBuilder {
    // PhysicalPlan → Executor 递归构建
    Executor build(PhysicalPlan plan, Transaction txn) {
        return switch (plan) {
            case PhysicalTableScan p -> new TableScanExecutor(p, txn);
            case PhysicalIndexScan p -> new IndexScanExecutor(p, txn);
            case PhysicalSelection p -> new SelectionExecutor(build(p.child(), txn), p.condition());
            case PhysicalProjection p -> new ProjectionExecutor(build(p.child(), txn), p.exprs());
            case PhysicalSort p -> new SortExecutor(build(p.child(), txn), p.sortItems());
            // ... etc
        };
    }
}
```

### 9.4 关键类

```
x-db-executor/
└── src/main/java/io/github/xinfra/lab/xdb/executor/
    ├── Executor.java                  # 迭代器接口
    ├── ExecutorBuilder.java           # PhysicalPlan → Executor
    ├── scan/
    │   ├── TableScanExecutor.java
    │   ├── IndexScanExecutor.java
    │   ├── IndexLookupExecutor.java
    │   └── PointGetExecutor.java
    ├── rel/
    │   ├── SelectionExecutor.java
    │   ├── ProjectionExecutor.java
    │   ├── SortExecutor.java
    │   ├── LimitExecutor.java
    │   ├── TopNExecutor.java
    │   ├── HashJoinExecutor.java
    │   ├── NestedLoopJoinExecutor.java
    │   ├── HashAggExecutor.java
    │   └── StreamAggExecutor.java
    ├── dml/
    │   ├── InsertExecutor.java
    │   ├── UpdateExecutor.java
    │   └── DeleteExecutor.java
    └── util/
        ├── DualExecutor.java
        ├── ExplainExecutor.java
        └── ShowExecutor.java
```

---

## 10. Session 管理与分布式事务 (x-db-session)

### 10.1 Session 模型

```java
class Session implements AutoCloseable {
    private final long sessionId;
    private final TxnClient txnClient;        // 共享，所有 session 复用
    private final InfoSchemaCache schemaCache;

    // 会话状态
    private String currentDatabase;
    private Transaction currentTxn;           // 当前事务（null = auto-commit 模式）
    private boolean autoCommit = true;
    private boolean pessimistic = true;       // 默认悲观事务（与 MySQL 一致）
    private Map<String, String> sessionVars;  // SET 变量

    // 执行入口
    ResultSet execute(String sql);

    // 事务控制
    void begin(boolean pessimistic);
    void commit();
    void rollback();
}
```

### 10.2 语句执行流水线

```java
ResultSet execute(String sql) {
    // 1. Parse
    Statement stmt = SQLParser.parse(sql);

    // 2. 特殊语句快速路径
    if (stmt instanceof UseStmt use) { currentDatabase = use.dbName(); return OK; }
    if (stmt instanceof BeginStmt) { begin(pessimistic); return OK; }
    if (stmt instanceof CommitStmt) { commit(); return OK; }
    if (stmt instanceof RollbackStmt) { rollback(); return OK; }
    if (stmt instanceof SetStmt set) { handleSet(set); return OK; }

    // 3. 事务管理
    boolean autoTxn = (currentTxn == null);
    if (autoTxn) {
        currentTxn = pessimistic ? txnClient.beginPessimistic() : txnClient.begin();
    }

    try {
        // 4. 获取当前 schema 快照
        InfoSchema is = schemaCache.latest();

        // 5. Analyze → Plan → Optimize → Build → Execute
        LogicalPlan logical = Analyzer.analyze(stmt, is, currentDatabase);
        LogicalPlan optimized = LogicalOptimizer.optimize(logical);
        PhysicalPlan physical = Planner.physicalOptimize(optimized);
        Executor executor = ExecutorBuilder.build(physical, currentTxn);

        ResultSet rs = drainExecutor(executor);

        // 6. Auto-commit
        if (autoTxn) {
            currentTxn.commit();
            currentTxn = null;
        }
        return rs;
    } catch (Exception e) {
        if (autoTxn && currentTxn != null) {
            currentTxn.rollback();
            currentTxn = null;
        }
        throw e;
    }
}
```

### 10.3 ResultSet

```java
class ResultSet {
    enum Type { OK, ROWS }

    // OK result (INSERT/UPDATE/DELETE/DDL)
    long affectedRows;
    long insertId;

    // ROWS result (SELECT/SHOW/EXPLAIN)
    List<ColumnDef> columns;
    List<Row> rows;
}

record ColumnDef(String name, String table, DataType type, int length, int flags) {}
```

### 10.4 关键类

```
x-db-session/
└── src/main/java/io/github/xinfra/lab/xdb/session/
    ├── Session.java              # 会话实现
    ├── SessionManager.java       # 会话池管理
    ├── ResultSet.java
    ├── ColumnDef.java
    └── SessionVariable.java      # 会话变量定义
```

---

## 11. MySQL 协议服务器 (x-db-server)

### 11.1 MySQL 协议概述

MySQL 使用基于 TCP 的二进制协议，分为两个阶段：

1. **连接阶段**：握手 → 认证 → OK/ERR
2. **命令阶段**：客户端发送命令 → 服务器返回结果

#### 包格式
```
+-------------------+---------+
| 3 bytes: payload  | 1 byte: |
| length            | seq_id  |
+-------------------+---------+
| payload (up to 16MB - 1)    |
+---------+-------------------+
```

### 11.2 Netty Pipeline

```java
public class MySQLServerInitializer extends ChannelInitializer<SocketChannel> {
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
          .addLast("decoder",  new MySQLPacketDecoder())
          .addLast("encoder",  new MySQLPacketEncoder())
          .addLast("auth",     new AuthenticationHandler(sessionManager))
          .addLast("command",  new CommandHandler(sessionManager));
    }
}
```

### 11.3 握手流程

```
Server                              Client
  |                                    |
  |--- HandshakeV10 Packet ---------->|  (protocol_version, server_version,
  |                                    |   connection_id, salt, capability_flags,
  |                                    |   charset, status_flags, auth_plugin)
  |                                    |
  |<-- HandshakeResponse41 ---------- |  (capability_flags, max_packet_size,
  |                                    |   charset, username, auth_response,
  |                                    |   database, auth_plugin)
  |                                    |
  |--- OK Packet ------------------->|  (认证成功)
  | or ERR Packet ------------------>|  (认证失败)
```

### 11.4 命令处理

| 命令 | 值 | 处理 |
|------|-----|------|
| COM_QUIT | 0x01 | 关闭连接 |
| COM_INIT_DB | 0x02 | `USE database` |
| COM_QUERY | 0x03 | 执行 SQL，返回结果集或 OK/ERR |
| COM_FIELD_LIST | 0x04 | 返回表的列信息 |
| COM_PING | 0x0E | 返回 OK |
| COM_STMT_PREPARE | 0x16 | 预编译语句（Phase 2） |
| COM_STMT_EXECUTE | 0x17 | 执行预编译语句（Phase 2） |
| COM_STMT_CLOSE | 0x19 | 关闭预编译语句（Phase 2） |

### 11.5 结果集编码

**SELECT 返回 ResultSet：**
```
1. Column Count Packet:     length-encoded integer
2. Column Definition ×N:    catalog, schema, table, name, charset,
                            column_length, column_type, flags, decimals
3. EOF Packet (deprecated in 4.1+ but some clients expect it)
4. Row Data ×M:             length-encoded string per column (NULL = 0xFB)
5. EOF / OK Packet:         final marker
```

**INSERT/UPDATE/DELETE 返回 OK：**
```
OK Packet: 0x00, affected_rows, last_insert_id, status_flags, warnings
```

**错误返回 ERR：**
```
ERR Packet: 0xFF, error_code, '#', sql_state(5), error_message
```

### 11.6 服务器启动

```java
class XDBServer {
    private final XDBConfig config;
    private final TxnClient txnClient;
    private final InfoSchemaCache schemaCache;
    private final DDLWorker ddlWorker;
    private final SessionManager sessionManager;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    void start() {
        // 1. 初始化 x-kv client
        txnClient = TxnClient.create(config.kvConfig());

        // 2. 初始化 schema cache
        schemaCache = new InfoSchemaCache(txnClient);
        schemaCache.startAutoRefresh();

        // 3. 启动 DDL worker
        ddlWorker = new DDLWorker(txnClient, schemaCache);
        ddlWorker.start();

        // 4. 启动 Netty MySQL 服务器
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new MySQLServerInitializer(sessionManager))
            .bind(config.port())   // default 4000
            .sync();
    }

    void shutdown() {
        ddlWorker.stop();
        schemaCache.stop();
        txnClient.close();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }
}
```

### 11.7 配置

```yaml
# x-db-server.yaml
server:
  port: 4000
  max-connections: 1000

kv:
  pd-endpoints:
    - "127.0.0.1:2379"
    - "127.0.0.1:2381"
    - "127.0.0.1:2383"

ddl:
  lease-duration-ms: 10000
  schema-reload-interval-ms: 1000

auth:
  users:
    - username: root
      password: ""    # 默认空密码
```

### 11.8 关键类

```
x-db-server/
└── src/main/java/io/github/xinfra/lab/xdb/server/
    ├── XDBServer.java                # 服务器主类
    ├── XDBConfig.java                # 配置
    ├── protocol/
    │   ├── MySQLPacketDecoder.java   # 包解码器
    │   ├── MySQLPacketEncoder.java   # 包编码器
    │   ├── MySQLPacket.java          # 包基类
    │   ├── Capabilities.java         # MySQL 能力标志
    │   ├── ServerStatus.java         # 服务器状态标志
    │   ├── CharsetUtil.java          # 字符集
    │   ├── handshake/
    │   │   ├── HandshakeV10Packet.java
    │   │   ├── HandshakeResponse41Packet.java
    │   │   └── AuthenticationHandler.java
    │   ├── command/
    │   │   ├── CommandHandler.java
    │   │   ├── CommandType.java
    │   │   └── QueryHandler.java
    │   └── response/
    │       ├── OkPacket.java
    │       ├── ErrPacket.java
    │       ├── EofPacket.java
    │       ├── ColumnDefinitionPacket.java
    │       └── ResultSetWriter.java
    └── auth/
        ├── AuthProvider.java
        └── NativePasswordAuth.java   # mysql_native_password
```

---

## 12. 实现路线图

### Phase 1: 基础层（第 1-2 周）

| 步骤 | 模块 | 内容 | 验证 |
|------|------|------|------|
| 1 | x-db-common | 字节编码、Key 构建、错误体系 | 单元测试 |
| 2 | x-db-expression | 类型系统、Datum、表达式求值 | 单元测试 |
| 3 | x-db-table | 表编码器（行/索引 KV 编解码） | round-trip 测试 |

### Phase 2: SQL 解析（第 3 周）

| 步骤 | 模块 | 内容 | 验证 |
|------|------|------|------|
| 4 | x-db-parser | ANTLR4 MySQL 语法 + AST | SQL 字符串解析测试 |

### Phase 3: Schema 管理（第 4 周）

| 步骤 | 模块 | 内容 | 验证 |
|------|------|------|------|
| 5 | x-db-meta | Schema 模型、InfoSchema、Meta KV 存储 | 单元测试 |
| 6 | x-db-ddl | F1 Schema 变更状态机 | 单元 + 集成测试 |

### Phase 4: 查询引擎（第 5-6 周）

| 步骤 | 模块 | 内容 | 验证 |
|------|------|------|------|
| 7 | x-db-planner | 逻辑/物理计划、优化器 | 计划验证测试 |
| 8 | x-db-executor | 所有 Executor 实现 | Mock 数据测试 |

### Phase 5: 端到端（第 7-8 周）

| 步骤 | 模块 | 内容 | 验证 |
|------|------|------|------|
| 9 | x-db-session | Session、语句流水线、事务管理 | 集成测试 |
| 10 | x-db-server | MySQL 协议、Netty 服务器 | mysql CLI 连接测试 |
| 11 | x-db-test | 完整集成测试套件 | E2E: mysql → x-db → x-kv |

---

## 13. 关键依赖

```xml
<!-- x-kv 客户端 -->
<dependency>
    <groupId>io.github.x-infra-lab</groupId>
    <artifactId>x-kv-client</artifactId>
    <version>0.2.0</version>
</dependency>
<dependency>
    <groupId>io.github.x-infra-lab</groupId>
    <artifactId>x-kv-proto</artifactId>
    <version>0.2.0</version>
</dependency>

<!-- ANTLR4 SQL 解析器 -->
<dependency>
    <groupId>org.antlr</groupId>
    <artifactId>antlr4-runtime</artifactId>
    <version>4.13.1</version>
</dependency>

<!-- Netty MySQL 协议服务器 -->
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-all</artifactId>
    <version>4.1.108.Final</version>
</dependency>

<!-- JSON 序列化（Schema 元数据） -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.0</version>
</dependency>

<!-- 日志 -->
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
</dependency>
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
</dependency>

<!-- 测试 -->
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <scope>test</scope>
</dependency>
```

---

## 14. 端到端验证场景

```sql
-- 连接
mysql -h 127.0.0.1 -P 4000 -u root

-- DDL
CREATE DATABASE test;
USE test;

CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE,
    age INT DEFAULT 0,
    created_at DATETIME DEFAULT NOW()
);

CREATE TABLE orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    amount DECIMAL(10, 2),
    status VARCHAR(20) DEFAULT 'pending',
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
);

-- DML
INSERT INTO users (name, email, age) VALUES
    ('Alice', 'alice@example.com', 30),
    ('Bob', 'bob@example.com', 25),
    ('Charlie', 'charlie@example.com', 35);

INSERT INTO orders (user_id, amount, status) VALUES
    (1, 99.99, 'completed'),
    (1, 49.50, 'pending'),
    (2, 200.00, 'completed');

-- 查询
SELECT * FROM users WHERE age > 20 ORDER BY name;
SELECT u.name, COUNT(o.id) as order_count, SUM(o.amount) as total
FROM users u JOIN orders o ON u.id = o.user_id
GROUP BY u.name HAVING total > 50
ORDER BY total DESC;

-- 更新/删除
UPDATE users SET age = 31 WHERE name = 'Alice';
DELETE FROM orders WHERE status = 'pending';

-- 事务
BEGIN;
INSERT INTO users (name, email, age) VALUES ('Dave', 'dave@example.com', 28);
INSERT INTO orders (user_id, amount) VALUES (LAST_INSERT_ID(), 150.00);
COMMIT;

-- Schema 变更
ALTER TABLE users ADD COLUMN phone VARCHAR(20);
ALTER TABLE orders ADD INDEX idx_amount (amount);

-- 管理命令
SHOW DATABASES;
SHOW TABLES;
SHOW COLUMNS FROM users;
SHOW CREATE TABLE users;
EXPLAIN SELECT * FROM users WHERE age > 25;
```
