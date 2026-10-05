package io.github.gouranshul.pgperf.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gouranshul.pgperf.support.PostgresIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;

/**
 * Proves the defense-in-depth layers below the SQL guard: even SQL that never went through the
 * guard cannot write, cannot run long, and cannot return unbounded rows.
 */
class ReadOnlyExecutorIT extends PostgresIntegrationTest {

    @Autowired
    ReadOnlyExecutor executor;

    @Test
    void transactionIsReadOnly() {
        String readOnly = executor.run(db -> db.client().sql("SHOW transaction_read_only").query(String.class).single());
        assertThat(readOnly).isEqualTo("on");
    }

    @Test
    void writesFailEvenWithoutTheGuard() {
        assertThatThrownBy(() -> executor.run(db ->
                db.client().sql("UPDATE shop.products SET price = 0 WHERE id = 1").update()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("read-only transaction");
    }

    @Test
    void ddlFailsEvenWithoutTheGuard() {
        assertThatThrownBy(() -> executor.run(db ->
                db.client().sql("CREATE INDEX ON shop.orders (customer_id)").update()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void roleCannotWriteOutsideATransactionEither() throws Exception {
        try (var connection = io.github.gouranshul.pgperf.support.DemoDatabase.readonlyConnection();
                var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("DELETE FROM shop.reviews WHERE id = 1"))
                    .hasMessageContaining("read-only transaction");
        }
    }

    @Test
    void statementTimeoutIsApplied() {
        assertThatThrownBy(() -> executor.run(Duration.ofMillis(300), db ->
                db.client().sql("SELECT pg_sleep(3)").query(String.class).single()))
                .isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    void timeoutCannotExceedConfiguredDefault() {
        String timeout = executor.run(Duration.ofHours(1), db ->
                db.client().sql("SHOW statement_timeout").query(String.class).single());
        assertThat(timeout).isEqualTo(executor.statementTimeout().toSeconds() + "s");
    }

    @Test
    void resultsAreCappedAtMaxRows() {
        int rows = executor.run(db -> db.client().sql("SELECT g FROM generate_series(1, 10000) g")
                .query(Integer.class).list().size());
        assertThat(rows).isEqualTo(executor.maxRows());
    }

    @Test
    void settingsDoNotLeakIntoTheNextCall() {
        executor.run(Duration.ofMillis(250), db -> db.client().sql("SELECT 1").query(Integer.class).single());
        String timeout = executor.run(db -> db.client().sql("SHOW statement_timeout").query(String.class).single());
        assertThat(timeout).isEqualTo(executor.statementTimeout().toSeconds() + "s");
    }
}
