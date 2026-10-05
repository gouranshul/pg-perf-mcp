package io.github.gouranshul.pgperf.db;

import io.github.gouranshul.pgperf.config.PgPerfProperties;
import java.time.Duration;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only way tools talk to the database. Every call:
 * <ul>
 *   <li>runs in a {@code READ ONLY} transaction that is <b>always rolled back</b>, even on success;</li>
 *   <li>sets {@code statement_timeout} and {@code lock_timeout} for that transaction only
 *       ({@code set_config(..., is_local => true)});</li>
 *   <li>forces {@code standard_conforming_strings = on} so Postgres reads string literals exactly
 *       as the SQL guard's lexer does;</li>
 *   <li>caps every result set at {@code pgperf.query.max-rows} rows via JDBC {@code maxRows}.</li>
 * </ul>
 * Thread safe: all state is immutable after construction.
 */
@Component
public class ReadOnlyExecutor {

    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final Duration statementTimeout;
    private final int maxRows;

    public ReadOnlyExecutor(DataSource dataSource, PlatformTransactionManager transactionManager,
            PgPerfProperties properties) {
        this.statementTimeout = properties.query().statementTimeout();
        this.maxRows = properties.query().maxRows();

        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setMaxRows(maxRows);
        // Client-side safety net in case the server-side timeout is somehow not applied.
        template.setQueryTimeout((int) statementTimeout.plusSeconds(2).toSeconds());
        this.jdbc = JdbcClient.create(template);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transactions = tx;
    }

    /** Runs {@code work} with the default statement timeout. */
    public <T> T run(Function<JdbcClient, T> work) {
        return run(statementTimeout, work);
    }

    /** Runs {@code work} with a specific timeout, never longer than the configured default. */
    public <T> T run(Duration timeout, Function<JdbcClient, T> work) {
        Duration effective = timeout.compareTo(statementTimeout) > 0 ? statementTimeout : timeout;
        String millis = effective.toMillis() + "ms";
        return transactions.execute(status -> {
            status.setRollbackOnly();
            jdbc.sql("SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true),"
                            + " set_config('standard_conforming_strings', 'on', true)")
                    .params(millis, millis)
                    .query((rs, n) -> null)
                    .list();
            return work.apply(jdbc);
        });
    }

    public int maxRows() {
        return maxRows;
    }

    public Duration statementTimeout() {
        return statementTimeout;
    }
}
