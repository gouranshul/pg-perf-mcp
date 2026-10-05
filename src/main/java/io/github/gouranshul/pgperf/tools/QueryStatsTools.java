package io.github.gouranshul.pgperf.tools;

import io.github.gouranshul.pgperf.audit.RowCounted;
import io.github.gouranshul.pgperf.db.CatalogQueries;
import io.github.gouranshul.pgperf.db.ReadOnlyExecutor;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Workload-level statistics from {@code pg_stat_statements}. */
@Component
public class QueryStatsTools {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;

    private final ReadOnlyExecutor executor;
    private final CatalogQueries catalog;
    private final ToolRunner runner;

    public QueryStatsTools(ReadOnlyExecutor executor, CatalogQueries catalog, ToolRunner runner) {
        this.executor = executor;
        this.catalog = catalog;
        this.runner = runner;
    }

    /** Sort orders the tool accepts, mapped to fixed SQL so client input never reaches the query text. */
    enum OrderBy {
        TOTAL_TIME("total_exec_time"),
        MEAN_TIME("mean_exec_time"),
        CALLS("calls"),
        ROWS("rows");

        final String column;

        OrderBy(String column) {
            this.column = column;
        }

        static OrderBy parse(String value) {
            if (value == null || value.isBlank()) {
                return TOTAL_TIME;
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ToolException("orderBy must be one of total_time, mean_time, calls, rows (got '%s')"
                        .formatted(value));
            }
        }
    }

    /**
     * @param queryId             pg_stat_statements query id (stable across calls)
     * @param query               normalized query text, constants replaced by $1, $2, ...
     * @param calls               times executed
     * @param totalTimeMs         total execution time
     * @param meanTimeMs          mean execution time per call
     * @param maxTimeMs           slowest single execution
     * @param rows                total rows returned or affected
     * @param sharedBlockHitRatio share of shared-buffer reads served from cache (0..1); null if none
     * @param percentOfTotalTime  share of all tracked execution time in this database
     */
    public record SlowQuery(long queryId, String query, long calls, double totalTimeMs, double meanTimeMs,
            double maxTimeMs, long rows, Double sharedBlockHitRatio, double percentOfTotalTime) {
    }

    public record SlowQueries(String orderedBy, List<SlowQuery> queries, String hint) implements RowCounted {

        @Override
        public int rowCount() {
            return queries.size();
        }
    }

    @McpTool(name = "top_slow_queries", title = "Top slow queries",
            description = """
                    List the most expensive SQL statements recorded by pg_stat_statements in the current database: \
                    normalized query text (constants replaced by $1, $2...), call count, total and mean execution \
                    time, rows, and shared-buffer cache hit ratio. Start here when asked why a database is slow. \
                    Pass a query's text to explain_query or suggest_indexes to dig deeper (replace $n placeholders \
                    with realistic values to use analyze=true). Statements issued by this server are excluded. \
                    Read-only.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult topSlowQueries(
            @McpToolParam(required = false,
                    description = "How many statements to return, 1-50. Default 10.") Integer limit,
            @McpToolParam(required = false,
                    description = "Ranking: total_time (default, overall load), mean_time (slowest per call), "
                            + "calls (most frequent, e.g. N+1 patterns) or rows (most rows).") String orderBy) {
        return runner.run("top_slow_queries", ToolRunner.args("limit", limit, "orderBy", orderBy),
                () -> collect(limit, OrderBy.parse(orderBy)));
    }

    SlowQueries collect(Integer limit, OrderBy order) {
        int n = limit == null ? DEFAULT_LIMIT : limit;
        if (n < 1 || n > MAX_LIMIT) {
            throw new ToolException("limit must be between 1 and " + MAX_LIMIT);
        }
        int effective = Math.min(n, executor.maxRows());
        return executor.run(db -> {
            if (!catalog.extensionInstalled(db, "pg_stat_statements")) {
                throw new ToolException("pg_stat_statements is not installed in this database. Add it to "
                        + "shared_preload_libraries, restart Postgres, then run CREATE EXTENSION pg_stat_statements;");
            }
            List<SlowQuery> queries = db.client().sql("""
                    SELECT s.queryid, left(s.query, 2000) AS query, s.calls, s.total_exec_time, s.mean_exec_time,
                           s.max_exec_time, s.rows,
                           s.shared_blks_hit::float8 / nullif(s.shared_blks_hit + s.shared_blks_read, 0) AS hit_ratio,
                           100 * s.total_exec_time / nullif(sum(s.total_exec_time) OVER (), 0) AS pct
                    FROM pg_stat_statements s
                    WHERE s.dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                      AND s.userid <> (SELECT oid FROM pg_roles WHERE rolname = current_user)
                    ORDER BY %s DESC NULLS LAST
                    LIMIT ?
                    """.formatted(order.column))
                    .param(effective)
                    .query((rs, i) -> new SlowQuery(
                            rs.getLong("queryid"),
                            rs.getString("query"),
                            rs.getLong("calls"),
                            round(rs.getDouble("total_exec_time")),
                            round(rs.getDouble("mean_exec_time")),
                            round(rs.getDouble("max_exec_time")),
                            rs.getLong("rows"),
                            rs.getObject("hit_ratio") == null ? null : round(rs.getDouble("hit_ratio")),
                            round(rs.getDouble("pct"))))
                    .list();
            String hint = queries.isEmpty()
                    ? "No statements recorded yet. Run some workload, or check pg_stat_statements.track."
                    : "Next: explain_query on the top entry (use analyze=true only with concrete values).";
            return new SlowQueries(order.name().toLowerCase(Locale.ROOT), queries, hint);
        });
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
