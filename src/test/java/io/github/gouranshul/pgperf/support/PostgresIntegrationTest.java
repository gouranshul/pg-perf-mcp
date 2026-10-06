package io.github.gouranshul.pgperf.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for Spring integration tests. The application connects as {@code mcp_readonly},
 * exactly as in production, never as the container superuser. The pool has a single connection so
 * that any per-session state leaking between tool calls would be caught by the tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresIntegrationTest {

    /** Random per run, so no key-shaped literal lives in the repository. */
    protected static final String TEST_API_KEY = "test-" + UUID.randomUUID();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DemoDatabase.get().getJdbcUrl());
        registry.add("spring.datasource.username", () -> DemoDatabase.READONLY_USER);
        registry.add("spring.datasource.password", () -> DemoDatabase.READONLY_PASSWORD);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        registry.add("pgperf.query.statement-timeout", () -> "3s");
        // The test seed is tiny, so statements inside functions finish in well under 1 ms.
        registry.add("pgperf.functions.nested-plan-threshold", () -> "0ms");
        registry.add("pgperf.security.api-key", () -> TEST_API_KEY);
    }

    /** Runs statements as the superuser, e.g. to generate pg_stat_statements entries. */
    protected static void asAdmin(String... sql) {
        try (Connection connection = DemoDatabase.adminConnection(); Statement statement = connection.createStatement()) {
            for (String s : sql) {
                statement.execute(s);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
