package io.github.gouranshul.pgperf.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gouranshul.pgperf.audit.AuditLogger;
import io.github.gouranshul.pgperf.audit.RowCounted;
import io.github.gouranshul.pgperf.config.PgPerfProperties;
import io.github.gouranshul.pgperf.guard.SqlRejectedException;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import tools.jackson.databind.json.JsonMapper;

class ToolRunnerTest {

    private SimpleMeterRegistry meters;
    private ToolRunner runner;

    record Rows(int rowCount) implements RowCounted {
    }

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        PgPerfProperties properties = new PgPerfProperties(new PgPerfProperties.Guard(1000),
                new PgPerfProperties.Query(Duration.ofSeconds(5), 200),
                new PgPerfProperties.Security("unit-test-key-0123456789"));
        JsonMapper json = JsonMapper.builder().build();
        runner = new ToolRunner(json, properties, new AuditLogger(json), observations, meters);
    }

    @Test
    void successIsSerializedAndTimed() {
        CallToolResult result = runner.run("demo", ToolRunner.args(), () -> new Rows(3));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(ToolResults.text(result)).isEqualTo("{\"rowCount\":3}");
        assertThat(meters.get("mcp.tool.duration").tag("tool", "demo").tag("outcome", "ok").timer().count())
                .isEqualTo(1);
    }

    @Test
    void guardRejectionsAreCountedAndExplained() {
        CallToolResult result = runner.run("explain_query", ToolRunner.args("sql", "DELETE FROM t"), () -> {
            throw new SqlRejectedException("Only SELECT statements are allowed (got DELETE)");
        });

        assertThat(result.isError()).isTrue();
        assertThat(ToolResults.text(result)).isEqualTo(
                "Rejected by the SQL guard: Only SELECT statements are allowed (got DELETE)");
        assertThat(meters.get("mcp.guard.rejections").tag("tool", "explain_query").counter().count()).isEqualTo(1);
        assertThat(meters.get("mcp.tool.duration").tag("outcome", "rejected").timer().count()).isEqualTo(1);
    }

    @Test
    void toolExceptionsPassTheirMessageThrough() {
        CallToolResult result = runner.run("top_slow_queries", ToolRunner.args(), () -> {
            throw new ToolException("limit must be between 1 and 50");
        });
        assertThat(ToolResults.text(result)).isEqualTo("limit must be between 1 and 50");
        assertThat(meters.get("mcp.tool.duration").tag("outcome", "error").timer().count()).isEqualTo(1);
    }

    @Test
    void connectionFailuresHideConnectionDetails() {
        CallToolResult result = runner.run("table_health", ToolRunner.args(), () -> {
            throw new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                    new SQLException("Connection to db.internal:5432 refused for user mcp_readonly", "08001"));
        });
        assertThat(ToolResults.text(result))
                .isEqualTo("The database is unavailable right now. Try again shortly.")
                .doesNotContain("db.internal", "5432", "mcp_readonly");
    }

    @Test
    void timeoutsExplainWhatToDoInstead() {
        CallToolResult result = runner.run("explain_query", ToolRunner.args(), () -> {
            throw new UncategorizedSQLException("explain", "EXPLAIN ...",
                    new SQLException("canceling statement due to statement timeout", "57014"));
        });
        assertThat(ToolResults.text(result)).contains("5s statement timeout", "analyze=false");
    }

    @Test
    void unexpectedErrorsReturnOnlyAReference() {
        CallToolResult result = runner.run("suggest_indexes", ToolRunner.args(), () -> {
            throw new IllegalStateException("secret internal detail at host db.internal");
        });
        assertThat(ToolResults.text(result))
                .startsWith("Internal error in suggest_indexes. Reference: ")
                .doesNotContain("secret", "db.internal", "IllegalStateException");
    }
}
