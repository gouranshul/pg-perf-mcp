package io.github.gouranshul.pgperf.analysis;

import io.github.gouranshul.pgperf.analysis.IndexAdvice.Suggestion;
import io.github.gouranshul.pgperf.analysis.Predicates.Kind;
import io.github.gouranshul.pgperf.analysis.Predicates.Predicate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives candidate btree indexes from a query plan. Pure function: no I/O, thread safe.
 *
 * <p>Three heuristics, each tied to something visible in the plan:
 * <ol>
 *   <li><b>Filtered sequential scans</b> on large tables: equality columns first, then at most one
 *       range column (the classic "equality, then range" column order).</li>
 *   <li><b>Join keys</b> whose table is read by a sequential scan.</li>
 *   <li><b>ORDER BY ... LIMIT</b> that sorts a whole large table: an index in sort order lets
 *       Postgres stop after LIMIT rows.</li>
 * </ol>
 * Candidates already covered by an existing index (same leading columns) are dropped, and a
 * candidate that is a prefix of a wider one on the same table is merged into it.
 */
public final class IndexAdvisor {

    /** Filters that keep more than this share of the table rarely benefit from an index. */
    static final double MAX_SELECTIVITY = 0.2;
    private static final int MAX_IDENTIFIER_LENGTH = 63;
    private static final Pattern SORT_KEY = Pattern.compile(
            "^(?:([A-Za-z_][A-Za-z0-9_$]*)\\.)?([A-Za-z_][A-Za-z0-9_$]*)(\\s+DESC)?(\\s+NULLS\\s+(?:FIRST|LAST))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_$]*$");

    public IndexAdvice advise(ExplainResult plan, Map<String, TableInfo> tables) {
        List<PlanNode> nodes = plan.root().flatten();
        Map<String, PlanNode> scansByAlias = new HashMap<>();
        for (PlanNode node : nodes) {
            if (node.relationName() != null) {
                scansByAlias.put(node.alias() != null ? node.alias() : node.relationName(), node);
            }
        }

        Map<String, Candidate> candidates = new LinkedHashMap<>();
        Set<String> notes = new LinkedHashSet<>();
        for (PlanNode node : nodes) {
            if (node.relationName() != null) {
                fromFilteredScan(node, tables, candidates, notes);
            }
            fromJoinKeys(node, scansByAlias, tables, candidates);
            if ("Limit".equals(node.nodeType())) {
                fromOrderByLimit(node, tables, candidates);
            }
        }

        List<Candidate> merged = mergePrefixes(new ArrayList<>(candidates.values()));
        List<Suggestion> suggestions = new ArrayList<>();
        for (Candidate c : merged) {
            Optional<TableInfo.ExistingIndex> covering = coveringIndex(c, tables.get(c.table));
            if (covering.isPresent()) {
                notes.add("Existing index %s on %s(%s) already covers %s; if the planner still avoids it, check"
                        .formatted(covering.get().name(), c.table, String.join(", ", covering.get().columns()),
                                String.join(", ", c.columns))
                        + " statistics (ANALYZE) and filter selectivity.");
            } else {
                suggestions.add(new Suggestion(c.table, List.copyOf(c.columns), statement(c), List.copyOf(c.reasons)));
            }
        }
        return new IndexAdvice(List.copyOf(suggestions), List.copyOf(notes));
    }

    // ---- heuristic 1: filtered sequential scans -----------------------------------------------

    private static void fromFilteredScan(PlanNode scan, Map<String, TableInfo> tables, Map<String, Candidate> out,
            Set<String> notes) {
        double rows = tableRows(scan, tables);
        if (!scan.isSeqScan() || scan.filter() == null || rows < PlanAnalyzer.LARGE_TABLE_ROWS) {
            return;
        }
        List<Predicate> own = ownPredicates(scan);
        for (Predicate p : own) {
            if (p.kind() == Kind.INFIX_MATCH) {
                notes.add(("LIKE/ILIKE with a leading wildcard on %s.%s cannot use a btree index. Consider"
                        + " CREATE EXTENSION pg_trgm; CREATE INDEX CONCURRENTLY ON %s USING gin (%s gin_trgm_ops);")
                        .formatted(scan.qualifiedRelation(), p.column(), scan.qualifiedRelation(), quote(p.column())));
            } else if (p.kind() == Kind.PREFIX_MATCH) {
                notes.add(("Prefix LIKE on %s.%s can use a btree index only with text_pattern_ops"
                        + " (or the C collation): CREATE INDEX CONCURRENTLY ON %s (%s text_pattern_ops);")
                        .formatted(scan.qualifiedRelation(), p.column(), scan.qualifiedRelation(), quote(p.column())));
            }
        }
        List<String> columns = equalityThenRange(own);
        if (columns.isEmpty()) {
            return;
        }
        if (scan.planRows() > rows * MAX_SELECTIVITY) {
            notes.add("Filter %s on %s keeps about %.0f%% of the rows; an index is unlikely to beat a sequential scan."
                    .formatted(scan.filter(), scan.qualifiedRelation(), 100 * scan.planRows() / rows));
            return;
        }
        add(out, scan.qualifiedRelation(), columns,
                "Seq Scan on %s (~%s rows) filters on %s but only ~%s rows match."
                        .formatted(scan.qualifiedRelation(), PlanAnalyzer.human(rows), String.join(", ", columns),
                                PlanAnalyzer.human(scan.planRows())));
    }

    private static List<String> equalityThenRange(List<Predicate> predicates) {
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        predicates.stream().filter(p -> p.kind() == Kind.EQUALITY).forEach(p -> columns.add(p.column()));
        predicates.stream().filter(p -> p.kind() == Kind.RANGE).map(Predicate::column)
                .filter(c -> !columns.contains(c)).findFirst().ifPresent(columns::add);
        return new ArrayList<>(columns);
    }

    /** Predicates on the scanned table itself (unqualified, or qualified with its alias/name). */
    private static List<Predicate> ownPredicates(PlanNode scan) {
        return Predicates.parse(scan.filter()).stream()
                .filter(p -> p.kind() != Kind.JOIN)
                .filter(p -> p.qualifier() == null || p.qualifier().equals(scan.alias())
                        || p.qualifier().equals(scan.relationName()))
                .toList();
    }

    // ---- heuristic 2: join keys ---------------------------------------------------------------

    private static void fromJoinKeys(PlanNode node, Map<String, PlanNode> scansByAlias, Map<String, TableInfo> tables,
            Map<String, Candidate> out) {
        for (String condition : new String[] {node.hashCond(), node.mergeCond(), node.joinFilter()}) {
            for (Predicate p : Predicates.parse(condition)) {
                if (p.kind() != Kind.JOIN) {
                    continue;
                }
                joinSide(p.qualifier(), p.column(), p.otherQualifier(), p.otherColumn(), scansByAlias, tables, out);
                joinSide(p.otherQualifier(), p.otherColumn(), p.qualifier(), p.column(), scansByAlias, tables, out);
            }
        }
    }

    private static void joinSide(String qualifier, String column, String otherQualifier, String otherColumn,
            Map<String, PlanNode> scansByAlias, Map<String, TableInfo> tables, Map<String, Candidate> out) {
        PlanNode scan = qualifier == null ? null : scansByAlias.get(qualifier);
        if (scan == null || !scan.isSeqScan()) {
            return;
        }
        double rows = tableRows(scan, tables);
        if (rows < PlanAnalyzer.LARGE_TABLE_ROWS) {
            return;
        }
        add(out, scan.qualifiedRelation(), List.of(column),
                "Join key %s.%s = %s.%s: %s (~%s rows) is read with a Seq Scan to find matching rows."
                        .formatted(qualifier, column, otherQualifier, otherColumn, scan.qualifiedRelation(),
                                PlanAnalyzer.human(rows)));
    }

    // ---- heuristic 3: ORDER BY ... LIMIT ------------------------------------------------------

    private static void fromOrderByLimit(PlanNode limit, Map<String, TableInfo> tables, Map<String, Candidate> out) {
        PlanNode sort = descend(limit, Set.of("Gather Merge", "Gather"));
        if (sort == null || !(sort.nodeType().equals("Sort") || sort.nodeType().equals("Incremental Sort"))) {
            return;
        }
        PlanNode scan = descend(sort, Set.of("Gather", "Gather Merge"));
        if (scan == null || !scan.isSeqScan() || tableRows(scan, tables) < PlanAnalyzer.LARGE_TABLE_ROWS) {
            return;
        }
        List<String> sortColumns = new ArrayList<>();
        for (String key : sort.sortKeys()) {
            Matcher m = SORT_KEY.matcher(key.strip());
            if (!m.matches() || (m.group(1) != null && !m.group(1).equals(scan.alias())
                    && !m.group(1).equals(scan.relationName()))) {
                return; // expression or another table's column: not a simple single-table index
            }
            sortColumns.add(m.group(2) + (m.group(3) != null ? " DESC" : ""));
        }
        List<String> columns = new ArrayList<>(equalityColumns(scan));
        sortColumns.stream().filter(c -> !columns.contains(baseColumn(c))).forEach(columns::add);
        add(out, scan.qualifiedRelation(), columns,
                ("ORDER BY %s with LIMIT sorts all ~%s rows of %s to return a few; an index in that order lets"
                        + " Postgres read only the first rows.")
                        .formatted(String.join(", ", sort.sortKeys()), PlanAnalyzer.human(tableRows(scan, tables)),
                                scan.qualifiedRelation()));
    }

    private static List<String> equalityColumns(PlanNode scan) {
        if (scan.filter() == null) {
            return List.of();
        }
        return ownPredicates(scan).stream().filter(p -> p.kind() == Kind.EQUALITY).map(Predicate::column)
                .distinct().toList();
    }

    /** Follows single-child pass-through nodes (e.g. Gather) down from {@code node}'s first child. */
    private static PlanNode descend(PlanNode node, Set<String> passThrough) {
        PlanNode current = node.children().isEmpty() ? null : node.children().getFirst();
        while (current != null && passThrough.contains(current.nodeType()) && !current.children().isEmpty()) {
            current = current.children().getFirst();
        }
        return current;
    }

    // ---- merging and output --------------------------------------------------------------------

    private static final class Candidate {
        final String table;
        final List<String> columns;
        final LinkedHashSet<String> reasons = new LinkedHashSet<>();

        Candidate(String table, List<String> columns) {
            this.table = table;
            this.columns = columns;
        }

        List<String> baseColumns() {
            return columns.stream().map(IndexAdvisor::baseColumn).toList();
        }
    }

    private static void add(Map<String, Candidate> out, String table, List<String> columns, String reason) {
        String key = (table + "|" + String.join(",", columns)).toLowerCase(Locale.ROOT);
        out.computeIfAbsent(key, k -> new Candidate(table, List.copyOf(columns))).reasons.add(reason);
    }

    /** Drops candidates whose columns are a leading prefix of a wider candidate on the same table. */
    private static List<Candidate> mergePrefixes(List<Candidate> candidates) {
        List<Candidate> result = new ArrayList<>();
        for (Candidate c : candidates) {
            Optional<Candidate> wider = candidates.stream()
                    .filter(o -> o != c && o.table.equals(c.table) && o.columns.size() > c.columns.size()
                            && o.baseColumns().subList(0, c.columns.size()).equals(c.baseColumns()))
                    .findFirst();
            if (wider.isPresent()) {
                wider.get().reasons.addAll(c.reasons);
            } else {
                result.add(c);
            }
        }
        return result;
    }

    private static Optional<TableInfo.ExistingIndex> coveringIndex(Candidate c, TableInfo table) {
        if (table == null) {
            return Optional.empty();
        }
        List<String> wanted = c.baseColumns();
        return table.indexes().stream()
                .filter(i -> i.columns().size() >= wanted.size() && i.columns().subList(0, wanted.size()).equals(wanted))
                .findFirst();
    }

    static String statement(Candidate c) {
        String[] parts = c.table.split("\\.", 2);
        String tableName = parts.length == 2 ? parts[1] : parts[0];
        String name = ("idx_" + tableName + "_" + String.join("_", c.baseColumns()))
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (name.length() > MAX_IDENTIFIER_LENGTH) {
            name = name.substring(0, MAX_IDENTIFIER_LENGTH);
        }
        String qualifiedTable = parts.length == 2 ? quote(parts[0]) + "." + quote(parts[1]) : quote(parts[0]);
        List<String> keys = c.columns.stream()
                .map(col -> col.endsWith(" DESC") ? quote(baseColumn(col)) + " DESC" : quote(col))
                .toList();
        return "CREATE INDEX CONCURRENTLY %s ON %s (%s);".formatted(name, qualifiedTable, String.join(", ", keys));
    }

    private static String baseColumn(String column) {
        return column.endsWith(" DESC") ? column.substring(0, column.length() - 5) : column;
    }

    static String quote(String identifier) {
        return SIMPLE_IDENTIFIER.matcher(identifier).matches()
                ? identifier
                : "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static double tableRows(PlanNode scan, Map<String, TableInfo> tables) {
        TableInfo info = tables.get(scan.qualifiedRelation());
        if (info != null && info.estimatedRows() >= 0) {
            return info.estimatedRows();
        }
        return scan.planRows() + (scan.rowsRemovedByFilter() == null ? 0 : scan.rowsRemovedByFilter());
    }
}
