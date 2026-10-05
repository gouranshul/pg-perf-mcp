package io.github.gouranshul.pgperf.support;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.net.http.HttpRequest;
import java.time.Duration;

/** Builds a real MCP client that talks streamable HTTP to the server under test. */
public final class McpTestClient {

    private McpTestClient() {
    }

    public static McpSyncClient connect(int port, String apiKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder();
        if (apiKey != null) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .requestBuilder(request)
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        client.initialize();
        return client;
    }
}
