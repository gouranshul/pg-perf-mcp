package io.github.gouranshul.pgperf.db;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
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
}
