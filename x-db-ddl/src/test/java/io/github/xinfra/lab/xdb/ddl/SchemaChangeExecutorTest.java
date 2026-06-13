package io.github.xinfra.lab.xdb.ddl;

import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.meta.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaChangeExecutorTest {

    private InMemoryMetaStore metaStore;
    private SchemaChangeExecutor executor;

    @BeforeEach
    void setUp() {
        metaStore = new InMemoryMetaStore();
        executor = new SchemaChangeExecutor(metaStore);
    }

    // -----------------------------------------------------------------------
    // State machine: nextState()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("F1 state machine transitions")
    class StateMachine {

        @Test
        @DisplayName("CREATE_DATABASE/TABLE/TRUNCATE are single-step (always null)")
        void singleStepOps() {
            for (DDLType t : List.of(DDLType.CREATE_DATABASE, DDLType.CREATE_TABLE, DDLType.TRUNCATE_TABLE)) {
                for (SchemaState s : SchemaState.values()) {
                    assertThat(executor.nextState(t, s))
                            .as("%s from %s", t, s)
                            .isNull();
                }
            }
        }

        @Test
        @DisplayName("DROP_DATABASE is single-step")
        void dropDatabaseSingleStep() {
            assertThat(executor.nextState(DDLType.DROP_DATABASE, SchemaState.PUBLIC)).isNull();
        }

        @Test
        @DisplayName("DROP_TABLE: PUBLIC → WRITE_ONLY → DELETE_ONLY → null")
        void dropTableTransitions() {
            assertThat(executor.nextState(DDLType.DROP_TABLE, SchemaState.PUBLIC))
                    .isEqualTo(SchemaState.WRITE_ONLY);
            assertThat(executor.nextState(DDLType.DROP_TABLE, SchemaState.WRITE_ONLY))
                    .isEqualTo(SchemaState.DELETE_ONLY);
            assertThat(executor.nextState(DDLType.DROP_TABLE, SchemaState.DELETE_ONLY))
                    .isNull();
        }

        @Test
        @DisplayName("ADD_COLUMN: ABSENT → DELETE_ONLY → WRITE_ONLY → WRITE_REORGANIZATION → null")
        void addColumnTransitions() {
            assertThat(executor.nextState(DDLType.ADD_COLUMN, SchemaState.ABSENT))
                    .isEqualTo(SchemaState.DELETE_ONLY);
            assertThat(executor.nextState(DDLType.ADD_COLUMN, SchemaState.DELETE_ONLY))
                    .isEqualTo(SchemaState.WRITE_ONLY);
            assertThat(executor.nextState(DDLType.ADD_COLUMN, SchemaState.WRITE_ONLY))
                    .isEqualTo(SchemaState.WRITE_REORGANIZATION);
            assertThat(executor.nextState(DDLType.ADD_COLUMN, SchemaState.WRITE_REORGANIZATION))
                    .isNull();
        }

        @Test
        @DisplayName("DROP_COLUMN: PUBLIC → WRITE_ONLY → DELETE_ONLY → null")
        void dropColumnTransitions() {
            assertThat(executor.nextState(DDLType.DROP_COLUMN, SchemaState.PUBLIC))
                    .isEqualTo(SchemaState.WRITE_ONLY);
            assertThat(executor.nextState(DDLType.DROP_COLUMN, SchemaState.WRITE_ONLY))
                    .isEqualTo(SchemaState.DELETE_ONLY);
            assertThat(executor.nextState(DDLType.DROP_COLUMN, SchemaState.DELETE_ONLY))
                    .isNull();
        }

        @Test
        @DisplayName("ADD_INDEX follows same path as ADD_COLUMN")
        void addIndexTransitions() {
            assertThat(executor.nextState(DDLType.ADD_INDEX, SchemaState.ABSENT))
                    .isEqualTo(SchemaState.DELETE_ONLY);
            assertThat(executor.nextState(DDLType.ADD_INDEX, SchemaState.DELETE_ONLY))
                    .isEqualTo(SchemaState.WRITE_ONLY);
            assertThat(executor.nextState(DDLType.ADD_INDEX, SchemaState.WRITE_ONLY))
                    .isEqualTo(SchemaState.WRITE_REORGANIZATION);
            assertThat(executor.nextState(DDLType.ADD_INDEX, SchemaState.WRITE_REORGANIZATION))
                    .isNull();
        }

        @Test
        @DisplayName("DROP_INDEX follows same path as DROP_COLUMN")
        void dropIndexTransitions() {
            assertThat(executor.nextState(DDLType.DROP_INDEX, SchemaState.PUBLIC))
                    .isEqualTo(SchemaState.WRITE_ONLY);
            assertThat(executor.nextState(DDLType.DROP_INDEX, SchemaState.WRITE_ONLY))
                    .isEqualTo(SchemaState.DELETE_ONLY);
            assertThat(executor.nextState(DDLType.DROP_INDEX, SchemaState.DELETE_ONLY))
                    .isNull();
        }
    }

    // -----------------------------------------------------------------------
    // CREATE DATABASE
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("CREATE DATABASE execution")
    class CreateDatabase {

        @Test
        void createsDatabase() {
            DDLJob job = new DDLJob();
            job.setType(DDLType.CREATE_DATABASE);
            job.setDbId(1);
            job.setDbName("testdb");
            job.setSchemaState(SchemaState.ABSENT);

            executor.execute(job);

            assertThat(job.getSchemaState()).isNull();
            assertThat(job.getVersion()).isEqualTo(1);

            DatabaseInfo db = metaStore.getDatabase(1);
            assertThat(db).isNotNull();
            assertThat(db.getName()).isEqualTo("testdb");
            assertThat(db.getState()).isEqualTo(SchemaState.PUBLIC);
        }

        @Test
        void advancesSchemaVersion() {
            metaStore.setSchemaVersion(5);
            DDLJob job = new DDLJob();
            job.setType(DDLType.CREATE_DATABASE);
            job.setDbId(1);
            job.setDbName("db1");
            job.setSchemaState(SchemaState.ABSENT);

            executor.execute(job);

            assertThat(metaStore.getSchemaVersion()).isEqualTo(6);
            assertThat(job.getVersion()).isEqualTo(6);
        }
    }

    // -----------------------------------------------------------------------
    // DROP DATABASE
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DROP DATABASE execution")
    class DropDatabase {

        @Test
        void dropsDatabase() {
            DatabaseInfo db = new DatabaseInfo();
            db.setId(1);
            db.setName("testdb");
            db.setState(SchemaState.PUBLIC);
            metaStore.createDatabase(db);

            DDLJob job = new DDLJob();
            job.setType(DDLType.DROP_DATABASE);
            job.setDbId(1);
            job.setDbName("testdb");
            job.setSchemaState(SchemaState.PUBLIC);

            executor.execute(job);

            assertThat(job.getSchemaState()).isNull();
            assertThat(metaStore.getDatabase(1)).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // CREATE TABLE
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("CREATE TABLE execution")
    class CreateTable {

        @BeforeEach
        void createDb() {
            DatabaseInfo db = new DatabaseInfo();
            db.setId(1);
            db.setName("testdb");
            db.setState(SchemaState.PUBLIC);
            metaStore.createDatabase(db);
        }

        @Test
        void createsTableWithColumnsAndIndices() {
            TableInfo tableInfo = buildSimpleTable(100, "users");
            DDLJob job = new DDLJob();
            job.setType(DDLType.CREATE_TABLE);
            job.setDbId(1);
            job.setTableId(100);
            job.setTableInfo(tableInfo);
            job.setSchemaState(SchemaState.ABSENT);

            executor.execute(job);

            assertThat(job.getSchemaState()).isNull();
            TableInfo stored = metaStore.getTable(1, 100);
            assertThat(stored).isNotNull();
            assertThat(stored.getState()).isEqualTo(SchemaState.PUBLIC);
            assertThat(stored.getColumns()).allMatch(c -> c.getState() == SchemaState.PUBLIC);
            assertThat(stored.getIndices()).allMatch(i -> i.getState() == SchemaState.PUBLIC);
        }
    }

    // -----------------------------------------------------------------------
    // DROP TABLE (multi-step)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DROP TABLE execution (multi-step)")
    class DropTable {

        @BeforeEach
        void createDbAndTable() {
            DatabaseInfo db = new DatabaseInfo();
            db.setId(1);
            db.setName("testdb");
            db.setState(SchemaState.PUBLIC);
            metaStore.createDatabase(db);

            TableInfo table = buildSimpleTable(100, "users");
            table.setState(SchemaState.PUBLIC);
            table.getColumns().forEach(c -> c.setState(SchemaState.PUBLIC));
            table.getIndices().forEach(i -> i.setState(SchemaState.PUBLIC));
            metaStore.createTable(1, table);
        }

        @Test
        void fullDropLifecycle() {
            DDLJob job = new DDLJob();
            job.setType(DDLType.DROP_TABLE);
            job.setDbId(1);
            job.setTableId(100);
            job.setSchemaState(SchemaState.PUBLIC);

            // Step 1: PUBLIC → WRITE_ONLY
            executor.execute(job);
            assertThat(job.getSchemaState()).isEqualTo(SchemaState.WRITE_ONLY);
            assertThat(metaStore.getTable(1, 100).getState()).isEqualTo(SchemaState.WRITE_ONLY);

            // Step 2: WRITE_ONLY → DELETE_ONLY
            executor.execute(job);
            assertThat(job.getSchemaState()).isEqualTo(SchemaState.DELETE_ONLY);
            assertThat(metaStore.getTable(1, 100).getState()).isEqualTo(SchemaState.DELETE_ONLY);

            // Step 3: DELETE_ONLY → removed
            executor.execute(job);
            assertThat(job.getSchemaState()).isNull();
            assertThat(metaStore.getTable(1, 100)).isNull();
        }

        @Test
        void eachStepAdvancesSchemaVersion() {
            DDLJob job = new DDLJob();
            job.setType(DDLType.DROP_TABLE);
            job.setDbId(1);
            job.setTableId(100);
            job.setSchemaState(SchemaState.PUBLIC);

            executor.execute(job);
            assertThat(job.getVersion()).isEqualTo(1);

            executor.execute(job);
            assertThat(job.getVersion()).isEqualTo(2);

            executor.execute(job);
            assertThat(job.getVersion()).isEqualTo(3);
        }
    }

    // -----------------------------------------------------------------------
    // ADD COLUMN (4-step)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ADD COLUMN execution (4-step)")
    class AddColumn {

        @BeforeEach
        void createDbAndTable() {
            DatabaseInfo db = new DatabaseInfo();
            db.setId(1);
            db.setName("testdb");
            db.setState(SchemaState.PUBLIC);
            metaStore.createDatabase(db);

            TableInfo table = buildSimpleTable(100, "users");
            table.setState(SchemaState.PUBLIC);
            table.getColumns().forEach(c -> c.setState(SchemaState.PUBLIC));
            table.getIndices().forEach(i -> i.setState(SchemaState.PUBLIC));
            metaStore.createTable(1, table);
        }

        @Test
        void fullAddColumnLifecycle() {
            ColumnInfo newCol = new ColumnInfo();
            newCol.setId(10);
            newCol.setName("email");
            newCol.setType(DataType.VARCHAR);

            DDLJob job = new DDLJob();
            job.setType(DDLType.ADD_COLUMN);
            job.setDbId(1);
            job.setTableId(100);
            job.setColumnInfo(newCol);
            job.setSchemaState(SchemaState.ABSENT);

            int initialColCount = metaStore.getTable(1, 100).getColumns().size();

            // Step 1: ABSENT → DELETE_ONLY (column added)
            executor.execute(job);
            assertThat(job.getSchemaState()).isEqualTo(SchemaState.DELETE_ONLY);
            TableInfo t = metaStore.getTable(1, 100);
            assertThat(t.getColumns()).hasSize(initialColCount + 1);
            assertThat(t.getColumn("email").getState()).isEqualTo(SchemaState.DELETE_ONLY);

            // Step 2: DELETE_ONLY → WRITE_ONLY
            executor.execute(job);
            assertThat(job.getSchemaState()).isEqualTo(SchemaState.WRITE_ONLY);
            assertThat(metaStore.getTable(1, 100).getColumn("email").getState())
                    .isEqualTo(SchemaState.WRITE_ONLY);

            // Step 3: WRITE_ONLY → WRITE_REORGANIZATION
            executor.execute(job);
            assertThat(job.getSchemaState()).isEqualTo(SchemaState.WRITE_REORGANIZATION);
            assertThat(metaStore.getTable(1, 100).getColumn("email").getState())
                    .isEqualTo(SchemaState.WRITE_REORGANIZATION);

            // Step 4: WRITE_REORGANIZATION → PUBLIC (done)
            executor.execute(job);
            assertThat(job.getSchemaState()).isNull();
            assertThat(metaStore.getTable(1, 100).getColumn("email").getState())
                    .isEqualTo(SchemaState.PUBLIC);
        }
    }

    // -----------------------------------------------------------------------
    // DROP COLUMN (3-step)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DROP COLUMN execution (3-step)")
    class DropColumn {

        @BeforeEach
        void createDbAndTable() {
            DatabaseInfo db = new DatabaseInfo();
            db.setId(1);
            db.setName("testdb");
            db.setState(SchemaState.PUBLIC);
            metaStore.createDatabase(db);

            TableInfo table = buildSimpleTable(100, "users");
            table.setState(SchemaState.PUBLIC);
            table.getColumns().forEach(c -> c.setState(SchemaState.PUBLIC));
            table.getIndices().forEach(i -> i.setState(SchemaState.PUBLIC));
            metaStore.createTable(1, table);
        }

        @Test
        void fullDropColumnLifecycle() {
            ColumnInfo colToDrop = new ColumnInfo();
            colToDrop.setName("name");

            DDLJob job = new DDLJob();
            job.setType(DDLType.DROP_COLUMN);
            job.setDbId(1);
            job.setTableId(100);
            job.setColumnInfo(colToDrop);
            job.setSchemaState(SchemaState.PUBLIC);

            int initialColCount = metaStore.getTable(1, 100).getColumns().size();

            // Step 1: PUBLIC → WRITE_ONLY
            executor.execute(job);
            assertThat(job.getSchemaState()).isEqualTo(SchemaState.WRITE_ONLY);
            assertThat(metaStore.getTable(1, 100).getColumn("name").getState())
                    .isEqualTo(SchemaState.WRITE_ONLY);

            // Step 2: WRITE_ONLY → DELETE_ONLY
            executor.execute(job);
            assertThat(job.getSchemaState()).isEqualTo(SchemaState.DELETE_ONLY);
            assertThat(metaStore.getTable(1, 100).getColumn("name").getState())
                    .isEqualTo(SchemaState.DELETE_ONLY);

            // Step 3: DELETE_ONLY → removed
            executor.execute(job);
            assertThat(job.getSchemaState()).isNull();
            assertThat(metaStore.getTable(1, 100).getColumns()).hasSize(initialColCount - 1);
            assertThat(metaStore.getTable(1, 100).getColumn("name")).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // ADD INDEX (4-step)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ADD INDEX execution (4-step)")
    class AddIndex {

        @BeforeEach
        void createDbAndTable() {
            DatabaseInfo db = new DatabaseInfo();
            db.setId(1);
            db.setName("testdb");
            db.setState(SchemaState.PUBLIC);
            metaStore.createDatabase(db);

            TableInfo table = buildSimpleTable(100, "users");
            table.setState(SchemaState.PUBLIC);
            table.getColumns().forEach(c -> c.setState(SchemaState.PUBLIC));
            table.getIndices().forEach(i -> i.setState(SchemaState.PUBLIC));
            metaStore.createTable(1, table);
        }

        @Test
        void fullAddIndexLifecycle() {
            IndexInfo newIdx = new IndexInfo();
            newIdx.setId(20);
            newIdx.setName("idx_name");
            newIdx.setTableId(100);
            newIdx.setColumns(List.of(new IndexColumn("name", 2, 0)));

            DDLJob job = new DDLJob();
            job.setType(DDLType.ADD_INDEX);
            job.setDbId(1);
            job.setTableId(100);
            job.setIndexInfo(newIdx);
            job.setSchemaState(SchemaState.ABSENT);

            int initialIdxCount = metaStore.getTable(1, 100).getIndices().size();

            // Step 1: ABSENT → DELETE_ONLY
            executor.execute(job);
            assertThat(job.getSchemaState()).isEqualTo(SchemaState.DELETE_ONLY);
            assertThat(metaStore.getTable(1, 100).getIndices()).hasSize(initialIdxCount + 1);
            assertThat(metaStore.getTable(1, 100).getIndex("idx_name").getState())
                    .isEqualTo(SchemaState.DELETE_ONLY);

            // Step 2: DELETE_ONLY → WRITE_ONLY
            executor.execute(job);
            assertThat(job.getSchemaState()).isEqualTo(SchemaState.WRITE_ONLY);

            // Step 3: WRITE_ONLY → WRITE_REORGANIZATION
            executor.execute(job);
            assertThat(job.getSchemaState()).isEqualTo(SchemaState.WRITE_REORGANIZATION);

            // Step 4: WRITE_REORGANIZATION → PUBLIC (done)
            executor.execute(job);
            assertThat(job.getSchemaState()).isNull();
            assertThat(metaStore.getTable(1, 100).getIndex("idx_name").getState())
                    .isEqualTo(SchemaState.PUBLIC);
        }
    }

    // -----------------------------------------------------------------------
    // DROP INDEX (3-step)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DROP INDEX execution (3-step)")
    class DropIndex {

        @BeforeEach
        void createDbAndTable() {
            DatabaseInfo db = new DatabaseInfo();
            db.setId(1);
            db.setName("testdb");
            db.setState(SchemaState.PUBLIC);
            metaStore.createDatabase(db);

            TableInfo table = buildSimpleTable(100, "users");
            table.setState(SchemaState.PUBLIC);
            table.getColumns().forEach(c -> c.setState(SchemaState.PUBLIC));
            table.getIndices().forEach(i -> i.setState(SchemaState.PUBLIC));

            IndexInfo extraIdx = new IndexInfo();
            extraIdx.setId(20);
            extraIdx.setName("idx_name");
            extraIdx.setTableId(100);
            extraIdx.setState(SchemaState.PUBLIC);
            extraIdx.setColumns(List.of(new IndexColumn("name", 2, 0)));
            table.getIndices().add(extraIdx);

            metaStore.createTable(1, table);
        }

        @Test
        void fullDropIndexLifecycle() {
            IndexInfo idxToDrop = new IndexInfo();
            idxToDrop.setName("idx_name");

            DDLJob job = new DDLJob();
            job.setType(DDLType.DROP_INDEX);
            job.setDbId(1);
            job.setTableId(100);
            job.setIndexInfo(idxToDrop);
            job.setSchemaState(SchemaState.PUBLIC);

            int initialIdxCount = metaStore.getTable(1, 100).getIndices().size();

            // Step 1: PUBLIC → WRITE_ONLY
            executor.execute(job);
            assertThat(job.getSchemaState()).isEqualTo(SchemaState.WRITE_ONLY);

            // Step 2: WRITE_ONLY → DELETE_ONLY
            executor.execute(job);
            assertThat(job.getSchemaState()).isEqualTo(SchemaState.DELETE_ONLY);

            // Step 3: DELETE_ONLY → removed
            executor.execute(job);
            assertThat(job.getSchemaState()).isNull();
            assertThat(metaStore.getTable(1, 100).getIndices()).hasSize(initialIdxCount - 1);
            assertThat(metaStore.getTable(1, 100).getIndex("idx_name")).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // TRUNCATE TABLE
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("TRUNCATE TABLE execution")
    class TruncateTable {

        @Test
        void truncatesTable() {
            DatabaseInfo db = new DatabaseInfo();
            db.setId(1);
            db.setName("testdb");
            db.setState(SchemaState.PUBLIC);
            metaStore.createDatabase(db);

            TableInfo table = buildSimpleTable(100, "users");
            table.setState(SchemaState.PUBLIC);
            metaStore.createTable(1, table);

            DDLJob job = new DDLJob();
            job.setType(DDLType.TRUNCATE_TABLE);
            job.setDbId(1);
            job.setTableId(100);
            job.setSchemaState(SchemaState.PUBLIC);

            executor.execute(job);

            assertThat(job.getSchemaState()).isNull();
            assertThat(job.getVersion()).isGreaterThan(0);
        }
    }

    // -----------------------------------------------------------------------
    // Error cases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        void dropTableOnMissingTableThrows() {
            DDLJob job = new DDLJob();
            job.setType(DDLType.DROP_TABLE);
            job.setDbId(1);
            job.setTableId(999);
            job.setSchemaState(SchemaState.PUBLIC);

            assertThatThrownBy(() -> executor.execute(job))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Table not found");
        }

        @Test
        void addColumnOnMissingTableThrows() {
            ColumnInfo col = new ColumnInfo();
            col.setName("x");
            DDLJob job = new DDLJob();
            job.setType(DDLType.ADD_COLUMN);
            job.setDbId(1);
            job.setTableId(999);
            job.setColumnInfo(col);
            job.setSchemaState(SchemaState.ABSENT);

            assertThatThrownBy(() -> executor.execute(job))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Table not found");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private TableInfo buildSimpleTable(long id, String name) {
        ColumnInfo idCol = new ColumnInfo();
        idCol.setId(1);
        idCol.setName("id");
        idCol.setType(DataType.BIGINT);
        idCol.setOffset(0);

        ColumnInfo nameCol = new ColumnInfo();
        nameCol.setId(2);
        nameCol.setName("name");
        nameCol.setType(DataType.VARCHAR);
        nameCol.setOffset(1);

        IndexInfo pk = new IndexInfo();
        pk.setId(1);
        pk.setName("PRIMARY");
        pk.setTableId(id);
        pk.setPrimary(true);
        pk.setUnique(true);
        pk.setColumns(List.of(new IndexColumn("id", 1, 0)));

        TableInfo table = new TableInfo();
        table.setId(id);
        table.setName(name);
        table.setDbId(1);
        table.setColumns(new ArrayList<>(List.of(idCol, nameCol)));
        table.setIndices(new ArrayList<>(List.of(pk)));
        table.setMaxColumnId(2);
        table.setMaxIndexId(1);
        return table;
    }
}
