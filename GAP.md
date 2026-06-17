# x-db vs TiDB 架构对比与 GAP 分析

> 基于 x-db 当前实现（2026-06-18）与 TiDB 架构的系统性对比

## 1. 整体架构

| 维度 | TiDB | x-db | 一致性 |
|------|------|------|--------|
| 计算存储分离 | TiDB Server + TiKV + PD | x-db-server + x-kv + PD | 一致 |
| 协议层 | MySQL 兼容（端口 4000） | MySQL 兼容（端口 4000，Netty） | 一致 |
| 语言 | Go | Java | 不同（设计意图） |

## 2. 已对齐的核心能力

### 2.1 表编码 (Table Codec)

与 TiDB 完全兼容：

- Row Key：`t{tableID}_r{rowHandle}`
- Index Key：`t{tableID}_i{indexID}_{cols}`
- Memcomparable 编码（INT/FLOAT/BYTES/DECIMAL/DATETIME）
- 行值 Column-aware 编码（flag + colID + value）
- 元数据键 `m_` 前缀体系

### 2.2 F1 Online Schema Change

- 完整状态机：ABSENT → DELETE_ONLY → WRITE_ONLY → WRITE_REORG → PUBLIC
- DDL Owner 选举（KV CAS lease）
- DDL Job 队列 + DDLWorker 执行
- Index Backfill（WRITE_REORG 阶段全量回填）
- 支持：CREATE/DROP DATABASE/TABLE、ADD/DROP COLUMN、ADD/DROP INDEX、TRUNCATE TABLE

### 2.3 事务模型

- Percolator 2PC（via x-kv）
- 乐观事务 + 悲观事务（默认悲观）
- MVCC Snapshot Isolation
- TSO（PD TSOBatcher）
- 事务超时检测（默认 60s）
- AUTO_COMMIT

### 2.4 查询优化器

- 逻辑优化（RBO）：列裁剪、谓词下推、常量折叠
- 物理优化（CBO）：Histogram 统计信息 + 代价估算
- 访问路径选择：TableScan / IndexScan / IndexLookup
- Join 算法选择：HashJoin / NLJ（基于代价）
- 聚合算法选择：HashAgg / StreamAgg
- ANALYZE TABLE 统计信息收集

### 2.5 执行引擎

- Volcano 迭代器模型
- 完整算子：Selection / Projection / Sort / Limit / TopN / HashJoin / NLJ / HashAgg / StreamAgg / Union
- DML 算子：Insert / Update / Delete（含索引维护）
- 内存管理：MemoryTracker + ActionOnExceed
- 磁盘溢写：Sort / HashJoin / HashAgg 均支持 spill
- Coprocessor 下推：DistScan / DistAgg / DistTopN（并行多 Region）
- 两阶段聚合：Partial Agg → Final Merge

### 2.6 SQL 解析器

- ANTLR4 MySQL 语法
- DDL/DML/Utility 完整 AST
- 子查询：Scalar / IN / EXISTS / 派生表
- UNION / UNION ALL
- 表达式：LIKE / BETWEEN / IN / CASE WHEN / CAST

### 2.7 类型系统与内置函数

- 数据类型：整数（TINYINT~BIGINT）、浮点、DECIMAL、字符串、二进制、日期时间、BOOLEAN
- 聚合：COUNT / SUM / AVG / MIN / MAX / GROUP_CONCAT
- 标量函数：数学（ABS/CEIL/FLOOR/ROUND 等）、字符串（CONCAT/SUBSTRING 等）、日期（NOW/DATE_FORMAT 等）、控制流（IF/IFNULL/COALESCE 等）

### 2.8 MySQL 协议

- HandshakeV10 + mysql_native_password 认证
- COM_QUERY / COM_STMT_PREPARE / COM_STMT_EXECUTE / COM_PING / COM_INIT_DB / COM_QUIT / COM_RESET_CONNECTION
- 大包分片（>16MB）
- TLS 支持
- 系统变量查询（@@）

---

## 3. GAP 清单

### P0 — 性能关键路径

| GAP | 说明 | TiDB 中的作用 |
|-----|------|--------------|
| PointGet / BatchPointGet 优化 | 主键/唯一索引单行查询跳过优化器，直接 KV Get | TiDB 最重要的快速路径，OLTP 场景命中率极高 |
| MergeJoin | 有序输入的高效等值 Join | 大表 Join 且双方有序时性能远优于 HashJoin |
| IndexJoin (IndexLookupJoin) | 外表驱动 + 内表按索引查找 | 大小表 Join 的最优策略，避免全表扫描 |

### P1 — SQL 功能完整性

| GAP | 说明 | TiDB 中的作用 |
|-----|------|--------------|
| Window Function | ROW_NUMBER / RANK / DENSE_RANK / LAG / LEAD / NTILE 等 | 分析查询必备，排名/分页/同比环比 |
| CTE (WITH / WITH RECURSIVE) | 公共表表达式 | 复杂查询可读性；递归 CTE 用于层级/图遍历 |
| 分区表 | Range / Hash / List Partition + Partition Pruning | 大数据量管理、TTL 清理、查询加速 |
| INSERT ON DUPLICATE KEY UPDATE | 冲突时更新 | 最常用的 Upsert 语义 |
| REPLACE INTO | 冲突时删除旧行再插入 | 常用 Upsert 变体 |
| INSERT IGNORE | 忽略冲突行 | 批量导入去重 |

### P2 — 功能增强

| GAP | 说明 | TiDB 中的作用 |
|-----|------|--------------|
| 向量化执行 (Chunk-based) | 按 Chunk（1024 行）批量处理，利用 CPU cache | 大数据量扫描/聚合性能提升 2-10x |
| MODIFY / CHANGE COLUMN | 修改列类型、重命名列 | DDL 完整性，生产环境常用 |
| RENAME TABLE | 重命名表 | DDL 完整性 |
| JSON 类型 | JSON 存储 + JSON_EXTRACT / JSON_SET 等函数 | 现代应用半结构化数据存储 |
| ENUM / SET 类型 | 枚举和集合类型 | MySQL 兼容性 |
| Multi-table UPDATE / DELETE | 跨表更新/删除 | MySQL 兼容性 |
| TopN 下推优化规则 | Sort + Limit → TopN，下推到数据源 | 分页查询性能优化 |
| 外连接消除 | 无用 LEFT JOIN 自动消除 | 逻辑优化规则完整性 |
| 子查询去关联化 | 将关联子查询转为 Join | 避免逐行执行子查询的 O(n²) 性能 |

### P3 — 高级能力

| GAP | 说明 | TiDB 中的作用 |
|-----|------|--------------|
| Savepoint | 事务内部分回滚 | 复杂事务控制 |
| Cascades 优化器 | 基于规则的搜索框架，自底向上枚举计划 | 更复杂查询的全局最优计划 |
| Prepared Statement 真正实现 | 当前是字符串替换，非真正参数化执行 | 减少重复解析开销，防 SQL 注入 |
| COM_FIELD_LIST 完整实现 | 当前返回空 EOF | 部分 ORM/工具依赖此命令 |
| 系统变量完整支持 | 当前硬编码常用变量 | 生产环境调优需要 |
| 视图 (VIEW) | CREATE VIEW / 查询视图 | SQL 抽象层 |
| 触发器 (TRIGGER) | BEFORE/AFTER INSERT/UPDATE/DELETE | MySQL 兼容性 |
| 存储过程 / 函数 | CREATE PROCEDURE / FUNCTION | MySQL 兼容性 |
| GC (垃圾回收) | MVCC 旧版本清理 | 存储空间回收（x-kv 层可能已有） |
| Slow Query Log | 慢查询日志记录与分析 | 生产环境性能诊断 |

---

## 4. 总结

x-db 的核心架构（存储编码、事务模型、Schema 变更、分布式执行）与 TiDB **高度一致**，是一个架构完整的分布式 SQL 数据库。

**主要差距集中在三个方面：**

1. **执行引擎优化深度** — PointGet 快速路径、MergeJoin/IndexJoin、向量化执行
2. **SQL 功能丰富度** — Window Function、CTE、分区表、Upsert 语义
3. **DDL 完整性** — MODIFY COLUMN、RENAME TABLE 等生产常用操作
