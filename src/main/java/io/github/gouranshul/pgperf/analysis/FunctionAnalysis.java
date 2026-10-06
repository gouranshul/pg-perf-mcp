package io.github.gouranshul.pgperf.analysis;

import io.github.gouranshul.pgperf.analysis.PlanAnalysis.Finding;
import java.util.List;

/**
 * What happened inside the database functions a query called: per-function timings, the
 * statements executed inside them with their own plans, and the problems found.
 *
 * @param executionTimeMs measured execution time of the whole query
 * @param outerPlan       the query's own plan (function calls show up only as opaque expressions here)
 * @param functions       user-defined functions the query called, most expensive first
 * @param statements      statements executed inside those functions, most expensive first
 * @param findings        function-level problems plus problems found in the nested plans, most severe first
 */
public record FunctionAnalysis(
        Double executionTimeMs,
        PlanAnalysis outerPlan,
        List<FunctionSummary> functions,
        List<NestedStatement> statements,
        List<Finding> findings) {

    /**
     * Input: one function's counters for this execution ({@code pg_stat_xact_user_functions}).
     *
     * @param name        schema-qualified name
     * @param arguments   identity arguments, e.g. {@code p_product_id bigint}
     * @param language    plpgsql, sql, ...
     * @param volatility  {@code i}, {@code s} or {@code v} as in {@code pg_proc.provolatile}
     * @param calls       times called during this execution
     * @param totalTimeMs time in the function including functions it called
     * @param selfTimeMs  time in the function itself
     * @param definition  {@code CREATE FUNCTION} source, possibly truncated
     */
    public record FunctionCall(String name, String arguments, String language, String volatility, long calls,
            double totalTimeMs, double selfTimeMs, String definition) {
    }

    /**
     * Input: one nested statement's counters for this execution ({@code pg_stat_statements} delta).
     *
     * @param queryId     pg_stat_statements query id
     * @param query       normalized statement text (constants replaced by $n)
     * @param calls       executions during this run
     * @param totalTimeMs execution time across those calls
     */
    public record StatementStats(long queryId, String query, long calls, double totalTimeMs) {
    }

    /**
     * @param name               schema-qualified name
     * @param arguments          identity arguments
     * @param language           implementation language
     * @param volatility         IMMUTABLE, STABLE or VOLATILE
     * @param calls              times called by the query
     * @param totalTimeMs        time including nested function calls
     * @param selfTimeMs         time in this function alone
     * @param meanTimeMs         total time per call
     * @param percentOfExecution share of the query's execution time spent in this function
     * @param definition         the function source, so the assistant can propose a rewrite
     */
    public record FunctionSummary(String name, String arguments, String language, String volatility, long calls,
            double totalTimeMs, double selfTimeMs, double meanTimeMs, Double percentOfExecution, String definition) {
    }

    /**
     * @param queryId            pg_stat_statements query id, null if unknown
     * @param query              the statement text
     * @param function           the function whose body contains it, when it could be determined
     * @param calls              executions during this query, null without pg_stat_statements
     * @param totalTimeMs        time across those executions, null without pg_stat_statements
     * @param meanTimeMs         time per execution
     * @param percentOfExecution share of the query's execution time
     * @param capturedPlanMs     duration of the execution whose plan is shown (the slowest captured)
     * @param plan               analysis of that plan; null if no execution reached the capture threshold
     */
    public record NestedStatement(Long queryId, String query, String function, Long calls, Double totalTimeMs,
            Double meanTimeMs, Double percentOfExecution, Double capturedPlanMs, PlanAnalysis plan) {
    }
}
