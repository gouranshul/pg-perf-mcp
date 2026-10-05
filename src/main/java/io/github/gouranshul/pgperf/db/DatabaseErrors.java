package io.github.gouranshul.pgperf.db;

import java.sql.SQLException;
import java.time.Duration;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * Converts database exceptions into messages that are safe to return to an MCP client: the
 * server's primary error text where it describes the query, never host names, ports, user names,
 * stack traces or driver internals.
 */
public final class DatabaseErrors {

    private DatabaseErrors() {
    }

    public static String describe(RuntimeException e, Duration statementTimeout) {
        if (e instanceof CannotGetJdbcConnectionException) {
            return "The database is unavailable right now. Try again shortly.";
        }
        SQLException sql = findSqlException(e);
        if (sql == null) {
            return "The database call failed.";
        }
        String state = sql.getSQLState() == null ? "" : sql.getSQLState();
        String primary = primaryMessage(sql);
        if (state.equals("57014")) {
            return "Query cancelled: it ran longer than the %ss statement timeout. Try explain_query with analyze=false,"
                    .formatted(statementTimeout.toSeconds())
                    + " which plans the query without running it.";
        }
        if (state.equals("55P03")) {
            return "Query cancelled: it waited too long for a lock held by another session (see blocking_sessions).";
        }
        if (state.equals("25006")) {
            return "Blocked: this server only allows read-only queries.";
        }
        if (state.startsWith("08") || state.startsWith("28") || state.startsWith("53") || state.startsWith("57P")) {
            return "The database is unavailable right now. Try again shortly.";
        }
        if (primary != null && describesTheQuery(state)) {
            return "Database error (SQLSTATE %s): %s".formatted(state, primary);
        }
        return "Database error (SQLSTATE %s).".formatted(state);
    }

    /**
     * Only some error classes describe the query itself (syntax, unknown objects, privileges,
     * missing prerequisites such as an extension). Others can echo table data: with
     * {@code analyze=true}, {@code CAST(email AS int)} fails with "invalid input syntax for type
     * integer: <the email>". Those keep only their SQLSTATE.
     */
    static boolean describesTheQuery(String state) {
        return state.startsWith("42") // syntax error or access rule violation
                || state.startsWith("0A") // feature not supported
                || state.startsWith("3D") // invalid catalog name
                || state.startsWith("3F") // invalid schema name
                || state.startsWith("55"); // object not in prerequisite state
    }

    private static SQLException findSqlException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    /** The server's one-line primary message, without DETAIL/HINT/WHERE or connection info. */
    private static String primaryMessage(SQLException e) {
        if (e instanceof PSQLException psql) {
            ServerErrorMessage server = psql.getServerErrorMessage();
            if (server != null && server.getMessage() != null) {
                String message = server.getMessage();
                return message.length() > 500 ? message.substring(0, 500) + "..." : message;
            }
        }
        return null;
    }
}
