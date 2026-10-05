package io.github.gouranshul.pgperf.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gouranshul.pgperf.support.McpTestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** HTTP-level checks of the API key protection. No database needed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"pgperf.security.api-key=" + SecurityTests.KEY, "management.health.db.enabled=false"})
class SecurityTests {

    static final String KEY = "security-test-key-0123456789";

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void healthIsOpenAndRevealsNoDetails() throws Exception {
        HttpResponse<String> response = get("/actuator/health", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components", "details");
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
    }

    @Test
    void mcpEndpointRequiresTheKey() throws Exception {
        HttpResponse<String> response = postMcp(null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(response.body()).contains("Authorization: Bearer");
    }

    @Test
    void wrongKeyIsRejected() throws Exception {
        assertThat(postMcp("Bearer not-the-right-key-0123456").statusCode()).isEqualTo(401);
        assertThat(postMcp("Basic " + KEY).statusCode()).isEqualTo(401);
        assertThat(postMcp(KEY).statusCode()).isEqualTo(401);
    }

    @Test
    void schemeIsCaseInsensitive() throws Exception {
        assertThat(postMcp("bearer " + KEY).statusCode()).isNotEqualTo(401);
    }

    @Test
    void otherActuatorEndpointsRequireTheKey() throws Exception {
        assertThat(get("/actuator/metrics", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/prometheus", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/metrics", "Bearer " + KEY).statusCode()).isEqualTo(200);
    }

    @Test
    void mcpClientWithWrongKeyCannotConnect() {
        assertThatThrownBy(() -> McpTestClient.connect(port, "wrong-key-0123456789abcdef"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void mcpClientWithKeyConnects() {
        try (var client = McpTestClient.connect(port, KEY)) {
            assertThat(client.listTools().tools()).isNotEmpty();
        }
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postMcp(String authorization) throws Exception {
        String initialize = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
                "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(initialize));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
