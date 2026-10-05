package io.github.gouranshul.pgperf.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Helpers to read {@link CallToolResult}s in tests. */
public final class ToolResults {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ToolResults() {
    }

    public static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    public static JsonNode json(CallToolResult result) {
        if (Boolean.TRUE.equals(result.isError())) {
            throw new AssertionError("Tool returned an error: " + text(result));
        }
        return JSON.readTree(text(result));
    }
}
