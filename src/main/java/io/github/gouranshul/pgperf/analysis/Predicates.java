package io.github.gouranshul.pgperf.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the condition strings Postgres prints in plans ({@code Filter}, {@code Hash Cond},
 * {@code Join Filter}, ...). Postgres normalizes these into a predictable shape, e.g.
 * {@code ((o.status = 'pending'::text) AND (o.created_at > (now() - '30 days'::interval)))},
 * so a small purpose-built parser is more reliable here than a general SQL parser.
 */
public final class Predicates {

    /** How a column is compared, which decides its position in a candidate index. */
    public enum Kind {
        /** {@code =}, {@code = ANY(...)}, {@code IS NULL}: best as leading index columns. */
        EQUALITY,
        /** {@code <, <=, >, >=}: one range column can follow the equality columns. */
        RANGE,
        /** {@code LIKE 'abc%'}: btree-indexable only with text_pattern_ops or the C collation. */
        PREFIX_MATCH,
        /** {@code LIKE '%abc%'}: no btree index can help; needs pg_trgm. */
        INFIX_MATCH,
        /** {@code a.x = b.y}: a join key. */
        JOIN
    }

    /**
     * One comparison against a column.
     *
     * @param qualifier   table alias or name in front of the column, or null if unqualified
     * @param column      column name
     * @param kind        kind of comparison
     * @param otherQualifier for {@link Kind#JOIN}, the qualifier of the other side
     * @param otherColumn for {@link Kind#JOIN}, the column on the other side
     */
    public record Predicate(String qualifier, String column, Kind kind, String otherQualifier, String otherColumn) {
    }

    private static final String IDENT = "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)";
    private static final Pattern COLUMN_REF = Pattern.compile("^(?:(" + IDENT + ")\\.)?(" + IDENT + ")$");
    private static final Pattern COMPARISON = Pattern.compile(
            "^(.+?)\\s*(=\\s*ANY|<=|>=|<>|!=|=|<|>|!~~\\*|!~~|~~\\*|~~)\\s*(.+)$", Pattern.DOTALL);
    private static final Pattern NULL_TEST = Pattern.compile("^(.+?)\\s+IS\\s+(?:NOT\\s+)?NULL$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CAST_SUFFIX = Pattern.compile("::[A-Za-z_ \\[\\]\"]+(\\([0-9, ]+\\))?(\\[\\])?$");

    private Predicates() {
    }

    /** Splits a condition on top-level AND and returns the comparisons it can classify. */
    public static List<Predicate> parse(String condition) {
        List<Predicate> predicates = new ArrayList<>();
        if (condition == null || condition.isBlank()) {
            return predicates;
        }
        for (String conjunct : splitTopLevelAnd(stripOuterParens(condition.strip()))) {
            classify(stripOuterParens(conjunct.strip()), predicates);
        }
        return predicates;
    }

    private static void classify(String expr, List<Predicate> out) {
        Matcher nullTest = NULL_TEST.matcher(expr);
        if (nullTest.matches()) {
            columnRef(nullTest.group(1)).ifPresent(c -> out.add(new Predicate(c[0], c[1], Kind.EQUALITY, null, null)));
            return;
        }
        Matcher m = COMPARISON.matcher(expr);
        if (!m.matches() || containsTopLevelOr(expr)) {
            return;
        }
        String left = stripCast(stripOuterParens(m.group(1).strip()));
        String op = m.group(2).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        String right = stripCast(stripOuterParens(m.group(3).strip()));

        var leftCol = columnRef(left);
        var rightCol = columnRef(right);
        if (leftCol.isPresent() && rightCol.isPresent() && op.equals("=")) {
            out.add(new Predicate(leftCol.get()[0], leftCol.get()[1], Kind.JOIN, rightCol.get()[0], rightCol.get()[1]));
            return;
        }
        // Postgres usually prints "column op constant"; accept the mirrored form too.
        boolean mirrored = leftCol.isEmpty() && rightCol.isPresent();
        var col = mirrored ? rightCol : leftCol;
        String value = mirrored ? left : right;
        if (col.isEmpty()) {
            return;
        }
        Kind kind = switch (op) {
            case "=", "= ANY" -> Kind.EQUALITY;
            case "<", "<=", ">", ">=" -> Kind.RANGE;
            case "~~", "~~*" -> value.startsWith("'%") || value.startsWith("'_") ? Kind.INFIX_MATCH : Kind.PREFIX_MATCH;
            default -> null;
        };
        if (kind != null) {
            out.add(new Predicate(col.get()[0], col.get()[1], kind, null, null));
        }
    }

    /** Returns {qualifier, column} for a plain (optionally qualified) column reference. */
    private static java.util.Optional<String[]> columnRef(String text) {
        Matcher m = COLUMN_REF.matcher(text.strip());
        if (!m.matches() || isLiteralKeyword(m.group(2))) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new String[] {unquote(m.group(1)), unquote(m.group(2))});
    }

    private static boolean isLiteralKeyword(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.equals("true") || lower.equals("false") || lower.equals("null");
    }

    private static String unquote(String ident) {
        if (ident == null) {
            return null;
        }
        if (ident.startsWith("\"") && ident.endsWith("\"")) {
            return ident.substring(1, ident.length() - 1).replace("\"\"", "\"");
        }
        return ident;
    }

    private static String stripCast(String s) {
        String result = s;
        Matcher m = CAST_SUFFIX.matcher(result);
        while (m.find() && !inQuotes(result, m.start())) {
            result = stripOuterParens(result.substring(0, m.start()).strip());
            m = CAST_SUFFIX.matcher(result);
        }
        return result;
    }

    static String stripOuterParens(String s) {
        String result = s;
        while (result.startsWith("(") && result.endsWith(")") && matchingParen(result, 0) == result.length() - 1) {
            result = result.substring(1, result.length() - 1).strip();
        }
        return result;
    }

    static List<String> splitTopLevelAnd(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && s.regionMatches(true, i, " AND ", 0, 5)) {
                parts.add(s.substring(start, i));
                start = i + 5;
                i += 4;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private static boolean containsTopLevelOr(String s) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && s.regionMatches(true, i, " OR ", 0, 4)) {
                return true;
            }
        }
        return false;
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean inQuotes(String s, int position) {
        boolean quoted = false;
        for (int i = 0; i < position; i++) {
            if (s.charAt(i) == '\'') {
                quoted = !quoted;
            }
        }
        return quoted;
    }
}
