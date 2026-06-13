package io.github.xinfra.lab.xdb.planner;

import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.meta.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Shared factory that creates an InfoSchema populated with test tables
 * for the planner unit tests.
 *
 * Database "testdb" (id=1) contains:
 *   - users   (id, name, age, email)
 *   - orders  (id, user_id, amount, status)
 *   - products(id, name, price, category)
 */
public final class TestSchemaFactory {
    private TestSchemaFactory() {}

    public static final String DB_NAME = "testdb";

    public static InfoSchema createInfoSchema() {
        DatabaseInfo db = new DatabaseInfo(1L, DB_NAME, "utf8mb4", "utf8mb4_general_ci", SchemaState.PUBLIC);

        TableInfo users = createUsersTable();
        TableInfo orders = createOrdersTable();
        TableInfo products = createProductsTable();

        return new InfoSchema(1L, Collections.singletonList(db),
                Arrays.asList(users, orders, products));
    }

    private static TableInfo createUsersTable() {
        List<ColumnInfo> columns = new ArrayList<>();
        columns.add(makeCol(1L, "id", DataType.BIGINT, 0));
        columns.add(makeCol(2L, "name", DataType.VARCHAR, 1));
        columns.add(makeCol(3L, "age", DataType.INT, 2));
        columns.add(makeCol(4L, "email", DataType.VARCHAR, 3));

        List<IndexInfo> indices = new ArrayList<>();
        indices.add(new IndexInfo(1L, "PRIMARY", 1L,
                Collections.singletonList(new IndexColumn("id", 1L, 0)),
                true, true, SchemaState.PUBLIC));
        indices.add(new IndexInfo(2L, "idx_name", 1L,
                Collections.singletonList(new IndexColumn("name", 2L, 0)),
                false, false, SchemaState.PUBLIC));

        return new TableInfo(1L, "users", 1L, "utf8mb4", "utf8mb4_general_ci",
                "", "InnoDB", columns, indices, 1L, SchemaState.PUBLIC, 4L, 2L);
    }

    private static TableInfo createOrdersTable() {
        List<ColumnInfo> columns = new ArrayList<>();
        columns.add(makeCol(1L, "id", DataType.BIGINT, 0));
        columns.add(makeCol(2L, "user_id", DataType.BIGINT, 1));
        columns.add(makeCol(3L, "amount", DataType.DOUBLE, 2));
        columns.add(makeCol(4L, "status", DataType.VARCHAR, 3));

        List<IndexInfo> indices = new ArrayList<>();
        indices.add(new IndexInfo(1L, "PRIMARY", 2L,
                Collections.singletonList(new IndexColumn("id", 1L, 0)),
                true, true, SchemaState.PUBLIC));

        return new TableInfo(2L, "orders", 1L, "utf8mb4", "utf8mb4_general_ci",
                "", "InnoDB", columns, indices, 1L, SchemaState.PUBLIC, 4L, 1L);
    }

    private static TableInfo createProductsTable() {
        List<ColumnInfo> columns = new ArrayList<>();
        columns.add(makeCol(1L, "id", DataType.BIGINT, 0));
        columns.add(makeCol(2L, "name", DataType.VARCHAR, 1));
        columns.add(makeCol(3L, "price", DataType.DOUBLE, 2));
        columns.add(makeCol(4L, "category", DataType.VARCHAR, 3));

        List<IndexInfo> indices = new ArrayList<>();
        indices.add(new IndexInfo(1L, "PRIMARY", 3L,
                Collections.singletonList(new IndexColumn("id", 1L, 0)),
                true, true, SchemaState.PUBLIC));

        return new TableInfo(3L, "products", 1L, "utf8mb4", "utf8mb4_general_ci",
                "", "InnoDB", columns, indices, 1L, SchemaState.PUBLIC, 4L, 1L);
    }

    private static ColumnInfo makeCol(long id, String name, DataType type, int offset) {
        return new ColumnInfo(id, name, type, 0, 0, true, false, false,
                null, null, offset, SchemaState.PUBLIC);
    }
}
