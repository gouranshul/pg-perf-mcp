package io.github.gouranshul.pgperf.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gouranshul.pgperf.db.ReadOnlyExecutor;
import io.github.gouranshul.pgperf.support.DemoDatabase;
import io.github.gouranshul.pgperf.support.PostgresIntegrationTest;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class IndexToolsIT extends PostgresIntegrationTest {

    @Autowired
    IndexTools tools;

    @Autowired
    ReadOnlyExecutor executor;

    @Test
    void suggestsMissingForeignKeyIndexAndValidatesItWithHypopg() {
        JsonNode result = ToolResults.json(tools.suggestIndexes(
                "SELECT id, status FROM shop.orders WHERE customer_id = 42"));

        assertThat(result.path("validation").asString()).startsWith("estimated with hypopg");
        JsonNode suggestion = result.path("suggestions").path(0);
        assertThat(suggestion.path("statement").asString())
                .isEqualTo("CREATE INDEX CONCURRENTLY idx_orders_customer_id ON shop.orders (customer_id);");
        assertThat(suggestion.path("usedByPlanner").asBoolean()).isTrue();
        assertThat(suggestion.path("improvementPercent").asDouble()).isGreaterThan(50);
        assertThat(result.path("important").asString()).contains("NOT executed");
    }

    @Test
    void suggestsSortOrderIndexForLatestOrders() {
        JsonNode result = ToolResults.json(tools.suggestIndexes(
                "SELECT id, customer_id, status, total FROM shop.orders ORDER BY created_at DESC LIMIT 20"));
        assertThat(result.path("suggestions")).anySatisfy(s ->
                assertThat(s.path("statement").asString()).contains("(created_at DESC)"));
    }

    @Test
    void neverCreatesRealIndexes() throws Exception {
        tools.suggestIndexes("SELECT id FROM shop.orders WHERE customer_id = 7");
        try (var connection = DemoDatabase.adminConnection();
                var statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT count(*) FROM pg_indexes"
                        + " WHERE schemaname = 'shop' AND tablename = 'orders' AND indexdef LIKE '%(customer_id)%'")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void hypotheticalIndexesDoNotLeakIntoThePooledConnection() {
        tools.suggestIndexes("SELECT id FROM shop.orders WHERE customer_id = 9");
        long leaked = executor.run(db -> db.client().sql("SELECT count(*) FROM hypopg_list_indexes")
                .query(Long.class).single());
        assertThat(leaked).isZero();
    }

    @Test
    void reportsLeadingWildcardSearchesAsANote() {
        JsonNode result = ToolResults.json(tools.suggestIndexes(
                "SELECT id FROM shop.products WHERE name LIKE '%Bamboo Mug%'"));
        assertThat(result.path("notes")).anySatisfy(n -> assertThat(n.asString()).contains("pg_trgm"));
    }
}
