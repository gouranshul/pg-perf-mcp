package io.github.gouranshul.pgperf.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.jdbc.UncategorizedSQLException;

class DatabaseErrorsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void showsTheServerMessageForQueryProblems() {
        assertThat(describe("42P01", "relation \"shop.nope\" does not exist"))
                .isEqualTo("Database error (SQLSTATE 42P01): relation \"shop.nope\" does not exist");
        assertThat(describe("55000", "pg_stat_statements must be loaded via shared_preload_libraries"))
                .contains("shared_preload_libraries");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "22P02|invalid input syntax for type integer: \"someone@example.com\"",
            "23505|duplicate key value violates unique constraint",
            "P0001|custom error raised with secret row data",
            "XX000|internal error with data",
    })
    void hidesMessagesThatCanContainRowData(String state, String message) {
        assertThat(describe(state, message)).isEqualTo("Database error (SQLSTATE " + state + ").");
    }

    @ParameterizedTest
    @CsvSource({"08006", "08001", "28P01", "53300", "57P01"})
    void connectionAndServerProblemsRevealNothing(String state) {
        assertThat(describe(state, "connection to host db.internal port 5432 failed for user mcp_readonly"))
                .isEqualTo("The database is unavailable right now. Try again shortly.");
    }

    @Test
    void timeoutsAndLockWaitsAreExplained() {
        assertThat(describe("57014", "canceling statement due to statement timeout"))
                .contains("5s statement timeout", "analyze=false");
        assertThat(describe("55P03", "canceling statement due to lock timeout")).contains("blocking_sessions");
        assertThat(describe("25006", "cannot execute UPDATE in a read-only transaction"))
                .isEqualTo("Blocked: this server only allows read-only queries.");
    }

    @Test
    void nonPostgresExceptionsGetOnlyTheSqlState() {
        var e = new UncategorizedSQLException("task", "SELECT 1", new SQLException("driver said x", "HY000"));
        assertThat(DatabaseErrors.describe(e, TIMEOUT)).isEqualTo("Database error (SQLSTATE HY000).");
    }

    private static String describe(String state, String message) {
        ServerErrorMessage server = new ServerErrorMessage("SERROR\0C" + state + "\0M" + message + "\0\0");
        PSQLException psql = new PSQLException(server);
        return DatabaseErrors.describe(new UncategorizedSQLException("task", "SELECT 1", psql), TIMEOUT);
    }
}
