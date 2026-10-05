package io.github.gouranshul.pgperf.config;

import io.github.gouranshul.pgperf.guard.SqlGuard;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Configuration(proxyBeanMethods = false)
public class DatabaseConfig {

    /**
     * Read-only transactions are enforced on the server with {@code SET TRANSACTION READ ONLY},
     * not just flagged on the JDBC connection.
     */
    @Bean
    JdbcTransactionManager transactionManager(DataSource dataSource) {
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);
        manager.setEnforceReadOnly(true);
        return manager;
    }

    @Bean
    SqlGuard sqlGuard(PgPerfProperties properties) {
        return new SqlGuard(properties.guard().maxSqlLength());
    }
}
