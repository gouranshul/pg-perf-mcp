package io.github.gouranshul.pgperf.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gouranshul.pgperf.db.ReadOnlyExecutor;
import io.github.gouranshul.pgperf.support.PostgresIntegrationTest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * explain_function and slow_functions against the demo functions in demo/schema.sql: PL/pgSQL
 * and SQL functions with a slow statement inside, and one function that writes.
 */
class FunctionToolsIT extends PostgresIntegrationTest {

    @Autowired
    FunctionTools tools;

    @Autowired
    ReadOnlyExecutor executor;

    @Test
    void plpgsqlFunctionCalledPerRowShowsTheStatementInsideItAndItsPlan() {
        JsonNode result = ToolResults.json(tools.explainFunction(
                "SELECT p.id, shop.product_rating(p.id) FROM shop.products p ORDER BY p.id LIMIT 25"));
        JsonNode analysis = result.path("analysis");

        JsonNode function = analysis.path("functions").path(0);
        assertThat(function.path("name").asString()).isEqualTo("shop.product_rating");
        assertThat(function.path("language").asString()).isEqualTo("plpgsql");
        assertThat(function.path("volatility").asString()).isEqualTo("STABLE");
        assertThat(function.path("calls").asLong()).isEqualTo(25);
        assertThat(function.path("definition").asString()).contains("INTO v_rating");

        JsonNode statement = analysis.path("statements").path(0);
        assertThat(statement.path("query").asString()).contains("FROM shop.reviews WHERE product_id = p_product_id");
        assertThat(statement.path("function").asString()).isEqualTo("shop.product_rating");
        assertThat(statement.path("calls").asLong()).isEqualTo(25);
        assertThat(statement.path("plan").path("analyzed").asBoolean()).isTrue();
        assertThat(statement.path("plan").path("costliestNodes").toString()).contains("shop.reviews");

        assertThat(analysis.path("findings")).anySatisfy(f -> {
            assertThat(f.path("type").asString()).isEqualTo("FUNCTION_CALLED_PER_ROW");
            assertThat(f.path("message").asString()).contains("ran 25 times");
        });
        assertThat(analysis.path("outerPlan").path("analyzed").asBoolean()).isTrue();
        assertThat(result.path("notes")).isEmpty();
    }

    @Test
    void sqlFunctionRevealsTheSequentialScanInsideIt() {
        JsonNode analysis = ToolResults.json(tools.explainFunction("SELECT shop.customer_lifetime_value(42)"))
                .path("analysis");

        JsonNode function = analysis.path("functions").path(0);
        assertThat(function.path("name").asString()).isEqualTo("shop.customer_lifetime_value");
        assertThat(function.path("language").asString()).isEqualTo("sql");
        assertThat(function.path("volatility").asString()).isEqualTo("VOLATILE");
        assertThat(analysis.path("statements").path(0).path("query").asString()).contains("FROM shop.orders");

        assertThat(analysis.path("findings")).anySatisfy(f -> {
            assertThat(f.path("type").asString()).isEqualTo("SEQ_SCAN_ON_LARGE_TABLE");
            assertThat(f.path("node").asString())
                    .isEqualTo("inside shop.customer_lifetime_value: Seq Scan on shop.orders");
        });
        assertThat(analysis.path("findings")).anySatisfy(f ->
                assertThat(f.path("type").asString()).isEqualTo("VOLATILE_FUNCTION_ONLY_READS"));
    }

    @Test
    void nestedPlansShowPlaceholdersNotTheValuesFunctionVariablesHeld() {
        JsonNode plan = ToolResults.json(tools.explainFunction("SELECT shop.product_rating(4242)"))
                .path("analysis").path("statements").path(0).path("plan");

        String nodes = plan.path("costliestNodes").toString();
        assertThat(nodes).contains("$1").doesNotContain("4242");
    }

    @Test
    void functionsThatWriteAreBlocked() {
        CallToolResult result = tools.explainFunction("SELECT shop.mark_order_shipped(1)");

        assertThat(result.isError()).isTrue();
        assertThat(ToolResults.text(result)).contains("read-only");
    }

    @Test
    void autoExplainAndGenericPlansDoNotLeakIntoThePooledConnection() {
        ToolResults.json(tools.explainFunction("SELECT shop.product_rating(7)"));

        // The pool has one connection, so this runs on the connection explain_function just used.
        String[] settings = executor.run(db -> db.client().sql("""
                SELECT current_setting('auto_explain.log_min_duration'), current_setting('plan_cache_mode')""")
                .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)}).single());
        assertThat(settings).containsExactly("-1", "auto");
    }

    @Test
    void backToBackCallsOnOneConnectionCountOnlyTheirOwnWork() {
        // Function statistics from the first call are still pending (flushed at most once a second)
        // when the second call starts on the same pooled connection.
        ToolResults.json(tools.explainFunction("SELECT shop.product_rating(7)"));
        JsonNode second = ToolResults.json(tools.explainFunction("SELECT shop.product_rating(8)")).path("analysis");

        assertThat(second.path("functions").path(0).path("calls").asLong()).isEqualTo(1);
        assertThat(second.path("statements").path(0).path("calls").asLong()).isEqualTo(1);
    }

    @Test
    void withoutPermissionForAutoExplainItStillReportsTimings() {
        asAdmin("REVOKE SET ON PARAMETER auto_explain.log_min_duration FROM mcp_readonly");
        try {
            JsonNode result = ToolResults.json(tools.explainFunction("SELECT shop.product_rating(7)"));

            assertThat(result.path("notes")).anySatisfy(n ->
                    assertThat(n.asString()).contains("may not change auto_explain settings", "GRANT SET ON PARAMETER"));
            JsonNode statement = result.path("analysis").path("statements").path(0);
            assertThat(statement.path("calls").asLong()).isEqualTo(1);
            assertThat(statement.path("plan").isNull()).isTrue();
            assertThat(result.path("analysis").path("functions").path(0).path("calls").asLong()).isEqualTo(1);
        } finally {
            asAdmin("GRANT SET ON PARAMETER auto_explain.log_min_duration TO mcp_readonly");
        }
    }

    @Test
    void queriesWithoutFunctionsPointBackToExplainQuery() {
        JsonNode result = ToolResults.json(tools.explainFunction("SELECT count(*) FROM shop.orders WHERE id < 10"));

        assertThat(result.path("analysis").path("functions")).isEmpty();
        assertThat(result.path("analysis").path("statements")).isEmpty();
        assertThat(result.path("notes")).anySatisfy(n ->
                assertThat(n.asString()).contains("No user-defined function ran"));
        assertThat(result.path("hint").asString()).contains("explain_query");
    }

    @Test
    void placeholdersAndGuardRejectionsAreReportedNotExecuted() {
        CallToolResult placeholders = tools.explainFunction("SELECT shop.product_rating($1)");
        assertThat(placeholders.isError()).isTrue();
        assertThat(ToolResults.text(placeholders)).contains("needs real values");

        CallToolResult twoStatements = tools.explainFunction("SELECT shop.product_rating(1); DELETE FROM shop.orders");
        assertThat(twoStatements.isError()).isTrue();
        assertThat(ToolResults.text(twoStatements)).contains("Rejected by the SQL guard");
    }

    @Test
    void slowFunctionsRanksFunctionsCalledByTheWorkload() throws InterruptedException {
        // Function statistics are flushed when the session ends, so run the workload in its own session.
        asAdmin("SELECT shop.product_rating(id) FROM shop.products ORDER BY id LIMIT 30");

        JsonNode rating = awaitFunction("shop.product_rating");
        assertThat(rating.path("language").asString()).isEqualTo("plpgsql");
        assertThat(rating.path("arguments").asString()).isEqualTo("p_product_id bigint");
        assertThat(rating.path("calls").asLong()).isGreaterThanOrEqualTo(30);
        assertThat(rating.path("meanTimeMs").asDouble()).isPositive();
    }

    @Test
    void slowFunctionsValidatesItsArguments() {
        assertThat(ToolResults.text(tools.slowFunctions(0, null))).contains("limit must be between 1 and 50");
        assertThat(ToolResults.text(tools.slowFunctions(5, "fastest"))).contains("orderBy must be one of");
        assertThat(ToolResults.json(tools.slowFunctions(5, "self_time")).path("orderedBy").asString())
                .isEqualTo("self_time");
    }

    private JsonNode awaitFunction(String name) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (true) {
            JsonNode result = ToolResults.json(tools.slowFunctions(50, "calls"));
            for (JsonNode f : result.path("functions")) {
                if (f.path("function").asString().equals(name)) {
                    assertThat(result.path("hint").asString()).contains("explain_function");
                    // A call that failed (e.g. a blocked write) leaves an entry with 0 calls behind.
                    assertThat(result.path("functions")).allSatisfy(g ->
                            assertThat(g.path("calls").asLong()).isPositive());
                    return f;
                }
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError(name + " never appeared in slow_functions: " + result);
            }
            Thread.sleep(200);
        }
    }
}
