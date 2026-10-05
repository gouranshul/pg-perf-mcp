package io.github.gouranshul.pgperf.guard;

import io.github.gouranshul.pgperf.guard.PgLexer.Token;
import io.github.gouranshul.pgperf.guard.PgLexer.Type;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

/**
 * The first line of defense: decides whether user-supplied SQL may be sent to the database.
 *
 * <p>Allows exactly one plain {@code SELECT} (CTEs only if every part is a SELECT). The SQL is
 * checked twice, by two independent readers:
 * <ol>
 *   <li>{@link PgLexer}, which tokenizes exactly as PostgreSQL does, counts statements, finds
 *       function calls and catches write keywords;</li>
 *   <li>JSqlParser, which builds an AST to verify the statement type, CTE bodies, {@code INTO}
 *       and locking clauses at every nesting level.</li>
 * </ol>
 * Both must agree. Anything either one cannot understand is rejected: the guard fails closed.
 *
 * <p>This class is stateless and thread safe.
 */
public final class SqlGuard {

    /**
     * Statement keywords that never appear in a read-only SELECT. Checked on unquoted identifiers
     * only, as a backstop to the AST check. A column that really has one of these names can still
     * be queried by double-quoting it.
     */
    private static final Set<String> WRITE_KEYWORDS = Set.of(
            "insert", "update", "delete", "merge", "truncate", "drop", "alter", "create", "grant",
            "revoke", "copy", "do", "call", "vacuum", "into", "lock", "execute", "prepare", "listen",
            "notify", "refresh", "reindex", "cluster", "checkpoint", "set", "reset", "discard",
            "deallocate", "import", "load");

    private final int maxLength;

    public SqlGuard(int maxLength) {
        this.maxLength = maxLength;
    }

    /**
     * Validates {@code sql} and returns the normalized statement to execute.
     *
     * @throws SqlRejectedException with a human-readable reason if the SQL is not allowed
     */
    public ValidatedSql validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new SqlRejectedException("SQL is empty");
        }
        if (sql.length() > maxLength) {
            throw new SqlRejectedException(
                    "SQL is too long (%d characters, limit is %d)".formatted(sql.length(), maxLength));
        }
        if (sql.indexOf('\0') >= 0) {
            throw new SqlRejectedException("SQL contains a NUL character");
        }

        List<Token> tokens = PgLexer.tokenize(sql);
        List<Token> body = singleStatement(tokens);
        String statementSql = sql.substring(body.getFirst().start(), body.getLast().end());

        Select select = parseSelect(statementSql);
        new SelectSafetyVisitor().check(select);
        rejectDangerousFunctions(body);
        rejectWriteKeywords(body);

        return new ValidatedSql(statementSql, maxParameterIndex(body));
    }

    /** Returns the tokens of the only statement, allowing nothing but semicolons after it. */
    private static List<Token> singleStatement(List<Token> tokens) {
        int end = 0;
        while (end < tokens.size() && tokens.get(end).type() != Type.SEMICOLON) {
            end++;
        }
        for (int i = end; i < tokens.size(); i++) {
            if (tokens.get(i).type() != Type.SEMICOLON) {
                throw new SqlRejectedException("Only a single statement is allowed (found more after a ';')");
            }
        }
        if (end == 0) {
            throw new SqlRejectedException("SQL is empty (it contains only comments or semicolons)");
        }
        return tokens.subList(0, end);
    }

    private static Select parseSelect(String sql) {
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException | RuntimeException e) {
            throw new SqlRejectedException(
                    "SQL could not be parsed; only standard PostgreSQL SELECT syntax is supported");
        }
        if (!(statement instanceof Select select)) {
            throw new SqlRejectedException("Only SELECT statements are allowed (got "
                    + statement.getClass().getSimpleName().toUpperCase(Locale.ROOT) + ")");
        }
        return select;
    }

    /** A function call is an identifier (possibly schema-qualified) followed by '('. */
    private static void rejectDangerousFunctions(List<Token> tokens) {
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.isIdentifier() && tokens.get(i + 1).type() == Type.LEFT_PAREN) {
                String name = token.value().toLowerCase(Locale.ROOT);
                DangerousFunctions.reasonFor(name).ifPresent(why -> {
                    throw new SqlRejectedException("Function %s() is not allowed: %s".formatted(name, why));
                });
            }
        }
    }

    private static void rejectWriteKeywords(List<Token> tokens) {
        for (Token token : tokens) {
            if (token.type() == Type.IDENTIFIER && WRITE_KEYWORDS.contains(token.value())) {
                throw new SqlRejectedException(("Keyword %s is not allowed in a read-only query"
                        + " (double-quote it if it is a column name)").formatted(token.value().toUpperCase(Locale.ROOT)));
            }
        }
    }

    private static int maxParameterIndex(List<Token> tokens) {
        int max = 0;
        for (Token token : tokens) {
            if (token.type() == Type.PARAMETER) {
                if (token.value().length() > 4) {
                    throw new SqlRejectedException("Parameter $" + token.value() + " is out of range");
                }
                max = Math.max(max, Integer.parseInt(token.value()));
            }
        }
        return max;
    }
}
