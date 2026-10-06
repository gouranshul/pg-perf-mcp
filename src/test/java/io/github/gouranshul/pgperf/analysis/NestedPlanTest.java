package io.github.gouranshul.pgperf.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class NestedPlanTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** An auto_explain notice as PostgreSQL 18 sends it (log_format=json, log_analyze=on). */
    static String notice(long queryId, String queryText, double durationMs, String plan) {
        return String.format(Locale.ROOT, """
                duration: %.3f ms  plan:
                {
                  "Query Text": "%s",
                  "Plan": %s,
                  "Query Identifier": %d
                }""", durationMs, queryText, plan, queryId);
    }

    static final String SEQ_SCAN_ON_REVIEWS = """
            {
              "Node Type": "Aggregate", "Startup Cost": 4687.5, "Total Cost": 4687.51, "Plan Rows": 1,
              "Actual Startup Time": 4.4, "Actual Total Time": 4.4, "Actual Rows": 1, "Actual Loops": 1,
              "Plans": [{
                "Node Type": "Seq Scan", "Parent Relationship": "Outer", "Relation Name": "reviews",
                "Schema": "shop", "Alias": "reviews", "Startup Cost": 0, "Total Cost": 4687.5, "Plan Rows": 5,
                "Actual Startup Time": 0.5, "Actual Total Time": 4.3, "Actual Rows": 5, "Actual Loops": 1,
                "Filter": "(reviews.product_id = $1)", "Rows Removed by Filter": 249995
              }]
            }""";

    @Test
    void parsesAnAutoExplainNotice() {
        NestedPlan plan = NestedPlan.parse(notice(111, "SELECT avg(rating)        FROM shop.reviews "
                + "WHERE product_id = p_product_id", 4.5, SEQ_SCAN_ON_REVIEWS), JSON).orElseThrow();

        assertThat(plan.queryId()).isEqualTo(111);
        assertThat(plan.queryText()).startsWith("SELECT avg(rating)").endsWith("product_id = p_product_id");
        assertThat(plan.durationMs()).isEqualTo(4.5);
        assertThat(plan.plan().analyzed()).isTrue();
        assertThat(plan.plan().executionTimeMs()).isEqualTo(4.5);
        assertThat(plan.plan().root().flatten()).extracting(PlanNode::qualifiedRelation).contains("shop.reviews");
    }

    @Test
    void missingQueryIdentifierBecomesZero() {
        String notice = """
                duration: 1.000 ms  plan:
                {"Query Text": "SELECT 1", "Plan": {"Node Type": "Result"}}""";
        assertThat(NestedPlan.parse(notice, JSON)).hasValueSatisfying(p -> assertThat(p.queryId()).isZero());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "relation \"x\" does not exist, skipping",
            "duration: 1.000 ms  plan:\nQuery Text: SELECT 1\nResult  (cost=0.00..0.01 rows=1 width=4)",
            "duration: 1.000 ms  plan:\n{\"Query Text\": \"SELECT 1\", \"Plan\": {",
            "duration: 1.000 ms  plan:\n{\"Query Text\": \"SELECT 1\"}",
    })
    void ignoresAnythingThatIsNotAJsonPlan(String notice) {
        assertThat(NestedPlan.parse(notice, JSON)).isEmpty();
    }
}
