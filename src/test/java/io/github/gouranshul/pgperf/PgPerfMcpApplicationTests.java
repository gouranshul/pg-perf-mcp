package io.github.gouranshul.pgperf;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gouranshul.pgperf.support.McpTestClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Talks to the running server through a real MCP client over streamable HTTP. Needs no database:
 * listing tools, resources and prompts and rendering a prompt never touch JDBC.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "pgperf.security.api-key=context-test-key-0123456789")
class PgPerfMcpApplicationTests {

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry meters;

    McpSyncClient client;

    @BeforeEach
    void connect() {
        client = McpTestClient.connect(port, "context-test-key-0123456789");
    }

    @AfterEach
    void close() {
        client.close();
    }

    @Test
    void exposesAllToolsAsReadOnly() {
        assertThat(client.listTools().tools()).extracting(Tool::name).containsExactlyInAnyOrder(
                "top_slow_queries", "explain_query", "suggest_indexes", "unused_indexes", "table_health",
                "blocking_sessions");
        assertThat(client.listTools().tools()).allSatisfy(tool -> {
            assertThat(tool.description()).hasSizeGreaterThan(100);
            assertThat(tool.annotations().readOnlyHint()).isTrue();
            assertThat(tool.annotations().destructiveHint()).isFalse();
        });
    }

    @Test
    void documentsToolParametersForTheModel() {
        Tool explain = client.listTools().tools().stream()
                .filter(t -> t.name().equals("explain_query")).findFirst().orElseThrow();
        assertThat(explain.inputSchema()).containsEntry("required", java.util.List.of("sql"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) explain.inputSchema().get("properties");
        assertThat(properties).containsKeys("sql", "analyze");
    }

    @Test
    void exposesSchemaResources() {
        assertThat(client.listResources().resources()).extracting(Resource::uri).contains("pg://schema/overview");
        assertThat(client.listResourceTemplates().resourceTemplates()).extracting(ResourceTemplate::uriTemplate)
                .contains("pg://schema/{table}");
    }

    @Test
    void writeAttemptsAreRejectedByTheGuardBeforeReachingTheDatabase() {
        double before = rejections();
        CallToolResult result = client.callTool(new CallToolRequest("explain_query",
                Map.of("sql", "WITH gone AS (DELETE FROM shop.orders RETURNING *) SELECT * FROM gone", "analyze", true)));

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text())
                .startsWith("Rejected by the SQL guard: Data-modifying CTE");
        assertThat(rejections()).isEqualTo(before + 1);
    }

    private double rejections() {
        var counter = meters.find("mcp.guard.rejections").tag("tool", "explain_query").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void rendersTheDiagnosisPrompt() {
        assertThat(client.listPrompts().prompts()).extracting(Prompt::name).contains("diagnose_slow_database");
        var prompt = client.getPrompt(new GetPromptRequest("diagnose_slow_database", Map.of("focus", "orders")));
        String text = ((TextContent) prompt.messages().getFirst().content()).text();
        assertThat(text).contains("top_slow_queries", "explain_query", "suggest_indexes", "table_health",
                "particularly concerned about: orders", "LIKE '%...%'");
    }
}
