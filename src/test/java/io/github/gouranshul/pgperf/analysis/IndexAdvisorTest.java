package io.github.gouranshul.pgperf.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gouranshul.pgperf.analysis.IndexAdvice.Suggestion;
import io.github.gouranshul.pgperf.analysis.TableInfo.ExistingIndex;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexAdvisorTest {

    private static final TableInfo ORDERS = new TableInfo("shop", "orders", 1_000_000,
            List.of(new ExistingIndex("orders_pkey", List.of("id"))));
    private static final TableInfo CUSTOMERS = new TableInfo("shop", "customers", 200_000,
            List.of(new ExistingIndex("customers_pkey", List.of("id")),
                    new ExistingIndex("customers_email_key", List.of("email"))));
    private static final Map<String, TableInfo> SHOP = Map.of("shop.orders", ORDERS, "shop.customers", CUSTOMERS);

    private final IndexAdvisor advisor = new IndexAdvisor();

    @Test
    void suggestsIndexForSelectiveFilterOnSeqScannedTable() {
        IndexAdvice advice = advisor.advise(Plans.load("customer_orders_seqscan.json"), SHOP);

        assertThat(advice.suggestions()).singleElement().satisfies(s -> {
            assertThat(s.table()).isEqualTo("shop.orders");
            assertThat(s.columns()).containsExactly("customer_id");
            assertThat(s.statement())
                    .isEqualTo("CREATE INDEX CONCURRENTLY idx_orders_customer_id ON shop.orders (customer_id);");
            assertThat(s.reasons()).singleElement().asString().contains("filters on customer_id");
        });
    }

    @Test
    void suggestsSortOrderIndexForOrderByLimit() {
        IndexAdvice advice = advisor.advise(Plans.load("latest_orders_limit.json"), SHOP);

        assertThat(advice.suggestions()).singleElement().satisfies(s -> {
            assertThat(s.columns()).containsExactly("created_at DESC");
            assertThat(s.statement())
                    .isEqualTo("CREATE INDEX CONCURRENTLY idx_orders_created_at ON shop.orders (created_at DESC);");
            assertThat(s.reasons().getFirst()).contains("ORDER BY o.created_at DESC with LIMIT");
        });
    }

    @Test
    void suggestsJoinKeyIndexAndSkipsColumnsCoveredByPrimaryKey() {
        IndexAdvice advice = advisor.advise(Plans.load("country_revenue_hashjoin.json"), SHOP);

        assertThat(advice.suggestions()).extracting(Suggestion::statement).containsExactly(
                "CREATE INDEX CONCURRENTLY idx_orders_customer_id ON shop.orders (customer_id);",
                "CREATE INDEX CONCURRENTLY idx_customers_country_created_at ON shop.customers (country, created_at);");
        assertThat(advice.suggestions().getFirst().reasons().getFirst())
                .contains("Join key o.customer_id = c.id");
        assertThat(advice.notes()).anySatisfy(n -> assertThat(n).contains("customers_pkey"));
    }

    @Test
    void ordersEqualityColumnsBeforeTheRangeColumn() {
        String json = """
                [{"Plan": {"Node Type": "Seq Scan", "Relation Name": "orders", "Schema": "shop", "Alias": "o",
                  "Total Cost": 25000, "Plan Rows": 40,
                  "Filter": "((o.created_at > '2026-01-01 00:00:00+00'::timestamp with time zone) AND (o.status = 'paid'::text) AND (o.customer_id = 7))"}}]""";
        IndexAdvice advice = advisor.advise(Plans.parse(json), SHOP);
        assertThat(advice.suggestions()).singleElement()
                .extracting(Suggestion::columns).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("status", "customer_id", "created_at");
    }

    @Test
    void mergesFilterCandidateIntoWiderSortCandidate() {
        String json = """
                [{"Plan": {"Node Type": "Limit", "Total Cost": 100, "Plan Rows": 10, "Plans": [
                  {"Node Type": "Sort", "Total Cost": 21400, "Plan Rows": 5, "Sort Key": ["created_at DESC"], "Plans": [
                    {"Node Type": "Seq Scan", "Relation Name": "orders", "Schema": "shop", "Alias": "orders",
                     "Total Cost": 21388, "Plan Rows": 5, "Filter": "(customer_id = 42)"}]}]}}]""";
        IndexAdvice advice = advisor.advise(Plans.parse(json), SHOP);
        assertThat(advice.suggestions()).singleElement().satisfies(s -> {
            assertThat(s.columns()).containsExactly("customer_id", "created_at DESC");
            assertThat(s.reasons()).hasSize(2);
        });
    }

    @Test
    void lowSelectivityFilterBecomesANoteNotASuggestion() {
        String json = """
                [{"Plan": {"Node Type": "Seq Scan", "Relation Name": "orders", "Schema": "shop", "Alias": "orders",
                  "Total Cost": 21388, "Plan Rows": 300000, "Filter": "(status = 'shipped'::text)"}}]""";
        IndexAdvice advice = advisor.advise(Plans.parse(json), SHOP);
        assertThat(advice.suggestions()).isEmpty();
        assertThat(advice.notes()).singleElement().asString().contains("keeps about 30%");
    }

    @Test
    void leadingWildcardLikeSuggestsTrigramNote() {
        String json = """
                [{"Plan": {"Node Type": "Seq Scan", "Relation Name": "products", "Schema": "shop", "Alias": "products",
                  "Total Cost": 1200, "Plan Rows": 5, "Filter": "(name ~~ '%Bamboo Mug%'::text)"}}]""";
        IndexAdvice advice = advisor.advise(Plans.parse(json),
                Map.of("shop.products", new TableInfo("shop", "products", 50_000, List.of())));
        assertThat(advice.suggestions()).isEmpty();
        assertThat(advice.notes()).singleElement().asString().contains("pg_trgm", "gin_trgm_ops");
    }

    @Test
    void existingIndexWithSameLeadingColumnIsReported() {
        TableInfo indexed = new TableInfo("shop", "orders", 1_000_000,
                List.of(new ExistingIndex("orders_customer_id_created_at_idx", List.of("customer_id", "created_at"))));
        IndexAdvice advice = advisor.advise(Plans.load("customer_orders_seqscan.json"), Map.of("shop.orders", indexed));
        assertThat(advice.suggestions()).isEmpty();
        assertThat(advice.notes()).singleElement().asString().contains("orders_customer_id_created_at_idx");
    }

    @Test
    void ignoresSmallTables() {
        TableInfo tiny = new TableInfo("shop", "orders", 800, List.of());
        assertThat(advisor.advise(Plans.load("customer_orders_seqscan.json"), Map.of("shop.orders", tiny)).suggestions())
                .isEmpty();
    }

    @Test
    void suggestsJoinFilterIndexForNestedLoopOverSeqScan() {
        IndexAdvice advice = advisor.advise(Plans.load("n_plus_one_analyzed.json"),
                Map.of("shop.reviews", new TableInfo("shop", "reviews", 250_000, List.of())));
        assertThat(advice.suggestions()).extracting(Suggestion::statement)
                .containsExactly("CREATE INDEX CONCURRENTLY idx_reviews_product_id ON shop.reviews (product_id);");
    }

    @Test
    void quotesIdentifiersThatNeedIt() {
        assertThat(IndexAdvisor.quote("customer_id")).isEqualTo("customer_id");
        assertThat(IndexAdvisor.quote("CustomerId")).isEqualTo("\"CustomerId\"");
        assertThat(IndexAdvisor.quote("odd\"name")).isEqualTo("\"odd\"\"name\"");
    }
}
