package io.github.gouranshul.pgperf.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Application settings under {@code pgperf.*}.
 *
 * @param guard limits applied by the SQL guard
 * @param query limits applied to every database call made by a tool
 */
@ConfigurationProperties("pgperf")
public record PgPerfProperties(@DefaultValue Guard guard, @DefaultValue Query query) {

    /** @param maxSqlLength longest SQL text accepted from a client, in characters */
    public record Guard(@DefaultValue("20000") int maxSqlLength) {

        public Guard {
            if (maxSqlLength <= 0) {
                throw new IllegalArgumentException("pgperf.guard.max-sql-length must be positive");
            }
        }
    }

    /**
     * @param statementTimeout server-side {@code statement_timeout} applied to each tool call
     * @param maxRows          hard cap on rows returned by any single query
     */
    public record Query(@DefaultValue("5s") Duration statementTimeout, @DefaultValue("200") int maxRows) {

        public Query {
            if (statementTimeout.isNegative() || statementTimeout.isZero()) {
                throw new IllegalArgumentException("pgperf.query.statement-timeout must be positive");
            }
            if (maxRows <= 0) {
                throw new IllegalArgumentException("pgperf.query.max-rows must be positive");
            }
        }
    }
}
