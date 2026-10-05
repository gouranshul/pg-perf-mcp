package io.github.gouranshul.pgperf.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gouranshul.pgperf.audit.AuditLogger.Outcome;
import io.github.gouranshul.pgperf.tools.ToolRunner;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AuditLoggerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AuditLogger audit = new AuditLogger(JSON);

    @Test
    void writesOneJsonObjectWithAllFields() {
        String line = audit.toJson(Instant.parse("2026-10-01T10:15:30Z"), "req-1", "table_health",
                ToolRunner.args("table", "shop.orders"), 42, 1, Outcome.OK, null);

        assertThat(line).doesNotContain("\n");
        JsonNode json = JSON.readTree(line);
        assertThat(json.path("timestamp").asString()).isEqualTo("2026-10-01T10:15:30Z");
        assertThat(json.path("event").asString()).isEqualTo("mcp_tool_call");
        assertThat(json.path("requestId").asString()).isEqualTo("req-1");
        assertThat(json.path("tool").asString()).isEqualTo("table_health");
        assertThat(json.path("args").path("table").asString()).isEqualTo("shop.orders");
        assertThat(json.path("durationMs").asLong()).isEqualTo(42);
        assertThat(json.path("rowCount").asInt()).isEqualTo(1);
        assertThat(json.path("outcome").asString()).isEqualTo("ok");
        assertThat(json.path("reason").isNull()).isTrue();
    }

    @Test
    void sqlIsHashedAndTruncatedNeverLoggedInFull() {
        String sql = "SELECT * FROM shop.customers WHERE email = 'someone@example.com' AND full_name = 'A Person'"
                + " AND country = 'NL'";
        String line = audit.toJson(Instant.now(), "req-2", "explain_query",
                ToolRunner.args("sql", sql, "analyze", true), 5, null, Outcome.REJECTED, "Rejected by the SQL guard");

        JsonNode args = JSON.readTree(line).path("args");
        assertThat(args.path("sql").path("sha256").asString()).isEqualTo(AuditLogger.sha256(sql)).hasSize(64);
        assertThat(args.path("sql").path("preview").asString()).hasSize(80).isEqualTo(sql.substring(0, 80));
        assertThat(args.path("sql").path("length").asInt()).isEqualTo(sql.length());
        assertThat(args.path("analyze").asBoolean()).isTrue();
        assertThat(line).doesNotContain("country = 'NL'");
        assertThat(JSON.readTree(line).path("outcome").asString()).isEqualTo("rejected");
    }

    @Test
    void shortSqlPreviewIsTheWholeStatement() {
        assertThat(AuditLogger.redact(ToolRunner.args("sql", "SELECT 1")))
                .extractingByKey("sql").asString().contains("preview=SELECT 1");
    }
}
