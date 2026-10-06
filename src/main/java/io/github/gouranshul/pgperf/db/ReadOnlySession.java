package io.github.gouranshul.pgperf.db;

import java.sql.ResultSet;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.QueryExecutor;
import org.postgresql.util.PSQLWarning;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCallback;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Database access handed to tool code inside a {@link ReadOnlyExecutor} transaction.
 *
 * <p>{@link #client()} is for the server's own, parameterized catalog queries.
 * {@link #queryForString(String)} is for SQL that embeds client text (already validated by the
 * guard): it uses a plain JDBC {@code Statement}, so characters such as {@code ?} in the client's
 * SQL are never mistaken for bind placeholders.
 */
public final class ReadOnlySession {

    private final JdbcClient client;
    private final JdbcTemplate template;

    ReadOnlySession(JdbcClient client, JdbcTemplate template) {
        this.client = client;
        this.template = template;
    }

    public JdbcClient client() {
        return client;
    }

    /** Runs {@code sql} as a plain statement and returns the first column of the first row. */
    public String queryForString(String sql) {
        List<String> rows = template.query(sql, (rs, n) -> rs.getString(1));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * The first column of the first row, plus the text of every notice the server sent while
     * the statement ran (for example plans from {@code auto_explain} with {@code log_level=notice}).
     *
     * @param value   first column of the first row, or null
     * @param notices primary message of each notice, in arrival order
     */
    public record ResultWithNotices(String value, List<String> notices) {
    }

    /** Like {@link #queryForString(String)}, and also collects the notices the statement raised. */
    public ResultWithNotices queryForStringWithNotices(String sql) {
        return template.execute((StatementCallback<ResultWithNotices>) statement -> {
            String value;
            try (ResultSet rs = statement.executeQuery(sql)) {
                value = rs.next() ? rs.getString(1) : null;
            }
            List<String> notices = new ArrayList<>();
            for (SQLWarning w = statement.getWarnings(); w != null; w = w.getNextWarning()) {
                ServerErrorMessage server = w instanceof PSQLWarning pw ? pw.getServerErrorMessage() : null;
                notices.add(server != null ? server.getMessage() : w.getMessage());
            }
            return new ResultWithNotices(value, List.copyOf(notices));
        });
    }

    /**
     * Like {@link #queryForString(String)}, but sent over the simple query protocol. Needed for
     * {@code EXPLAIN (GENERIC_PLAN)}: over the extended protocol the server counts the {@code $n}
     * placeholders in the text and rejects the bind message that carries no values. Only use it for
     * SQL the guard has proven to be a single statement, since the simple protocol would also accept
     * several.
     */
    public String queryForStringSimpleProtocol(String sql) {
        return template.execute((StatementCallback<String>) statement -> {
            statement.unwrap(BaseStatement.class).executeWithFlags(sql, QueryExecutor.QUERY_EXECUTE_AS_SIMPLE);
            try (ResultSet rs = statement.getResultSet()) {
                return rs != null && rs.next() ? rs.getString(1) : null;
            }
        });
    }
}
