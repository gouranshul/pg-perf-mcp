package io.github.gouranshul.pgperf.analysis;

import io.github.gouranshul.pgperf.analysis.FunctionAnalysis.FunctionCall;
import io.github.gouranshul.pgperf.analysis.FunctionAnalysis.FunctionSummary;
import io.github.gouranshul.pgperf.analysis.FunctionAnalysis.NestedStatement;
import io.github.gouranshul.pgperf.analysis.FunctionAnalysis.StatementStats;
import io.github.gouranshul.pgperf.analysis.PlanAnalysis.Finding;
import io.github.gouranshul.pgperf.analysis.PlanAnalysis.FindingType;
import io.github.gouranshul.pgperf.analysis.PlanAnalysis.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explains where a query's time goes when it calls database functions. EXPLAIN of the calling
 * query shows a function call as one opaque expression; this combines the per-function counters,
 * the counters of every statement executed inside the functions and the plans captured for those
 * statements, and reports the problems found at both levels.
 *
 * <p>Pure: no database access, so every rule is unit-testable.
 */
public final class FunctionAnalyzer {

    /** Share of execution time spent in one function that makes it the main problem. */
    static final double DOMINATES_HIGH_PERCENT = 50;
    static final double DOMINATES_MEDIUM_PERCENT = 20;
    /** Calls from a single query that indicate a per-row call. */
    static final long PER_ROW_CALLS = 10;
    public static final int MAX_DEFINITION_LENGTH = 4000;

    private static final Set<String> SQL_LANGUAGES = Set.of("sql", "plpgsql");
    private static final Pattern WORD = Pattern.compile("[a-z_][a-z0-9_$]*");

    private final PlanAnalyzer planAnalyzer = new PlanAnalyzer();

    /**
     * @param outer     EXPLAIN ANALYZE of the calling query
     * @param functions per-function counters for this execution
     * @param stats     per-statement counters for statements run inside functions (may be empty)
     * @param plans     captured plans of those statements (may be empty)
     * @param tableRows estimated rows per schema-qualified table, as for {@link PlanAnalyzer}
     */
    public FunctionAnalysis analyze(ExplainResult outer, List<FunctionCall> functions, List<StatementStats> stats,
            List<NestedPlan> plans, Map<String, Long> tableRows) {
        Double executionMs = outer.executionTimeMs();
        List<Finding> findings = new ArrayList<>();

        List<FunctionSummary> summaries = functions.stream()
                .sorted(Comparator.comparingDouble(FunctionCall::totalTimeMs).reversed())
                .map(f -> summarize(f, executionMs))
                .toList();
        for (FunctionSummary f : summaries) {
            dominatesQuery(f).ifPresent(findings::add);
            calledPerRow(f).ifPresent(findings::add);
            volatileButOnlyReads(f).ifPresent(findings::add);
        }

        // Keep the slowest captured execution of each statement: that is the plan worth showing.
        Map<Object, NestedPlan> slowest = new LinkedHashMap<>();
        for (NestedPlan p : plans) {
            slowest.merge(planKey(p), p, (a, b) -> a.durationMs() >= b.durationMs() ? a : b);
        }

        List<NestedStatement> statements = new ArrayList<>();
        for (StatementStats s : stats) {
            NestedPlan plan = slowest.remove(s.queryId());
            statements.add(nested(s.queryId(), plan != null ? plan.queryText() : s.query(), s.calls(),
                    s.totalTimeMs(), plan, functions, executionMs, tableRows, findings));
        }
        for (NestedPlan p : slowest.values()) {
            statements.add(nested(p.queryId() == 0 ? null : p.queryId(), p.queryText(), null, null, p, functions,
                    executionMs, tableRows, findings));
        }
        statements.sort(Comparator
                .comparing((NestedStatement s) -> s.totalTimeMs() == null ? -1 : s.totalTimeMs())
                .thenComparing(s -> s.capturedPlanMs() == null ? -1 : s.capturedPlanMs())
                .reversed());
        findings.sort(Comparator.comparing(Finding::severity));

        return new FunctionAnalysis(executionMs, planAnalyzer.analyze(outer, tableRows), summaries,
                List.copyOf(statements), List.copyOf(findings));
    }

    private NestedStatement nested(Long queryId, String text, Long calls, Double totalMs, NestedPlan plan,
            List<FunctionCall> functions, Double executionMs, Map<String, Long> tableRows, List<Finding> findings) {
        String function = attribute(text, functions);
        PlanAnalysis analysis = plan == null ? null : planAnalyzer.analyze(plan.plan(), tableRows);
        if (analysis != null) {
            String where = function != null ? function : "a function";
            for (Finding f : analysis.findings()) {
                if (f.severity() != Severity.LOW) {
                    findings.add(new Finding(f.severity(), f.type(), "inside " + where + ": " + f.node(),
                            f.message(), f.suggestion()));
                }
            }
        }
        Double mean = calls == null || calls == 0 ? null : round(totalMs / calls);
        return new NestedStatement(queryId, oneLine(text), function, calls, totalMs == null ? null : round(totalMs),
                mean, percent(totalMs, executionMs), plan == null ? null : round(plan.durationMs()), analysis);
    }

    private static FunctionSummary summarize(FunctionCall f, Double executionMs) {
        String definition = f.definition() == null || f.definition().length() <= MAX_DEFINITION_LENGTH
                ? f.definition()
                : f.definition().substring(0, MAX_DEFINITION_LENGTH - 1) + "…";
        double mean = f.calls() == 0 ? 0 : f.totalTimeMs() / f.calls();
        return new FunctionSummary(f.name(), f.arguments(), f.language(), volatility(f.volatility()), f.calls(),
                round(f.totalTimeMs()), round(f.selfTimeMs()), round(mean), percent(f.totalTimeMs(), executionMs),
                definition);
    }

    private static Optional<Finding> dominatesQuery(FunctionSummary f) {
        Double pct = f.percentOfExecution();
        if (pct == null || pct < DOMINATES_MEDIUM_PERCENT) {
            return Optional.empty();
        }
        return Optional.of(new Finding(
                pct >= DOMINATES_HIGH_PERCENT ? Severity.HIGH : Severity.MEDIUM,
                FindingType.FUNCTION_DOMINATES_QUERY,
                f.name(),
                "%s takes %.0f%% of the query's execution time (%d call%s, %.2f ms in total)."
                        .formatted(f.name(), pct, f.calls(), f.calls() == 1 ? "" : "s", f.totalTimeMs()),
                "The cost is inside the function, not in the query's own plan. See the statements listed for it "
                        + "and their findings."));
    }

    private static Optional<Finding> calledPerRow(FunctionSummary f) {
        if (f.calls() < PER_ROW_CALLS) {
            return Optional.empty();
        }
        boolean dominant = f.percentOfExecution() != null && f.percentOfExecution() >= DOMINATES_HIGH_PERCENT;
        return Optional.of(new Finding(
                dominant ? Severity.HIGH : Severity.MEDIUM,
                FindingType.FUNCTION_CALLED_PER_ROW,
                f.name(),
                "%s ran %d times for one query (%.3f ms per call): it is called once per row, and every call "
                        .formatted(f.name(), f.calls(), f.meanTimeMs())
                        + "runs the statements inside it again.",
                "Replace the per-row call with a join or one set-based query (for example GROUP BY over all "
                        + "rows), or make each call cheap by fixing the findings on its statements."));
    }

    private static Optional<Finding> volatileButOnlyReads(FunctionSummary f) {
        if (!"VOLATILE".equals(f.volatility()) || !SQL_LANGUAGES.contains(f.language())) {
            return Optional.empty();
        }
        // The call ran in a read-only transaction and succeeded, so it did not write anything.
        return Optional.of(new Finding(Severity.LOW, FindingType.VOLATILE_FUNCTION_ONLY_READS, f.name(),
                "%s is declared VOLATILE (the default when nothing is specified), but it only read data in this run."
                        .formatted(f.name()),
                "If it has no side effects and does not depend on volatile functions such as random(), "
                        + "clock_timestamp() or nextval(), declare it STABLE: the planner can then use a call to it "
                        + "as an index condition (WHERE id = f(x)), evaluated once per scan instead of per row."));
    }

    /**
     * Finds the function whose source contains the statement: the statement's words must appear
     * in the source in the same order (PL/pgSQL drops {@code INTO} targets from the executed text,
     * so an exact substring match would miss). When several match, the tightest match wins.
     */
    static String attribute(String statement, List<FunctionCall> functions) {
        if (statement == null || functions.isEmpty()) {
            return null;
        }
        List<String> needle = words(statement);
        String best = null;
        int bestSpan = Integer.MAX_VALUE;
        for (FunctionCall f : functions) {
            if (f.definition() == null) {
                continue;
            }
            int span = subsequenceSpan(needle, words(f.definition()));
            if (span >= 0 && span < bestSpan) {
                best = f.name();
                bestSpan = span;
            }
        }
        if (best == null && functions.size() == 1) {
            return functions.getFirst().name();
        }
        return best;
    }

    /** Length of the shortest window of {@code haystack} containing {@code needle} in order, or -1. */
    static int subsequenceSpan(List<String> needle, List<String> haystack) {
        if (needle.isEmpty()) {
            return -1;
        }
        int best = -1;
        for (int start = 0; start < haystack.size(); start++) {
            if (!haystack.get(start).equals(needle.getFirst())) {
                continue;
            }
            int i = 1;
            int j = start + 1;
            while (i < needle.size() && j < haystack.size()) {
                if (haystack.get(j).equals(needle.get(i))) {
                    i++;
                }
                j++;
            }
            if (i == needle.size()) {
                int span = j - start;
                if (best < 0 || span < best) {
                    best = span;
                }
            }
        }
        return best;
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            words.add(m.group());
        }
        return words;
    }

    private static Object planKey(NestedPlan p) {
        return p.queryId() != 0 ? (Object) p.queryId() : oneLine(p.queryText());
    }

    /** Spells out {@code pg_proc.provolatile}. */
    public static String volatility(String code) {
        return switch (code == null ? "" : code) {
            case "i" -> "IMMUTABLE";
            case "s" -> "STABLE";
            case "v" -> "VOLATILE";
            default -> code;
        };
    }

    private static Double percent(Double part, Double whole) {
        if (part == null || whole == null || whole <= 0) {
            return null;
        }
        return round(Math.min(100, 100 * part / whole));
    }

    private static String oneLine(String text) {
        return text == null ? null : text.strip().replaceAll("\\s+", " ");
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
