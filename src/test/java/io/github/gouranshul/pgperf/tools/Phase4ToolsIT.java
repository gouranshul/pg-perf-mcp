package io.github.gouranshul.pgperf.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gouranshul.pgperf.support.DemoDatabase;
import io.github.gouranshul.pgperf.support.PostgresIntegrationTest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** unused_indexes, table_health and blocking_sessions against the seeded demo database. */
class Phase4ToolsIT extends PostgresIntegrationTest {

    @Autowired
    UnusedIndexTools unusedIndexTools;

    @Autowired
    TableHealthTools tableHealthTools;

    @Autowired
    LockTools lockTools;

    @Test
    void unusedIndexesListsTheDeliberatelyUselessIndexButNotConstraints() {
        JsonNode result = ToolResults.json(unusedIndexTools.unusedIndexes());

        assertThat(result.path("indexes")).anySatisfy(i -> {
            assertThat(i.path("index").asString()).isEqualTo("shop.idx_customers_last_login");
            assertThat(i.path("sizeBytes").asLong()).isPositive();
            assertThat(i.path("dropStatement").asString())
                    .isEqualTo("DROP INDEX CONCURRENTLY shop.idx_customers_last_login;");
        });
        assertThat(result.path("indexes")).noneSatisfy(i ->
                assertThat(i.path("index").asString()).endsWith("_pkey"));
        assertThat(result.path("indexes")).noneSatisfy(i ->
                assertThat(i.path("index").asString()).endsWith("_key"));
        assertThat(result.path("caution").asString()).contains("Nothing was dropped");
    }

    @Test
    void tableHealthReportsDeadTuplesAndDisabledAutovacuumOnOrders() {
        JsonNode orders = ToolResults.json(tableHealthTools.tableHealth("shop.orders")).path("tables").path(0);

        assertThat(orders.path("table").asString()).isEqualTo("shop.orders");
        assertThat(orders.path("deadTuples").asLong()).isPositive();
        assertThat(orders.path("autovacuumEnabled").asBoolean()).isFalse();
        assertThat(orders.path("warnings")).anySatisfy(w ->
                assertThat(w.asString()).contains("Autovacuum is disabled"));
        assertThat(orders.path("totalSize").asString()).isNotBlank();
    }

    @Test
    void tableHealthWithoutArgumentRanksByDeadTuples() {
        JsonNode result = ToolResults.json(tableHealthTools.tableHealth(null));
        assertThat(result.path("tablesShown").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(result.path("tables").path(0).path("table").asString()).isEqualTo("shop.orders");
    }

    @Test
    void tableHealthAcceptsUnqualifiedNamesAndExplainsUnknownOnes() {
        assertThat(ToolResults.json(tableHealthTools.tableHealth("reviews")).path("tablesShown").asInt()).isEqualTo(1);

        CallToolResult missing = tableHealthTools.tableHealth("no_such_table");
        assertThat(missing.isError()).isTrue();
        assertThat(ToolResults.text(missing)).contains("pg://schema/overview");
    }

    @Test
    void blockingSessionsFindsTheBlockerAndTheWaiter() throws Exception {
        try (Connection blocker = DemoDatabase.adminConnection(); Connection waiter = DemoDatabase.adminConnection()) {
            blocker.setAutoCommit(false);
            int blockerPid = pid(blocker);
            int waiterPid = pid(waiter);
            try (Statement s = blocker.createStatement()) {
                s.execute("UPDATE shop.products SET stock = stock - 1 WHERE id = 42");
            }
            CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> {
                try (Statement s = waiter.createStatement()) {
                    s.execute("UPDATE shop.products SET price = price WHERE id = 42");
                } catch (Exception ignored) {
                    // released when the blocker rolls back
                }
            });
            try {
                JsonNode report = awaitBlocking();
                assertThat(report.path("rootBlockers")).extracting(JsonNode::asInt).contains(blockerPid);
                assertThat(report.path("pairs")).anySatisfy(p -> {
                    assertThat(p.path("blockedPid").asInt()).isEqualTo(waiterPid);
                    assertThat(p.path("blockingPid").asInt()).isEqualTo(blockerPid);
                    assertThat(p.path("blockingState").asString()).isEqualTo("idle in transaction");
                    assertThat(p.path("blockedQuery").asString()).contains("UPDATE shop.products");
                    assertThat(p.path("lockType").asString()).isNotBlank();
                });
            } finally {
                blocker.rollback();
                waiting.join();
            }
        }
        assertThat(ToolResults.json(lockTools.blockingSessions()).path("pairs").size()).isZero();
    }

    private JsonNode awaitBlocking() throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            JsonNode report = ToolResults.json(lockTools.blockingSessions());
            if (report.path("blockedSessions").asInt() > 0) {
                return report;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No blocked session appeared");
    }

    private static int pid(Connection connection) throws Exception {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
