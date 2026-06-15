package io.github.xinfra.lab.xdb.executor.dml;

import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.executor.ListExecutor;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.Constant;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.IndexColumn;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.table.TableCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpdateExecutorTest {

    private TreeMap<ByteArrayKey, byte[]> kvStore;
    private TransactionContext txnCtx;
    private TableInfo table;

    @BeforeEach
    void setUp() {
        kvStore = new TreeMap<>();
        txnCtx = new TransactionContext(
                (start, end, limit) -> List.of(),
                key -> {
                    ByteArrayKey k = new ByteArrayKey(key);
                    byte[] v = kvStore.get(k);
                    return v;
                },
                (key, value) -> kvStore.put(new ByteArrayKey(key), value),
                key -> kvStore.remove(new ByteArrayKey(key)),
                new EvalContext()
        );

        // Table: users(id BIGINT PK AUTO_INCREMENT, email VARCHAR, name VARCHAR)
        // Unique index on email
        ColumnInfo idCol = new ColumnInfo();
        idCol.setId(1);
        idCol.setName("id");
        idCol.setType(DataType.BIGINT);
        idCol.setOffset(0);
        idCol.setAutoIncrement(true);

        ColumnInfo emailCol = new ColumnInfo();
        emailCol.setId(2);
        emailCol.setName("email");
        emailCol.setType(DataType.VARCHAR);
        emailCol.setOffset(1);

        ColumnInfo nameCol = new ColumnInfo();
        nameCol.setId(3);
        nameCol.setName("name");
        nameCol.setType(DataType.VARCHAR);
        nameCol.setOffset(2);

        IndexInfo pk = new IndexInfo();
        pk.setId(1);
        pk.setName("PRIMARY");
        pk.setTableId(1);
        pk.setPrimary(true);
        pk.setUnique(true);
        pk.setColumns(List.of(new IndexColumn("id", 1, 0)));

        IndexInfo emailIdx = new IndexInfo();
        emailIdx.setId(2);
        emailIdx.setName("idx_email");
        emailIdx.setTableId(1);
        emailIdx.setPrimary(false);
        emailIdx.setUnique(true);
        emailIdx.setColumns(List.of(new IndexColumn("email", 2, 0)));

        table = new TableInfo();
        table.setId(1);
        table.setName("users");
        table.setColumns(new ArrayList<>(List.of(idCol, emailCol, nameCol)));
        table.setIndices(new ArrayList<>(List.of(pk, emailIdx)));
        table.setMaxColumnId(3);
        table.setMaxIndexId(2);
    }

    private void insertRow(long id, String email, String name) throws Exception {
        InsertExecutor insert = new InsertExecutor(
                txnCtx, table,
                table.getColumns(),
                List.of(List.of(
                        new Constant(Datum.of(id), DataType.BIGINT),
                        new Constant(Datum.of(email), DataType.VARCHAR),
                        new Constant(Datum.of(name), DataType.VARCHAR)
                ))
        );
        insert.open();
        insert.next();
        insert.close();
    }

    @Test
    @DisplayName("UPDATE to duplicate unique index value throws dupEntry")
    void updateToDuplicateUniqueValue() throws Exception {
        insertRow(1, "alice@test.com", "Alice");
        insertRow(2, "bob@test.com", "Bob");

        // Try to update Bob's email to Alice's — should fail
        ColumnInfo emailCol = table.getColumn("email");
        ListExecutor child = new ListExecutor(
                List.of(new Row(new Datum[]{Datum.of(2L), Datum.of("bob@test.com"), Datum.of("Bob")})),
                table.getColumns()
        );
        UpdateExecutor update = new UpdateExecutor(
                txnCtx, child, table,
                List.of(emailCol),
                List.of(new Constant(Datum.of("alice@test.com"), DataType.VARCHAR))
        );
        update.open();
        assertThatThrownBy(update::next)
                .isInstanceOf(XDBException.class)
                .hasMessageContaining("Duplicate entry")
                .hasMessageContaining("idx_email");
        update.close();
    }

    @Test
    @DisplayName("UPDATE to new unique value succeeds")
    void updateToNewUniqueValue() throws Exception {
        insertRow(1, "alice@test.com", "Alice");
        insertRow(2, "bob@test.com", "Bob");

        // Update Bob's email to a new unique value — should succeed
        ColumnInfo emailCol = table.getColumn("email");
        ListExecutor child = new ListExecutor(
                List.of(new Row(new Datum[]{Datum.of(2L), Datum.of("bob@test.com"), Datum.of("Bob")})),
                table.getColumns()
        );
        UpdateExecutor update = new UpdateExecutor(
                txnCtx, child, table,
                List.of(emailCol),
                List.of(new Constant(Datum.of("charlie@test.com"), DataType.VARCHAR))
        );
        update.open();
        update.next();
        update.close();
        assertThat(update.affectedRows()).isEqualTo(1);

        // Verify the old index key is removed and new one exists
        byte[] oldIdxKey = TableCodec.encodeIndexKey(table.getId(), 2,
                List.of(Datum.of("bob@test.com")), null);
        byte[] newIdxKey = TableCodec.encodeIndexKey(table.getId(), 2,
                List.of(Datum.of("charlie@test.com")), null);
        assertThat(txnCtx.getter().get(oldIdxKey)).isNull();
        assertThat(txnCtx.getter().get(newIdxKey)).isNotNull();
    }

    @Test
    @DisplayName("UPDATE keeping same unique value succeeds (no self-conflict)")
    void updateSameUniqueValue() throws Exception {
        insertRow(1, "alice@test.com", "Alice");

        // Update Alice's name but keep the email — should succeed
        ColumnInfo nameCol = table.getColumn("name");
        ListExecutor child = new ListExecutor(
                List.of(new Row(new Datum[]{Datum.of(1L), Datum.of("alice@test.com"), Datum.of("Alice")})),
                table.getColumns()
        );
        UpdateExecutor update = new UpdateExecutor(
                txnCtx, child, table,
                List.of(nameCol),
                List.of(new Constant(Datum.of("Alicia"), DataType.VARCHAR))
        );
        update.open();
        update.next();
        update.close();
        assertThat(update.affectedRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("UPDATE multiple rows to same unique value fails on second row")
    void updateMultipleRowsToSameUniqueValue() throws Exception {
        insertRow(1, "alice@test.com", "Alice");
        insertRow(2, "bob@test.com", "Bob");

        // Try to update both rows to the same email — should fail on the second
        ColumnInfo emailCol = table.getColumn("email");
        ListExecutor child = new ListExecutor(
                List.of(
                        new Row(new Datum[]{Datum.of(1L), Datum.of("alice@test.com"), Datum.of("Alice")}),
                        new Row(new Datum[]{Datum.of(2L), Datum.of("bob@test.com"), Datum.of("Bob")})
                ),
                table.getColumns()
        );
        UpdateExecutor update = new UpdateExecutor(
                txnCtx, child, table,
                List.of(emailCol),
                List.of(new Constant(Datum.of("same@test.com"), DataType.VARCHAR))
        );
        update.open();
        assertThatThrownBy(update::next)
                .isInstanceOf(XDBException.class)
                .hasMessageContaining("Duplicate entry");
        update.close();
    }

    /**
     * Wrapper for byte[] to use as TreeMap key with proper equals/hashCode/compareTo.
     */
    private static class ByteArrayKey implements Comparable<ByteArrayKey> {
        final byte[] data;

        ByteArrayKey(byte[] data) {
            this.data = data;
        }

        @Override
        public int compareTo(ByteArrayKey o) {
            return java.util.Arrays.compare(this.data, o.data);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ByteArrayKey that)) return false;
            return java.util.Arrays.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(data);
        }
    }
}
