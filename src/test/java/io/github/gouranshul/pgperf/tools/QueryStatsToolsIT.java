package io.github.gouranshul.pgperf.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gouranshul.pgperf.support.PostgresIntegrationTest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class QueryStatsToolsIT extends PostgresIntegrationTest {

    @Autowired
    QueryStatsTools tools;

    @BeforeAll
    static void generateWorkload() {
        for (int i = 0; i < 25; i++) {
            asAdmin("SELECT id, status FROM shop.orders WHERE customer_id = " + (i + 1));
        }
        asAdmin("SELECT count(*) FROM shop.order_items oi JOIN shop.products p ON p.id = oi.product_id");
    }

    @Test
    void returnsNormalizedStatementsRankedByTotalTime() {
        JsonNode result = ToolResults.json(tools.topSlowQueries(5, null));

        assertThat(result.path("orderedBy").asString()).isEqualTo("total_time");
        assertThat(result.path("queries").size()).isBetween(1, 5);
        JsonNode first = result.path("queries").path(0);
        assertThat(first.path("calls").asLong()).isPositive();
        assertThat(first.path("totalTimeMs").asDouble()).isPositive();
        double previous = Double.MAX_VALUE;
        for (JsonNode q : result.path("queries")) {
            assertThat(q.path("totalTimeMs").asDouble()).isLessThanOrEqualTo(previous);
            previous = q.path("totalTimeMs").asDouble();
        }
    }

    @Test
    void callsOrderingSurfacesRepeatedStatementsWithPlaceholders() {
        JsonNode result = ToolResults.json(tools.topSlowQueries(50, "calls"));
        assertThat(result.path("queries")).anySatisfy(q -> {
            assertThat(q.path("query").asString()).contains("customer_id = $1");
            assertThat(q.path("calls").asLong()).isGreaterThanOrEqualTo(25);
        });
    }

    @Test
    void excludesStatementsIssuedByThisServer() {
        tools.topSlowQueries(5, null);
        JsonNode result = ToolResults.json(tools.topSlowQueries(50, "calls"));
        assertThat(result.path("queries"))
                .noneSatisfy(q -> assertThat(q.path("query").asString()).contains("pg_stat_statements s"));
    }

    @Test
    void rejectsInvalidArgumentsWithHelpfulMessages() {
        CallToolResult badOrder = tools.topSlowQueries(5, "slowest");
        assertThat(badOrder.isError()).isTrue();
        assertThat(ToolResults.text(badOrder)).contains("total_time, mean_time, calls, rows");

        CallToolResult badLimit = tools.topSlowQueries(500, null);
        assertThat(badLimit.isError()).isTrue();
        assertThat(ToolResults.text(badLimit)).contains("between 1 and 50");
    }
}
