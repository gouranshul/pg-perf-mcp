package io.github.gouranshul.pgperf.tools;

import io.github.gouranshul.pgperf.analysis.TableHealthRules;
import io.github.gouranshul.pgperf.analysis.TableHealthRules.Stats;
import io.github.gouranshul.pgperf.db.ReadOnlyExecutor;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Vacuum, bloat, statistics freshness and scan patterns per table. */
@Component
public class TableHealthTools {

    static final int MAX_TABLES = 25;

    private final ReadOnlyExecutor executor;
    private final ToolRunner runner;
    private final TableHealthRules rules = new TableHealthRules();

    public TableHealthTools(ReadOnlyExecutor executor, ToolRunner runner) {
        this.executor = executor;
        this.runner = runner;
    }

    /**
     * @param deadRatio fraction of dead tuples (0..1), null for an empty table
     * @param warnings  problems found, in plain language with a suggested fix
     */
    public record TableHealth(String table, long liveTuples, long deadTuples, Double deadRatio,
            long modifiedSinceAnalyze, OffsetDateTime lastVacuum, OffsetDateTime lastAutovacuum,
            OffsetDateTime lastAnalyze, OffsetDateTime lastAutoanalyze, long seqScans, long idxScans,
            boolean autovacuumEnabled, String tableSize, String indexesSize, String totalSize, List<String> warnings) {
    }

    public record TableHealthReport(int tablesShown, List<TableHealth> tables) {
    }

    @McpTool(name = "table_health", title = "Table health",
            description = """
                    Report vacuum and statistics health per table: live and dead tuples, dead-tuple ratio, last \
                    (auto)vacuum and (auto)analyze, rows changed since the last analyze, sequential vs index \
                    scans, and table/index sizes, each with plain-language warnings (bloat, disabled autovacuum, \
                    stale statistics, scan-heavy large tables). Without a table name it returns the tables with \
                    the most dead tuples (up to 25). Read-only.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult tableHealth(
            @McpToolParam(required = false, description = "Table name, optionally schema-qualified "
                    + "(e.g. orders or shop.orders). Omit to scan all user tables.") String table) {
        return runner.run("table_health", () -> report(table));
    }

    TableHealthReport report(String table) {
        String filter = table == null || table.isBlank() ? null : table.strip();
        return executor.run(db -> {
            OffsetDateTime now = db.client().sql("SELECT now()").query(OffsetDateTime.class).single();
            List<TableHealth> tables = db.client().sql("""
                    SELECT format('%I.%I', s.schemaname, s.relname) AS table_name,
                           s.n_live_tup, s.n_dead_tup, s.n_mod_since_analyze,
                           s.last_vacuum, s.last_autovacuum, s.last_analyze, s.last_autoanalyze,
                           s.seq_scan, s.seq_tup_read, coalesce(s.idx_scan, 0) AS idx_scan,
                           NOT coalesce('autovacuum_enabled=false' = ANY (c.reloptions), false) AS autovacuum_enabled,
                           pg_size_pretty(pg_table_size(s.relid)) AS table_size,
                           pg_size_pretty(pg_indexes_size(s.relid)) AS indexes_size,
                           pg_size_pretty(pg_total_relation_size(s.relid)) AS total_size
                    FROM pg_stat_user_tables s
                    JOIN pg_class c ON c.oid = s.relid
                    WHERE CAST(? AS text) IS NULL
                       OR s.relname = CAST(? AS text)
                       OR s.schemaname || '.' || s.relname = CAST(? AS text)
                    ORDER BY s.n_dead_tup DESC, pg_total_relation_size(s.relid) DESC
                    LIMIT ?
                    """)
                    .params(filter, filter, filter, MAX_TABLES)
                    .query((rs, n) -> toHealth(rs, now))
                    .list();
            if (filter != null && tables.isEmpty()) {
                throw new ToolException("No user table named '" + filter + "' was found. Read the pg://schema/overview"
                        + " resource for the list of tables.");
            }
            return new TableHealthReport(tables.size(), tables);
        });
    }

    private TableHealth toHealth(ResultSet rs, OffsetDateTime now) throws SQLException {
        Stats stats = new Stats(
                rs.getString("table_name"),
                rs.getLong("n_live_tup"),
                rs.getLong("n_dead_tup"),
                rs.getLong("n_mod_since_analyze"),
                rs.getObject("last_vacuum", OffsetDateTime.class),
                rs.getObject("last_autovacuum", OffsetDateTime.class),
                rs.getObject("last_analyze", OffsetDateTime.class),
                rs.getObject("last_autoanalyze", OffsetDateTime.class),
                rs.getLong("seq_scan"),
                rs.getLong("seq_tup_read"),
                rs.getLong("idx_scan"),
                rs.getBoolean("autovacuum_enabled"));
        Double ratio = stats.deadRatio();
        return new TableHealth(stats.table(), stats.liveTuples(), stats.deadTuples(),
                ratio == null ? null : Math.round(ratio * 1000) / 1000.0,
                stats.modifiedSinceAnalyze(), stats.lastVacuum(), stats.lastAutovacuum(), stats.lastAnalyze(),
                stats.lastAutoanalyze(), stats.seqScans(), stats.idxScans(), stats.autovacuumEnabled(),
                rs.getString("table_size"), rs.getString("indexes_size"), rs.getString("total_size"),
                rules.warnings(stats, now));
    }
}
