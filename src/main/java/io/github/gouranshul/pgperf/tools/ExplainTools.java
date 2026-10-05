package io.github.gouranshul.pgperf.tools;

import io.github.gouranshul.pgperf.analysis.ExplainResult;
import io.github.gouranshul.pgperf.analysis.PlanAnalysis;
import io.github.gouranshul.pgperf.analysis.PlanAnalyzer;
import io.github.gouranshul.pgperf.analysis.TableInfo;
import io.github.gouranshul.pgperf.db.CatalogQueries;
import io.github.gouranshul.pgperf.db.ReadOnlyExecutor;
import io.github.gouranshul.pgperf.guard.SqlGuard;
import io.github.gouranshul.pgperf.guard.ValidatedSql;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Query plan analysis. */
@Component
public class ExplainTools {

    private final SqlGuard guard;
    private final ReadOnlyExecutor executor;
    private final QueryPlanner planner;
    private final CatalogQueries catalog;
    private final ToolRunner runner;
    private final PlanAnalyzer analyzer = new PlanAnalyzer();

    public ExplainTools(SqlGuard guard, ReadOnlyExecutor executor, QueryPlanner planner, CatalogQueries catalog,
            ToolRunner runner) {
        this.guard = guard;
        this.executor = executor;
        this.planner = planner;
        this.catalog = catalog;
        this.runner = runner;
    }

    /**
     * @param mode     "estimated" (planned only), "generic" (planned with $n placeholders) or "analyzed"
     * @param analysis the summarized plan
     */
    public record ExplainResponse(String mode, PlanAnalysis analysis) {
    }

    @McpTool(name = "explain_query", title = "Explain a query",
            description = """
                    Show how PostgreSQL executes a SELECT and what makes it slow. Returns a summary instead of \
                    the raw plan: the 5 costliest plan nodes, sequential scans on large tables, row estimates off \
                    by 10x or more, sorts or hashes spilling to disk, and nested loops with many iterations, each \
                    with a suggested next step. Only a single SELECT is accepted (anything else is rejected by the \
                    SQL guard). With analyze=false (default) the query is only planned, never run. With \
                    analyze=true it really executes, inside a read-only transaction that is rolled back and \
                    cancelled after the statement timeout, so timings and actual row counts are available. \
                    Normalized queries with $1, $2 placeholders work with analyze=false (generic plan).""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult explainQuery(
            @McpToolParam(description = "One SELECT statement, e.g. copied from top_slow_queries.") String sql,
            @McpToolParam(required = false, description = "true to execute the query and report actual rows and "
                    + "timings (EXPLAIN ANALYZE). Default false: plan only.") Boolean analyze) {
        return runner.run("explain_query", () -> explain(sql, Boolean.TRUE.equals(analyze)));
    }

    ExplainResponse explain(String sql, boolean analyze) {
        ValidatedSql validated = guard.validate(sql);
        return executor.run(db -> {
            ExplainResult plan = planner.explain(db, validated, analyze);
            Map<String, Long> rows = new LinkedHashMap<>();
            for (TableInfo table : catalog.tables(db, QueryPlanner.relations(plan)).values()) {
                rows.put(table.qualifiedName(), table.estimatedRows());
            }
            String mode = validated.hasParameters() ? "generic" : analyze ? "analyzed" : "estimated";
            return new ExplainResponse(mode, analyzer.analyze(plan, rows));
        });
    }
}
