package io.github.gouranshul.pgperf.tools;

import io.github.gouranshul.pgperf.analysis.ExplainResult;
import io.github.gouranshul.pgperf.audit.RowCounted;
import io.github.gouranshul.pgperf.analysis.IndexAdvice;
import io.github.gouranshul.pgperf.analysis.IndexAdvice.Suggestion;
import io.github.gouranshul.pgperf.analysis.IndexAdvisor;
import io.github.gouranshul.pgperf.analysis.TableInfo;
import io.github.gouranshul.pgperf.db.CatalogQueries;
import io.github.gouranshul.pgperf.db.ReadOnlyExecutor;
import io.github.gouranshul.pgperf.db.ReadOnlySession;
import io.github.gouranshul.pgperf.guard.SqlGuard;
import io.github.gouranshul.pgperf.guard.ValidatedSql;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Index recommendations. Suggestions are returned as text and never executed. */
@Component
public class IndexTools {

    private static final Logger log = LoggerFactory.getLogger(IndexTools.class);
    static final String NEVER_EXECUTED = "These statements were NOT executed. Review them, then run them yourself"
            + " outside a transaction block (CONCURRENTLY avoids blocking writes but takes longer).";

    private final SqlGuard guard;
    private final ReadOnlyExecutor executor;
    private final QueryPlanner planner;
    private final CatalogQueries catalog;
    private final ToolRunner runner;
    private final IndexAdvisor advisor = new IndexAdvisor();

    public IndexTools(SqlGuard guard, ReadOnlyExecutor executor, QueryPlanner planner, CatalogQueries catalog,
            ToolRunner runner) {
        this.guard = guard;
        this.executor = executor;
        this.planner = planner;
        this.catalog = catalog;
        this.runner = runner;
    }

    /**
     * @param table               schema-qualified table
     * @param columns             key columns in order
     * @param statement           CREATE INDEX CONCURRENTLY statement to review and run manually
     * @param reasons             what in the plan points at this index
     * @param estimatedCostAfter  planner cost with a hypothetical index (hypopg), null if not validated
     * @param improvementPercent  estimated cost reduction, null if not validated
     * @param usedByPlanner       whether the planner chose the hypothetical index, null if not validated
     */
    public record IndexCandidate(String table, List<String> columns, String statement, List<String> reasons,
            Double estimatedCostAfter, Double improvementPercent, Boolean usedByPlanner) {
    }

    public record SuggestIndexesResponse(double currentEstimatedCost, List<IndexCandidate> suggestions,
            List<String> notes, String validation, String important) implements RowCounted {

        @Override
        public int rowCount() {
            return suggestions.size();
        }
    }

    @McpTool(name = "suggest_indexes", title = "Suggest indexes",
            description = """
                    Recommend btree indexes for a slow SELECT, derived from its plan: filtered sequential scans \
                    on large tables (equality columns first, then one range column), join keys on sequentially \
                    scanned tables, and ORDER BY ... LIMIT that sorts a whole table. Columns already covered by \
                    an existing index are skipped. Returns CREATE INDEX CONCURRENTLY statements with the reason \
                    for each; it NEVER creates them. If the hypopg extension is installed, each suggestion is \
                    checked with a hypothetical index and the estimated cost reduction is reported; otherwise \
                    validation is reported as skipped. The query is planned, not executed.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult suggestIndexes(
            @McpToolParam(description = "One SELECT statement to optimize, e.g. from top_slow_queries.") String sql) {
        return runner.run("suggest_indexes", ToolRunner.args("sql", sql), () -> suggest(sql));
    }

    SuggestIndexesResponse suggest(String sql) {
        ValidatedSql validated = guard.validate(sql);
        return executor.run(db -> {
            ExplainResult plan = planner.explain(db, validated, false);
            Map<String, TableInfo> tables = catalog.tables(db, QueryPlanner.relations(plan));
            IndexAdvice advice = advisor.advise(plan, tables);
            double baseCost = plan.root().totalCost();

            List<IndexCandidate> candidates = new ArrayList<>();
            String validation;
            if (advice.suggestions().isEmpty()) {
                validation = "nothing to validate";
            } else if (!catalog.extensionInstalled(db, "hypopg")) {
                validation = "skipped: the hypopg extension is not installed, so cost reductions were not estimated";
                advice.suggestions().forEach(s -> candidates.add(unvalidated(s)));
            } else {
                validation = validateWithHypopg(db, validated, advice.suggestions(), baseCost, candidates);
            }
            return new SuggestIndexesResponse(baseCost, candidates, advice.notes(), validation, NEVER_EXECUTED);
        });
    }

    /**
     * Creates one hypothetical index at a time (backend memory only, nothing touches disk or the
     * catalog), re-plans the query, then resets. A savepoint keeps the transaction usable if hypopg
     * fails, so {@code hypopg_reset()} always runs and nothing leaks into the pooled connection.
     */
    private String validateWithHypopg(ReadOnlySession db, ValidatedSql sql, List<Suggestion> suggestions,
            double baseCost, List<IndexCandidate> out) {
        db.client().sql("SAVEPOINT pgperf_hypopg").update();
        try {
            for (Suggestion s : suggestions) {
                resetHypopg(db);
                String hypotheticalName = db.client().sql("SELECT indexname FROM hypopg_create_index(?)")
                        .param(hypotheticalDefinition(s))
                        .query(String.class)
                        .single();
                ExplainResult withIndex = planner.explain(db, sql, false);
                double cost = withIndex.root().totalCost();
                boolean used = withIndex.root().flatten().stream()
                        .anyMatch(n -> hypotheticalName.equals(n.indexName()));
                double improvement = baseCost <= 0 ? 0 : Math.round(1000 * (baseCost - cost) / baseCost) / 10.0;
                out.add(new IndexCandidate(s.table(), s.columns(), s.statement(), s.reasons(), cost,
                        improvement, used));
            }
            resetHypopg(db);
            return "estimated with hypopg hypothetical indexes (planner cost, nothing was created)";
        } catch (RuntimeException e) {
            log.warn("hypopg validation failed; returning unvalidated suggestions", e);
            db.client().sql("ROLLBACK TO SAVEPOINT pgperf_hypopg").update();
            resetHypopg(db);
            out.clear();
            suggestions.forEach(s -> out.add(unvalidated(s)));
            return "skipped: hypopg could not evaluate these indexes";
        }
    }

    private static void resetHypopg(ReadOnlySession db) {
        db.client().sql("SELECT hypopg_reset()").query((rs, n) -> 0).list();
    }

    /** hypopg wants a plain CREATE INDEX (no CONCURRENTLY, name optional). */
    static String hypotheticalDefinition(Suggestion s) {
        return s.statement().replaceFirst("^CREATE INDEX CONCURRENTLY \\S+ ON ", "CREATE INDEX ON ");
    }

    private static IndexCandidate unvalidated(Suggestion s) {
        return new IndexCandidate(s.table(), s.columns(), s.statement(), s.reasons(), null, null, null);
    }
}
