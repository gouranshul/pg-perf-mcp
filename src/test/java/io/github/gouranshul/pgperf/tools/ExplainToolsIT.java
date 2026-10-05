package io.github.gouranshul.pgperf.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gouranshul.pgperf.support.PostgresIntegrationTest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class ExplainToolsIT extends PostgresIntegrationTest {

    @Autowired
    ExplainTools tools;

    @Test
    void estimatedPlanFlagsSeqScanOnLargeTable() {
        JsonNode result = ToolResults.json(tools.explainQuery(
                "SELECT id, status FROM shop.orders WHERE customer_id = 42", false));

        assertThat(result.path("mode").asString()).isEqualTo("estimated");
        JsonNode analysis = result.path("analysis");
        assertThat(analysis.path("analyzed").asBoolean()).isFalse();
        assertThat(analysis.path("costliestNodes").path(0).path("node").asString())
                .isEqualTo("Seq Scan on shop.orders");
        assertThat(analysis.path("findings")).anySatisfy(f -> {
            assertThat(f.path("type").asString()).isEqualTo("SEQ_SCAN_ON_LARGE_TABLE");
            assertThat(f.path("message").asString()).contains("customer_id = 42");
        });
    }

    @Test
    void analyzeReportsActualRowsAndTimings() {
        JsonNode analysis = ToolResults.json(tools.explainQuery(
                "SELECT id FROM shop.orders ORDER BY created_at DESC LIMIT 20", true)).path("analysis");

        assertThat(analysis.path("analyzed").asBoolean()).isTrue();
        assertThat(analysis.path("executionTimeMs").asDouble()).isPositive();
        assertThat(analysis.path("costliestNodes").path(0).has("actualRows")).isTrue();
    }

    @Test
    void normalizedQueriesGetAGenericPlan() {
        JsonNode result = ToolResults.json(tools.explainQuery(
                "SELECT * FROM shop.orders WHERE customer_id = $1 AND status = $2", null));
        assertThat(result.path("mode").asString()).isEqualTo("generic");
    }

    @Test
    void placeholdersCannotBeAnalyzed() {
        CallToolResult result = tools.explainQuery("SELECT * FROM shop.orders WHERE customer_id = $1", true);
        assertThat(result.isError()).isTrue();
        assertThat(ToolResults.text(result)).contains("analyze=false");
    }

    @Test
    void questionMarkOperatorsAreNotTreatedAsBindParameters() {
        JsonNode result = ToolResults.json(tools.explainQuery(
                "SELECT id FROM shop.products WHERE '{\"a\": 1}'::jsonb ? 'a'", false));
        assertThat(result.path("mode").asString()).isEqualTo("estimated");
    }

    @Test
    void guardRejectionsAreReportedNotExecuted() {
        CallToolResult result = tools.explainQuery("DELETE FROM shop.orders", true);
        assertThat(result.isError()).isTrue();
        assertThat(ToolResults.text(result)).startsWith("Rejected by the SQL guard");
        asAdmin("DO $$ BEGIN IF (SELECT count(*) FROM shop.orders) = 0 THEN RAISE 'orders deleted'; END IF; END $$");
    }

    @Test
    void longRunningAnalyzeIsCancelledWithAClearMessage() {
        CallToolResult result = tools.explainQuery(
                "SELECT count(*) FROM shop.order_items a CROSS JOIN shop.order_items b", true);
        assertThat(result.isError()).isTrue();
        assertThat(ToolResults.text(result)).contains("statement timeout").doesNotContain("jdbc:", "Exception");
    }

    @Test
    void sqlErrorsAreReportedWithoutInternals() {
        CallToolResult result = tools.explainQuery("SELECT * FROM shop.no_such_table", false);
        assertThat(result.isError()).isTrue();
        assertThat(ToolResults.text(result))
                .contains("42P01", "does not exist")
                .doesNotContain("jdbc:", "localhost", "mcp_readonly", "at org.");
    }
}
