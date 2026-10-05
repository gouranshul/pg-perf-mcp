package io.github.gouranshul.pgperf.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for Spring integration tests. The application connects as {@code mcp_readonly},
 * exactly as in production, never as the container superuser.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresIntegrationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DemoDatabase.get().getJdbcUrl());
        registry.add("spring.datasource.username", () -> DemoDatabase.READONLY_USER);
        registry.add("spring.datasource.password", () -> DemoDatabase.READONLY_PASSWORD);
    }
}
