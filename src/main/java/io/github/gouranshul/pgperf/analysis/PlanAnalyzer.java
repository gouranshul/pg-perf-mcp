package io.github.gouranshul.pgperf.analysis;

import io.github.gouranshul.pgperf.analysis.PlanAnalysis.Finding;
import io.github.gouranshul.pgperf.analysis.PlanAnalysis.FindingType;
import io.github.gouranshul.pgperf.analysis.PlanAnalysis.NodeSummary;
import io.github.gouranshul.pgperf.analysis.PlanAnalysis.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a raw plan into a {@link PlanAnalysis}. Pure function: no I/O, no state, thread safe.
 *
 * <p>Detects sequential scans on large tables, row estimates off by 10x or more, sorts and hashes
 * that spill to disk, and nested loops whose inner side runs many times.
 */
public final class PlanAnalyzer {

    public static final long LARGE_TABLE_ROWS = 10_000;
    public static final double MISESTIMATE_FACTOR = 10;
    /** Ignore misestimates where both numbers are tiny; they rarely change the plan. */
    static final double MISESTIMATE_MIN_ROWS = 100;
    public static final double HIGH_LOOPS = 1_000;
    private static final int TOP_NODES = 5;
    private static final int MAX_DETAIL_LENGTH = 300;

    /**
     * @param plan      parsed EXPLAIN output
     * @param tableRows estimated row count per table ({@code pg_class.reltuples}), keyed by
     *                  schema-qualified name; tables missing from the map fall back to plan rows
     */
    public PlanAnalysis analyze(ExplainResult plan, Map<String, Long> tableRows) {
        List<PlanNode> nodes = plan.root().flatten();
        boolean analyzed = plan.analyzed();

        Comparator<PlanNode> byCost = analyzed
                ? Comparator.comparingDouble((PlanNode n) -> n.selfTimeMs() == null ? 0 : n.selfTimeMs())
                : Comparator.comparingDouble(PlanNode::selfCost);
        List<NodeSummary> costliest = nodes.stream()
                .filter(n -> !n.neverExecuted())
                .sorted(byCost.reversed())
                .limit(TOP_NODES)
                .map(PlanAnalyzer::summarize)
                .toList();

        List<Finding> findings = new ArrayList<>();
        for (PlanNode node : nodes) {
            seqScanOnLargeTable(node, tableRows).ifPresent(findings::add);
            rowEstimateMismatch(node).ifPresent(findings::add);
            sortSpill(node).ifPresent(findings::add);
            hashSpill(node).ifPresent(findings::add);
            nestedLoopHighLoops(node).ifPresent(findings::add);
        }
        findings.sort(Comparator.comparing(Finding::severity));

        return new PlanAnalysis(analyzed, plan.root().totalCost(), plan.planningTimeMs(), plan.executionTimeMs(),
                nodes.size(), costliest, List.copyOf(findings));
    }

    private static NodeSummary summarize(PlanNode n) {
        Double selfTime = n.selfTimeMs();
        return new NodeSummary(n.label(), round(n.selfCost()), selfTime == null ? null : round(selfTime),
                n.planRows(), n.actualRows(), n.actualLoops(), detail(n));
    }

    private static String detail(PlanNode n) {
        for (String candidate : new String[] {n.filter(), n.indexCond(), n.hashCond(), n.mergeCond(), n.joinFilter()}) {
            if (candidate != null) {
                return candidate.length() > MAX_DETAIL_LENGTH ? candidate.substring(0, MAX_DETAIL_LENGTH) + "..." : candidate;
            }
        }
        return n.sortKeys().isEmpty() ? null : "Sort Key: " + String.join(", ", n.sortKeys());
    }

    private static Optional<Finding> seqScanOnLargeTable(PlanNode n, Map<String, Long> tableRows) {
        if (!n.isSeqScan() || n.neverExecuted()) {
            return Optional.empty();
        }
        Long known = tableRows.get(n.qualifiedRelation());
        double rows = known != null && known >= 0
                ? known
                : n.planRows() + (n.rowsRemovedByFilter() == null ? 0 : n.rowsRemovedByFilter());
        if (rows < LARGE_TABLE_ROWS) {
            return Optional.empty();
        }
        StringBuilder message = new StringBuilder("Sequential scan reads all ~%s rows of %s"
                .formatted(human(rows), n.qualifiedRelation()));
        if (n.filter() != null) {
            message.append(" to apply filter ").append(n.filter());
            if (n.rowsRemovedByFilter() != null && n.actualRows() != null) {
                message.append("; kept %s rows per loop, discarded %s"
                        .formatted(human(n.actualRows()), human(n.rowsRemovedByFilter())));
            }
        }
        boolean selective = n.filter() != null && n.planRows() < rows * 0.05;
        String suggestion;
        if (n.filter() == null) {
            suggestion = "The whole table is read. If the query needs only some rows, add a WHERE clause or LIMIT;"
                    + " otherwise this may be expected.";
        } else if (selective) {
            suggestion = "The filter keeps few rows, so an index on the filtered columns should help."
                    + " Run suggest_indexes on this query.";
        } else {
            suggestion = "The filter keeps a large share of the table, so an index may not beat the scan."
                    + " Check suggest_indexes before adding one.";
        }
        return Optional.of(new Finding(selective ? Severity.HIGH : Severity.MEDIUM, FindingType.SEQ_SCAN_ON_LARGE_TABLE,
                n.label(), message.toString(), suggestion));
    }

    private static Optional<Finding> rowEstimateMismatch(PlanNode n) {
        if (n.actualRows() == null || n.neverExecuted()) {
            return Optional.empty();
        }
        double estimated = n.planRows();
        double actual = n.actualRows();
        double high = Math.max(estimated, actual);
        double low = Math.max(Math.min(estimated, actual), 1);
        if (high < MISESTIMATE_MIN_ROWS || high / low < MISESTIMATE_FACTOR) {
            return Optional.empty();
        }
        String direction = actual > estimated ? "underestimated" : "overestimated";
        String table = n.qualifiedRelation();
        String suggestion = table != null
                ? "Statistics for " + table + " may be stale or too coarse: run ANALYZE " + table
                        + ", or raise the column statistics target / add extended statistics for correlated columns."
                : "Misestimates propagate upward from the inputs of this node; fix the lowest mismatched node first.";
        return Optional.of(new Finding(Severity.MEDIUM, FindingType.ROW_ESTIMATE_MISMATCH, n.label(),
                "Planner %s rows by %.0fx: expected %s, got %s per loop."
                        .formatted(direction, high / low, human(estimated), human(actual)),
                suggestion));
    }

    private static Optional<Finding> sortSpill(PlanNode n) {
        if (!"Disk".equalsIgnoreCase(n.sortSpaceType())) {
            return Optional.empty();
        }
        return Optional.of(new Finding(Severity.HIGH, FindingType.SORT_SPILLED_TO_DISK, n.label(),
                "Sort on %s spilled %s kB to disk.".formatted(String.join(", ", n.sortKeys()),
                        n.sortSpaceUsedKb() == null ? "?" : n.sortSpaceUsedKb()),
                "An index matching the sort keys avoids the sort entirely (especially with LIMIT)."
                        + " Otherwise raise work_mem for this query."));
    }

    private static Optional<Finding> hashSpill(PlanNode n) {
        if (n.hashBatches() == null || n.hashBatches() <= 1) {
            return Optional.empty();
        }
        return Optional.of(new Finding(Severity.MEDIUM, FindingType.HASH_SPILLED_TO_DISK, n.label(),
                "Hash table needed %d batches, so it spilled to disk.".formatted(n.hashBatches()),
                "Filter the hashed (inner) side earlier, or raise work_mem / hash_mem_multiplier."));
    }

    private static Optional<Finding> nestedLoopHighLoops(PlanNode n) {
        if (!"Nested Loop".equals(n.nodeType()) || n.children().size() < 2) {
            return Optional.empty();
        }
        PlanNode outer = n.children().get(0);
        PlanNode inner = n.children().get(1);
        boolean measured = inner.actualLoops() != null;
        double loops = measured ? inner.actualLoops() : outer.planRows();
        if (loops < HIGH_LOOPS) {
            return Optional.empty();
        }
        boolean innerIsScan = inner.isSeqScan();
        String message = "%s: inner side (%s) %s %s times.".formatted(n.label(), inner.label(),
                measured ? "ran" : "is expected to run", human(loops));
        String suggestion = innerIsScan
                ? "Each loop scans " + inner.qualifiedRelation() + " sequentially; index the join key on that table."
                : "Fine if each inner lookup is a cheap index probe. If it is slow, check the join key index"
                        + " or whether a hash join would be cheaper.";
        return Optional.of(new Finding(innerIsScan ? Severity.HIGH : Severity.LOW, FindingType.NESTED_LOOP_HIGH_LOOPS,
                n.label(), message, suggestion));
    }

    static String human(double n) {
        if (n >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", n / 1_000_000);
        }
        if (n >= 10_000) {
            return String.format(Locale.ROOT, "%.0fk", n / 1_000);
        }
        return String.format(Locale.ROOT, "%.0f", n);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
