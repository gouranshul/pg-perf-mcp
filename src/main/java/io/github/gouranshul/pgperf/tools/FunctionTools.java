package io.github.gouranshul.pgperf.tools;

import io.github.gouranshul.pgperf.analysis.ExplainResult;
import io.github.gouranshul.pgperf.analysis.FunctionAnalysis;
import io.github.gouranshul.pgperf.analysis.FunctionAnalysis.FunctionCall;
import io.github.gouranshul.pgperf.analysis.FunctionAnalysis.StatementStats;
import io.github.gouranshul.pgperf.analysis.FunctionAnalyzer;
import io.github.gouranshul.pgperf.analysis.NestedPlan;
import io.github.gouranshul.pgperf.analysis.TableInfo;
import io.github.gouranshul.pgperf.audit.RowCounted;
import io.github.gouranshul.pgperf.config.PgPerfProperties;
import io.github.gouranshul.pgperf.db.CatalogQueries;
import io.github.gouranshul.pgperf.db.ReadOnlyExecutor;
import io.github.gouranshul.pgperf.db.ReadOnlySession;
import io.github.gouranshul.pgperf.db.ReadOnlySession.ResultWithNotices;
import io.github.gouranshul.pgperf.guard.SqlGuard;
import io.github.gouranshul.pgperf.guard.ValidatedSql;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Database functions: which ones cost the most, and what the statements inside them do.
 *
 * <p>EXPLAIN of a query shows a function call as one opaque expression. {@code explain_function}
 * runs the query once with {@code auto_explain} switched on for that transaction only, so the
 * plan of every statement executed inside the functions comes back as a notice, and combines it
 * with the per-function counters ({@code pg_stat_xact_user_functions}) and the
 * {@code pg_stat_statements} counters of the nested statements, both as before/after differences.
 * See ADR 0006.
 */
@Component
public class FunctionTools {

    private static final Logger log = LoggerFactory.getLogger(FunctionTools.class);

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;
    private static final int MAX_NESTED_STATEMENTS = 50;
    private static final String SAVEPOINT = "pgperf_functions";

    /** User-defined functions only: not built-ins, not functions that belong to an extension. */
    private static final String USER_FUNCTIONS = """
            JOIN pg_proc p ON p.oid = f.funcid
            JOIN pg_language l ON l.oid = p.prolang
            WHERE f.schemaname NOT IN ('pg_catalog', 'information_schema')
              AND NOT EXISTS (SELECT 1 FROM pg_depend d
                              WHERE d.classid = 'pg_proc'::regclass AND d.objid = f.funcid AND d.deptype = 'e')
            """;

    private final SqlGuard guard;
    private final ReadOnlyExecutor executor;
    private final CatalogQueries catalog;
    private final ToolRunner runner;
    private final JsonMapper json;
    private final Duration nestedPlanThreshold;
    private final FunctionAnalyzer analyzer = new FunctionAnalyzer();

    public FunctionTools(SqlGuard guard, ReadOnlyExecutor executor, CatalogQueries catalog, ToolRunner runner,
            JsonMapper json, PgPerfProperties properties) {
        this.guard = guard;
        this.executor = executor;
        this.catalog = catalog;
        this.runner = runner;
        this.json = json;
        this.nestedPlanThreshold = properties.functions().nestedPlanThreshold();
    }

    /** Sort orders for slow_functions, mapped to fixed SQL so client input never reaches the query text. */
    enum OrderBy {
        TOTAL_TIME("f.total_time"),
        SELF_TIME("f.self_time"),
        MEAN_TIME("f.total_time / nullif(f.calls, 0)"),
        CALLS("f.calls");

        final String expression;

        OrderBy(String expression) {
            this.expression = expression;
        }

        static OrderBy parse(String value) {
            if (value == null || value.isBlank()) {
                return TOTAL_TIME;
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ToolException("orderBy must be one of total_time, self_time, mean_time, calls (got '%s')"
                        .formatted(value));
            }
        }
    }

    /**
     * @param function    schema-qualified name
     * @param arguments   identity arguments, e.g. {@code p_product_id bigint}
     * @param language    plpgsql, sql, ...
     * @param volatility  IMMUTABLE, STABLE or VOLATILE
     * @param calls       times called since statistics were reset
     * @param totalTimeMs time in the function including functions it called
     * @param selfTimeMs  time in the function itself
     * @param meanTimeMs  total time per call
     */
    public record SlowFunction(String function, String arguments, String language, String volatility, long calls,
            double totalTimeMs, double selfTimeMs, double meanTimeMs) {
    }

    public record SlowFunctions(String orderedBy, List<SlowFunction> functions, String hint) implements RowCounted {

        @Override
        public int rowCount() {
            return functions.size();
        }
    }

    /**
     * @param analysis what happened inside the functions
     * @param notes    what could not be measured and why
     * @param hint     suggested next step
     */
    public record ExplainFunctionResponse(FunctionAnalysis analysis, List<String> notes, String hint)
            implements RowCounted {

        @Override
        public int rowCount() {
            return analysis.statements().size();
        }
    }

    @McpTool(name = "slow_functions", title = "Slow database functions",
            description = """
                    List the user-defined database functions (PL/pgSQL, SQL, ...) that cost the most time, from \
                    pg_stat_user_functions: calls, total time (including functions they call), self time and \
                    mean time per call, plus language and volatility. Use it when the workload calls functions, \
                    or when top_slow_queries shows statements that call them. Then call explain_function with a \
                    SELECT that calls the worst one with realistic arguments. Needs track_functions = pl or all. \
                    Read-only.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult slowFunctions(
            @McpToolParam(required = false,
                    description = "How many functions to return, 1-50. Default 10.") Integer limit,
            @McpToolParam(required = false,
                    description = "Ranking: total_time (default), self_time (excluding functions they call), "
                            + "mean_time (slowest per call) or calls (most frequent).") String orderBy) {
        return runner.run("slow_functions", ToolRunner.args("limit", limit, "orderBy", orderBy),
                () -> collect(limit, OrderBy.parse(orderBy)));
    }

    SlowFunctions collect(Integer limit, OrderBy order) {
        int n = limit == null ? DEFAULT_LIMIT : limit;
        if (n < 1 || n > MAX_LIMIT) {
            throw new ToolException("limit must be between 1 and " + MAX_LIMIT);
        }
        int effective = Math.min(n, executor.maxRows());
        return executor.run(db -> {
            List<SlowFunction> functions = db.client().sql("""
                    SELECT quote_ident(f.schemaname) || '.' || quote_ident(f.funcname) AS name,
                           pg_get_function_identity_arguments(f.funcid) AS arguments,
                           l.lanname AS language, p.provolatile::text AS volatility,
                           f.calls, f.total_time, f.self_time
                    FROM pg_stat_user_functions f
                    """ + USER_FUNCTIONS + """
                      AND f.calls > 0
                    ORDER BY %s DESC NULLS LAST
                    LIMIT ?
                    """.formatted(order.expression))
                    .param(effective)
                    .query((rs, i) -> new SlowFunction(
                            rs.getString("name"),
                            rs.getString("arguments"),
                            rs.getString("language"),
                            FunctionAnalyzer.volatility(rs.getString("volatility")),
                            rs.getLong("calls"),
                            round(rs.getDouble("total_time")),
                            round(rs.getDouble("self_time")),
                            rs.getLong("calls") == 0 ? 0 : round(rs.getDouble("total_time") / rs.getLong("calls"))))
                    .list();
            String hint;
            if (!functions.isEmpty()) {
                SlowFunction top = functions.getFirst();
                hint = "Next: explain_function with a SELECT that calls %s(%s) with realistic arguments."
                        .formatted(top.function(), top.arguments());
            } else if ("none".equals(setting(db, "track_functions"))) {
                hint = "track_functions is off, so no function timings are collected. Ask a DBA to set "
                        + "track_functions = 'pl' (or 'all' to include SQL functions) and reload the configuration.";
            } else {
                hint = "No user-defined function has run since statistics were last reset.";
            }
            return new SlowFunctions(order.name().toLowerCase(Locale.ROOT), functions, hint);
        });
    }

    @McpTool(name = "explain_function", title = "Explain the functions a query calls",
            description = """
                    Look inside the database functions a query calls. EXPLAIN shows a function call as one opaque \
                    expression, so a slow function body is invisible to explain_query. This tool runs the SELECT \
                    once (EXPLAIN ANALYZE, inside a read-only transaction that is rolled back and cancelled after \
                    the statement timeout) and returns: the time spent in each user-defined function, every \
                    statement executed inside them with calls, time and its own analyzed plan, and findings such \
                    as a sequential scan inside a function, a function called once per row, or a read-only \
                    function declared VOLATILE. Pass a SELECT with concrete values, e.g. \
                    SELECT shop.product_rating(42) or a page query that calls functions per row. Functions that \
                    write fail, because nothing here can modify the database. The function source is included \
                    so you can propose a rewrite; suggested fixes are never executed.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult explainFunction(
            @McpToolParam(description = "One SELECT that calls the function(s), with concrete argument values.")
            String sql) {
        return runner.run("explain_function", ToolRunner.args("sql", sql), () -> explain(sql));
    }

    ExplainFunctionResponse explain(String sql) {
        ValidatedSql validated = guard.validate(sql);
        if (validated.hasParameters()) {
            throw new ToolException("explain_function runs the query, so it needs real values: replace the $"
                    + validated.parameterCount() + "-style placeholders, e.g. SELECT shop.my_function(42).");
        }
        return executor.run(db -> {
            List<String> notes = new ArrayList<>();
            String trackFunctions = setting(db, "track_functions");
            if ("none".equals(trackFunctions)) {
                notes.add("track_functions is off, so per-function timings are missing. Set track_functions = "
                        + "'pl' (or 'all' to include SQL functions) to collect them.");
            }
            boolean countStatements = catalog.extensionInstalled(db, "pg_stat_statements")
                    && "all".equals(setting(db, "pg_stat_statements.track"));
            if (!countStatements) {
                notes.add("Statements inside functions are not counted: that needs pg_stat_statements with "
                        + "pg_stat_statements.track = all.");
            }
            String statementsBefore = countStatements ? statementSnapshot(db) : null;
            String functionsBefore = functionSnapshot(db);

            db.client().sql("SAVEPOINT " + SAVEPOINT).update();
            boolean capturing = enableNestedPlans(db, notes);
            // Safe to concatenate: the guard proved this is exactly one SELECT statement.
            String explainSql = "EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT JSON) " + validated.sql();
            ResultWithNotices result = db.queryForStringWithNotices(explainSql);
            // Switches auto_explain and plan_cache_mode back off before anything else runs.
            db.client().sql("ROLLBACK TO SAVEPOINT " + SAVEPOINT).update();

            ExplainResult outer = ExplainResult.parse(json.readTree(result.value()));
            List<NestedPlan> plans = !capturing ? List.of() : result.notices().stream()
                    .map(n -> NestedPlan.parse(n, json))
                    .flatMap(Optional::stream)
                    .filter(p -> !p.queryText().equals(explainSql))
                    .toList();
            List<FunctionCall> functions = functionCalls(db, functionsBefore);
            List<StatementStats> stats = countStatements ? statementDelta(db, statementsBefore) : List.of();

            Set<String> relations = new LinkedHashSet<>(QueryPlanner.relations(outer));
            plans.forEach(p -> relations.addAll(QueryPlanner.relations(p.plan())));
            Map<String, Long> rows = new LinkedHashMap<>();
            for (TableInfo table : catalog.tables(db, relations).values()) {
                rows.put(table.qualifiedName(), table.estimatedRows());
            }

            if (functions.isEmpty() && !"none".equals(trackFunctions)) {
                notes.add("No user-defined function ran as a separate call. Simple SQL functions are inlined into "
                        + "the query, so their work already shows in outerPlan.");
            }
            if (capturing && plans.isEmpty() && !stats.isEmpty()) {
                notes.add(("No statement inside a function took %d ms or more, so no nested plans were captured "
                        + "(pgperf.functions.nested-plan-threshold). Their calls and timings are still listed.")
                        .formatted(nestedPlanThreshold.toMillis()));
            }
            FunctionAnalysis analysis = analyzer.analyze(outer, functions, stats, plans, rows);
            return new ExplainFunctionResponse(analysis, List.copyOf(notes), hint(analysis));
        });
    }

    /**
     * Switches auto_explain on for this transaction only (SET LOCAL semantics, undone by the
     * caller's ROLLBACK TO SAVEPOINT). Generic plans keep data values out of the reported plans:
     * a custom plan would print the value a PL/pgSQL variable held, which may come from a table.
     */
    private boolean enableNestedPlans(ReadOnlySession db, List<String> notes) {
        boolean loaded = Boolean.TRUE.equals(db.client()
                .sql("SELECT EXISTS (SELECT 1 FROM pg_settings WHERE name = 'auto_explain.log_min_duration')")
                .query(Boolean.class).single());
        if (!loaded) {
            notes.add("auto_explain is not loaded, so the plans of statements inside functions are not shown "
                    + "(their timings are). Add it to shared_preload_libraries, or have a DBA run ALTER ROLE "
                    + "<this role> SET session_preload_libraries = 'auto_explain'.");
            return false;
        }
        try {
            db.client().sql("""
                    SELECT set_config('auto_explain.log_analyze', 'on', true),
                           set_config('auto_explain.log_nested_statements', 'on', true),
                           set_config('auto_explain.log_format', 'json', true),
                           set_config('auto_explain.log_level', 'notice', true),
                           set_config('auto_explain.log_verbose', 'on', true),
                           set_config('auto_explain.log_parameter_max_length', '0', true),
                           set_config('plan_cache_mode', 'force_generic_plan', true),
                           set_config('auto_explain.log_min_duration', ?, true)
                    """)
                    .param(Long.toString(nestedPlanThreshold.toMillis()))
                    .query((rs, i) -> 0)
                    .list();
            return true;
        } catch (DataAccessException e) {
            log.debug("Could not enable auto_explain for this transaction", e);
            db.client().sql("ROLLBACK TO SAVEPOINT " + SAVEPOINT).update();
            notes.add("This role may not change auto_explain settings, so the plans of statements inside functions "
                    + "are not shown (their timings are). On PostgreSQL 15+ a DBA can allow it with: GRANT SET ON "
                    + "PARAMETER auto_explain.log_min_duration, auto_explain.log_analyze, "
                    + "auto_explain.log_nested_statements, auto_explain.log_format, auto_explain.log_level, "
                    + "auto_explain.log_verbose, auto_explain.log_parameter_max_length TO <this role>;");
            return false;
        }
    }

    /** One row holding every nested statement's counters, so the delta survives the 200-row cap. */
    private static String statementSnapshot(ReadOnlySession db) {
        return db.client().sql("""
                SELECT coalesce(jsonb_object_agg(s.queryid || ':' || s.userid,
                                                 jsonb_build_array(s.calls, s.total_exec_time)), '{}')::text
                FROM pg_stat_statements s
                WHERE NOT s.toplevel AND s.dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                """).query(String.class).single();
    }

    /**
     * Counters of the statements executed inside functions since {@code before}. Other sessions
     * running the same statements at the same moment would be counted too.
     */
    private static List<StatementStats> statementDelta(ReadOnlySession db, String before) {
        return db.client().sql("""
                WITH before AS (
                    SELECT key, (value ->> 0)::bigint AS calls, (value ->> 1)::float8 AS total_time
                    FROM jsonb_each(?::jsonb))
                SELECT s.queryid, min(left(s.query, 2000)) AS query,
                       sum(s.calls - coalesce(b.calls, 0)) AS calls,
                       sum(s.total_exec_time - coalesce(b.total_time, 0)) AS total_time
                FROM pg_stat_statements s
                LEFT JOIN before b ON b.key = s.queryid || ':' || s.userid
                WHERE NOT s.toplevel
                  AND s.dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                  AND s.calls > coalesce(b.calls, 0)
                  AND s.query !~* '^[[:space:]]*explain[[:space:]]'
                GROUP BY s.queryid
                ORDER BY total_time DESC
                LIMIT ?
                """)
                .param(before)
                .param(MAX_NESTED_STATEMENTS)
                .query((rs, i) -> new StatementStats(rs.getLong("queryid"), rs.getString("query"),
                        rs.getLong("calls"), rs.getDouble("total_time")))
                .list();
    }

    /**
     * This backend's function counters. pg_stat_xact_user_functions reads the backend's pending
     * statistics, which are flushed at most once a second, so it can still hold calls from earlier
     * transactions on the same pooled connection: only the difference to this snapshot is ours.
     */
    private static String functionSnapshot(ReadOnlySession db) {
        return db.client().sql("""
                SELECT coalesce(jsonb_object_agg(f.funcid::text,
                                                 jsonb_build_array(f.calls, f.total_time, f.self_time)), '{}')::text
                FROM pg_stat_xact_user_functions f
                """).query(String.class).single();
    }

    /** Functions called since {@code before}, with their counters for this execution only. */
    private static List<FunctionCall> functionCalls(ReadOnlySession db, String before) {
        return db.client().sql("""
                WITH before AS (
                    SELECT key::oid AS funcid, (value ->> 0)::bigint AS calls, (value ->> 1)::float8 AS total_time,
                           (value ->> 2)::float8 AS self_time
                    FROM jsonb_each(?::jsonb))
                SELECT quote_ident(f.schemaname) || '.' || quote_ident(f.funcname) AS name,
                       pg_get_function_identity_arguments(f.funcid) AS arguments,
                       l.lanname AS language, p.provolatile::text AS volatility,
                       f.calls - coalesce(b.calls, 0) AS calls,
                       f.total_time - coalesce(b.total_time, 0) AS total_time,
                       f.self_time - coalesce(b.self_time, 0) AS self_time,
                       CASE WHEN p.prokind IN ('f', 'p')
                            THEN left(pg_get_functiondef(f.funcid), %d) END AS definition
                FROM pg_stat_xact_user_functions f
                LEFT JOIN before b ON b.funcid = f.funcid
                """.formatted(FunctionAnalyzer.MAX_DEFINITION_LENGTH + 1) + USER_FUNCTIONS + """
                  AND f.calls > coalesce(b.calls, 0)
                ORDER BY total_time DESC
                LIMIT %d
                """.formatted(MAX_NESTED_STATEMENTS))
                .param(before)
                .query((rs, i) -> new FunctionCall(rs.getString("name"), rs.getString("arguments"),
                        rs.getString("language"), rs.getString("volatility"), rs.getLong("calls"),
                        rs.getDouble("total_time"), rs.getDouble("self_time"), rs.getString("definition")))
                .list();
    }

    private static String setting(ReadOnlySession db, String name) {
        return db.client().sql("SELECT current_setting(?, true)").param(name).query(String.class).single();
    }

    private static String hint(FunctionAnalysis analysis) {
        if (analysis.functions().isEmpty() && analysis.statements().isEmpty()) {
            return "Nothing ran inside a function. Use explain_query for this statement.";
        }
        return "Fix HIGH findings first. To index a table used inside a function, run suggest_indexes on the nested "
                + "statement with a realistic value in place of the function variable. Each function's source is in "
                + "functions[].definition. Nothing was changed.";
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
