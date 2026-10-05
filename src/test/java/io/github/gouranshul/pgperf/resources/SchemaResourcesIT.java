package io.github.gouranshul.pgperf.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gouranshul.pgperf.support.McpTestClient;
import io.github.gouranshul.pgperf.support.PostgresIntegrationTest;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the schema resources through a real MCP client. */
class SchemaResourcesIT extends PostgresIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    McpSyncClient client;

    @BeforeEach
    void connect() {
        client = McpTestClient.connect(port, TEST_API_KEY);
    }

    @AfterEach
    void close() {
        client.close();
    }

    @Test
    void overviewListsShopTablesLargestFirst() {
        JsonNode overview = read("pg://schema/overview");
        assertThat(overview.path("tables")).extracting(t -> t.path("table").asString())
                .contains("shop.orders", "shop.order_items", "shop.customers", "shop.products", "shop.reviews");
        assertThat(overview.path("tables").path(0).path("table").asString()).isEqualTo("shop.order_items");
    }

    @Test
    void tableDetailShowsColumnsIndexesAndUnindexedForeignKeys() {
        JsonNode orders = read("pg://schema/shop.orders");

        assertThat(orders.path("table").asString()).isEqualTo("shop.orders");
        assertThat(orders.path("columns")).extracting(c -> c.path("name").asString())
                .containsExactly("id", "customer_id", "status", "total", "created_at");
        assertThat(orders.path("indexes")).extracting(i -> i.path("name").asString()).containsExactly("orders_pkey");
        assertThat(orders.path("foreignKeys")).singleElement().satisfies(fk -> {
            assertThat(fk.path("columns").path(0).asString()).isEqualTo("customer_id");
            assertThat(fk.path("referencesTable").asString()).isEqualTo("shop.customers");
            assertThat(fk.path("indexed").asBoolean()).isFalse();
        });
    }

    @Test
    void foreignKeyCoveredByCompositePrimaryKeyCountsAsIndexed() {
        JsonNode items = read("pg://schema/order_items");
        assertThat(items.path("foreignKeys")).anySatisfy(fk -> {
            assertThat(fk.path("columns").path(0).asString()).isEqualTo("order_id");
            assertThat(fk.path("indexed").asBoolean()).isTrue();
        }).anySatisfy(fk -> {
            assertThat(fk.path("columns").path(0).asString()).isEqualTo("product_id");
            assertThat(fk.path("indexed").asBoolean()).isFalse();
        });
    }

    @Test
    void unknownTableIsAHelpfulError() {
        assertThatThrownBy(() -> read("pg://schema/nope"))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("No table named 'nope'")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Exception", "jdbc"));
    }

    private JsonNode read(String uri) {
        var result = client.readResource(new ReadResourceRequest(uri));
        return JSON.readTree(((TextResourceContents) result.contents().getFirst()).text());
    }
}
