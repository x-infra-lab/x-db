package io.github.xinfra.lab.xdb.ddl.backfill;

import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Backfills index data when adding a new index.
 *
 * <p>During the WRITE_REORGANIZATION -> PUBLIC transition of ADD_INDEX,
 * the backfiller scans all existing rows and creates index entries for them.
 */
public class IndexBackfiller {
    private static final Logger log = LoggerFactory.getLogger(IndexBackfiller.class);

    @FunctionalInterface
    public interface RowScanner {
        /**
         * Scan table rows and create index entries for the given index.
         *
         * @param tableId  the table to scan
         * @param indexInfo the index to backfill
         * @param columns  the table's column definitions
         */
        void scanAndIndex(long tableId, IndexInfo indexInfo, List<ColumnInfo> columns);
    }

    private final RowScanner scanner;

    public IndexBackfiller(RowScanner scanner) {
        this.scanner = scanner;
    }

    /**
     * Backfill index entries for all existing rows in the table.
     *
     * @param table the table info containing column definitions
     * @param index the index to backfill
     */
    public void backfill(TableInfo table, IndexInfo index) {
        log.info("Starting index backfill: tableId={}, tableName={}, indexId={}, indexName={}",
                table.getId(), table.getName(), index.getId(), index.getName());

        long startTime = System.currentTimeMillis();
        scanner.scanAndIndex(table.getId(), index, table.getColumns());
        long elapsed = System.currentTimeMillis() - startTime;

        log.info("Index backfill completed: tableId={}, indexName={}, elapsed={}ms",
                table.getId(), index.getName(), elapsed);
    }
}
