# x-db

A distributed SQL database built on [x-kv](https://github.com/x-infra-lab/x-kv). MySQL-compatible wire protocol, F1-style online schema change, and a volcano-model query engine.

## Architecture

```
                        ┌─────────────────────────────┐
                        │       MySQL Client           │
                        └──────────┬──────────────────┘
                                   │ MySQL Protocol
                        ┌──────────▼──────────────────┐
                        │       x-db-server            │
                        │  (Netty, auth, wire codec)   │
                        └──────────┬──────────────────┘
                                   │
                        ┌──────────▼──────────────────┐
                        │       x-db-session           │
                        │  (session, txn management)   │
                        └──┬───────────────────────┬──┘
                           │                       │
              ┌────────────▼──────┐     ┌──────────▼──────────┐
              │   x-db-executor   │     │     x-db-ddl        │
              │ (volcano engine)  │     │ (F1 schema change)  │
              └────────┬──────────┘     └──────────┬──────────┘
                       │                           │
              ┌────────▼──────────┐     ┌──────────▼──────────┐
              │   x-db-planner    │     │     x-db-meta       │
              │ (logical/physical)│     │  (schema metadata)  │
              └────────┬──────────┘     └─────────────────────┘
                       │
              ┌────────▼──────────┐
              │    x-db-parser    │
              │   (ANTLR4 SQL)    │
              └───────────────────┘
                       │
              ┌────────▼──────────┐
              │  x-db-expression  │      x-db-table (codec)
              │  (types, eval)    │      x-db-common (errors)
              └───────────────────┘
                       │
              ┌────────▼──────────┐
              │      x-kv         │
              │ (distributed KV)  │
              └───────────────────┘
```

## Modules

| Module | Description |
|--------|-------------|
| `x-db-common` | Shared error codes and exceptions |
| `x-db-expression` | Data types, `Datum`, `Row`, expression evaluation |
| `x-db-table` | Row codec (encode/decode rows to KV bytes) |
| `x-db-parser` | ANTLR4-based SQL parser |
| `x-db-meta` | Schema metadata (`DatabaseInfo`, `TableInfo`, `ColumnInfo`) |
| `x-db-ddl` | F1-style online schema change (5-state machine) |
| `x-db-planner` | Logical and physical query plans |
| `x-db-executor` | Volcano-model iterators (scan, filter, sort, hash-agg, etc.) |
| `x-db-session` | Session management, transaction lifecycle |
| `x-db-server` | Netty-based MySQL protocol server |
| `x-db-test` | End-to-end integration tests |

## SQL Features

- **DDL**: `CREATE DATABASE`, `DROP DATABASE`, `CREATE TABLE`, `DROP TABLE`, `SHOW TABLES`
- **DML**: `INSERT`, `SELECT`, `UPDATE`, `DELETE`
- **Expressions**: arithmetic, comparison, `AND`/`OR`/`NOT`, string literals, integers
- **Query**: `WHERE`, `ORDER BY` (ASC/DESC), `LIMIT`, `SELECT` without `FROM`
- **Types**: `BIGINT`, `INT`, `VARCHAR`, `BOOLEAN`

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+
- A running [x-kv](https://github.com/x-infra-lab/x-kv) cluster (PD + TiKV nodes)

### Build

```bash
mvn clean package -DskipTests
```

### Configure

The server reads configuration from `x-db.yml` (current directory, then classpath). Defaults:

```yaml
port: 4000
pdAddresses: "127.0.0.1:2379"
workerThreads: 0        # 0 = auto (2 * CPU cores)
maxConnections: 1000
```

CLI args override config: `java -jar x-db-server.jar [port] [pdAddresses]`

### Run

```bash
java --enable-preview -jar x-db-server/target/x-db-server-0.1.0-SNAPSHOT.jar
```

The server connects to x-kv, starts the DDL worker, and listens on the configured port.

### Connect

```bash
mysql -h 127.0.0.1 -P 4000 -u root
```

```sql
CREATE DATABASE mydb;
USE mydb;
CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(255), age INT);
INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30);
SELECT * FROM users WHERE age > 25;
SELECT COUNT(*), MIN(age), MAX(age) FROM users;
```

### Run Tests

```bash
mvn clean test
```

## Known Limitations

- No password authentication (accepts all connections)
- No TLS/SSL support
- No subqueries or `EXISTS`
- No `JOIN` in DML (`UPDATE`, `DELETE`)
- No `ALTER TABLE` (use DDL schema change API)
- Single-node query execution (no distributed query planning)

## License

[Apache License 2.0](LICENSE)
