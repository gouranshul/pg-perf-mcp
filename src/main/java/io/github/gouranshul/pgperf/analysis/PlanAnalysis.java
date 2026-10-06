package io.github.gouranshul.pgperf.analysis;

import java.util.List;

/**
 * A compact, LLM-friendly summary of a query plan: the expensive nodes and the specific problems
 * found, instead of the raw (often thousands of lines long) EXPLAIN output.
 *
 * @param analyzed        whether the plan came from EXPLAIN ANALYZE (actual rows and timings)
 * @param totalCost       planner total cost estimate for the query
 * @param planningTimeMs  planning time (ANALYZE only)
 * @param executionTimeMs execution time (ANALYZE only)
 * @param nodeCount       number of nodes in the full plan
 * @param costliestNodes  up to five nodes with the highest self time (ANALYZE) or self cost
 * @param findings        problems detected, most severe first
 */
public record PlanAnalysis(
        boolean analyzed,
        double totalCost,
        Double planningTimeMs,
        Double executionTimeMs,
        int nodeCount,
        List<NodeSummary> costliestNodes,
        List<Finding> findings) {

    /**
     * @param node          label such as "Seq Scan on shop.orders o"
     * @param selfCost      estimated cost of this node alone
     * @param selfTimeMs    measured time in this node alone, all loops (ANALYZE only)
     * @param estimatedRows rows the planner expected per loop
     * @param actualRows    rows actually produced per loop (ANALYZE only)
     * @param loops         times the node was executed (ANALYZE only)
     * @param detail        filter or condition, when there is one
     */
    public record NodeSummary(String node, double selfCost, Double selfTimeMs, double estimatedRows,
            Double actualRows, Double loops, String detail) {
    }

    public enum Severity { HIGH, MEDIUM, LOW }

    public enum FindingType {
        SEQ_SCAN_ON_LARGE_TABLE,
        ROW_ESTIMATE_MISMATCH,
        SORT_SPILLED_TO_DISK,
        HASH_SPILLED_TO_DISK,
        NESTED_LOOP_HIGH_LOOPS,
        /** A user-defined function accounts for most of a query's execution time. */
        FUNCTION_DOMINATES_QUERY,
        /** A function runs once per row, re-running every statement inside it. */
        FUNCTION_CALLED_PER_ROW,
        /** A function is declared VOLATILE (the default) but only read data in this run. */
        VOLATILE_FUNCTION_ONLY_READS
    }

    /**
     * @param severity   how much this is likely to matter
     * @param type       machine-readable category
     * @param node       the plan node it concerns
     * @param message    what was observed
     * @param suggestion what to try next
     */
    public record Finding(Severity severity, FindingType type, String node, String message, String suggestion) {
    }
}
