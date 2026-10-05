package io.github.gouranshul.pgperf.tools;

import io.github.gouranshul.pgperf.audit.RowCounted;
import io.github.gouranshul.pgperf.db.ReadOnlyExecutor;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/** Indexes that cost write time and disk space but are never read. */
@Component
public class UnusedIndexTools {

    private final ReadOnlyExecutor executor;
    private final ToolRunner runner;

    public UnusedIndexTools(ReadOnlyExecutor executor, ToolRunner runner) {
        this.executor = executor;
        this.runner = runner;
    }

    /**
     * @param index         schema-qualified index name
     * @param table         schema-qualified table name
     * @param sizeBytes     on-disk size
     * @param size          human-readable size
     * @param definition    CREATE INDEX statement (to recreate it if needed)
     * @param valid         false if a failed CREATE INDEX CONCURRENTLY left it invalid
     * @param dropStatement statement to review and run manually; never executed by this server
     */
    public record UnusedIndex(String index, String table, long sizeBytes, String size, String definition,
            boolean valid, String dropStatement) {
    }

    /**
     * @param statsSince when usage counters were last reset; "unused" only means "since then"
     */
    public record UnusedIndexes(OffsetDateTime statsSince, long totalBytes, List<UnusedIndex> indexes,
            String caution) implements RowCounted {

        @Override
        public int rowCount() {
            return indexes.size();
        }
    }

    @McpTool(name = "unused_indexes", title = "Unused indexes",
            description = """
                    List indexes that have never been scanned (idx_scan = 0) since statistics were last reset, \
                    largest first, with their size and definition. Primary keys, unique indexes and indexes that \
                    back a constraint are excluded because they enforce correctness even when unread. Unused \
                    indexes slow down every INSERT/UPDATE and waste disk and cache. Includes a DROP INDEX \
                    CONCURRENTLY statement for review; nothing is dropped. Read-only.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult unusedIndexes() {
        return runner.run("unused_indexes", ToolRunner.args(), this::find);
    }

    UnusedIndexes find() {
        return executor.run(db -> {
            OffsetDateTime statsSince = db.client()
                    .sql("SELECT stats_reset FROM pg_stat_database WHERE datname = current_database()")
                    .query(OffsetDateTime.class)
                    .optional()
                    .orElse(null);
            List<UnusedIndex> indexes = db.client().sql("""
                    SELECT format('%I.%I', s.schemaname, s.indexrelname) AS index_name,
                           format('%I.%I', s.schemaname, s.relname) AS table_name,
                           pg_relation_size(s.indexrelid) AS size_bytes,
                           pg_size_pretty(pg_relation_size(s.indexrelid)) AS size,
                           pg_get_indexdef(s.indexrelid) AS definition,
                           i.indisvalid AS valid
                    FROM pg_stat_user_indexes s
                    JOIN pg_index i ON i.indexrelid = s.indexrelid
                    WHERE s.idx_scan = 0
                      AND NOT i.indisprimary
                      AND NOT i.indisunique
                      AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = s.indexrelid)
                    ORDER BY pg_relation_size(s.indexrelid) DESC, 1
                    """)
                    .query((rs, n) -> new UnusedIndex(
                            rs.getString("index_name"),
                            rs.getString("table_name"),
                            rs.getLong("size_bytes"),
                            rs.getString("size"),
                            rs.getString("definition"),
                            rs.getBoolean("valid"),
                            "DROP INDEX CONCURRENTLY " + rs.getString("index_name") + ";"))
                    .list();
            long total = indexes.stream().mapToLong(UnusedIndex::sizeBytes).sum();
            return new UnusedIndexes(statsSince, total, indexes,
                    "Usage counters are per server: an index unused here may still serve queries on a read replica."
                            + " Check statsSince covers a full business cycle before dropping anything."
                            + " Nothing was dropped.");
        });
    }
}
