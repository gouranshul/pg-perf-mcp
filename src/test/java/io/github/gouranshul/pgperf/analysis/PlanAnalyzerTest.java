package io.github.gouranshul.pgperf.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gouranshul.pgperf.analysis.PlanAnalysis.Finding;
import io.github.gouranshul.pgperf.analysis.PlanAnalysis.FindingType;
import io.github.gouranshul.pgperf.analysis.PlanAnalysis.NodeSummary;
import io.github.gouranshul.pgperf.analysis.PlanAnalysis.Severity;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanAnalyzerTest {

    private static final Map<String, Long> SHOP_TABLES = Map.of(
            "shop.orders", 1_000_000L,
            "shop.customers", 200_000L,
            "shop.products", 50_000L,
            "shop.reviews", 250_000L);

    private final PlanAnalyzer analyzer = new PlanAnalyzer();

    @Test
    void selectiveSeqScanOnLargeTableIsHighSeverity() {
        PlanAnalysis analysis = analyzer.analyze(Plans.load("customer_orders_seqscan.json"), SHOP_TABLES);

        assertThat(analysis.analyzed()).isFalse();
        assertThat(analysis.nodeCount()).isEqualTo(2);
        assertThat(analysis.findings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.SEQ_SCAN_ON_LARGE_TABLE);
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
            assertThat(f.node()).isEqualTo("Seq Scan on shop.orders o");
            assertThat(f.message()).contains("1.0M rows of shop.orders", "(o.customer_id = 42)");
            assertThat(f.suggestion()).contains("suggest_indexes");
        });
    }

    @Test
    void costliestNodesUseSelfCostWithoutAnalyze() {
        PlanAnalysis analysis = analyzer.analyze(Plans.load("customer_orders_seqscan.json"), SHOP_TABLES);

        assertThat(analysis.costliestNodes()).extracting(NodeSummary::node)
                .containsExactly("Seq Scan on shop.orders o", "Sort");
        assertThat(analysis.costliestNodes().getFirst().selfCost()).isEqualTo(21388.46);
        assertThat(analysis.costliestNodes().getFirst().detail()).isEqualTo("(o.customer_id = 42)");
        assertThat(analysis.costliestNodes().get(1).selfCost()).isEqualTo(0.07);
    }

    @Test
    void smallTablesAreNotFlagged() {
        PlanAnalysis analysis = analyzer.analyze(Plans.load("customer_orders_seqscan.json"),
                Map.of("shop.orders", 500L));
        assertThat(analysis.findings()).isEmpty();
    }

    @Test
    void fallsBackToPlanRowsWhenTableStatsAreUnknown() {
        PlanAnalysis analysis = analyzer.analyze(Plans.load("country_revenue_hashjoin.json"), Map.of());
        assertThat(analysis.findings()).extracting(Finding::node).containsExactly("Seq Scan on shop.orders o");
        assertThat(analysis.findings().getFirst().severity()).isEqualTo(Severity.MEDIUM);
        assertThat(analysis.findings().getFirst().suggestion()).contains("whole table");
    }

    @Test
    void analyzedPlanRanksBySelfTimeAndReportsTimings() {
        PlanAnalysis analysis = analyzer.analyze(Plans.load("latest_orders_limit.json"), SHOP_TABLES);

        assertThat(analysis.analyzed()).isTrue();
        assertThat(analysis.executionTimeMs()).isEqualTo(156.4);
        assertThat(analysis.planningTimeMs()).isEqualTo(0.21);
        NodeSummary top = analysis.costliestNodes().getFirst();
        assertThat(top.node()).isEqualTo("Sort");
        // 3 loops x 140.6 ms minus the scan's 3 x 45.3 ms
        assertThat(top.selfTimeMs()).isEqualTo(285.9);
    }

    @Test
    void detectsRowEstimateMismatch() {
        PlanAnalysis analysis = analyzer.analyze(Plans.load("n_plus_one_analyzed.json"), SHOP_TABLES);

        assertThat(analysis.findings()).filteredOn(f -> f.type() == FindingType.ROW_ESTIMATE_MISMATCH)
                .extracting(Finding::node)
                .contains("Index Scan using products_pkey on shop.products p")
                .allSatisfy(node -> assertThat(node).isNotNull());
        Finding indexScan = analysis.findings().stream()
                .filter(f -> f.type() == FindingType.ROW_ESTIMATE_MISMATCH && f.node().startsWith("Index Scan"))
                .findFirst().orElseThrow();
        assertThat(indexScan.message()).contains("underestimated rows by 500x", "expected 10, got 5000");
        assertThat(indexScan.suggestion()).contains("ANALYZE shop.products");
    }

    @Test
    void detectsDiskSortHashSpillAndNestedLoopOverSeqScan() {
        PlanAnalysis analysis = analyzer.analyze(Plans.load("n_plus_one_analyzed.json"), SHOP_TABLES);

        assertThat(analysis.findings()).extracting(Finding::type).contains(
                FindingType.SORT_SPILLED_TO_DISK, FindingType.HASH_SPILLED_TO_DISK, FindingType.NESTED_LOOP_HIGH_LOOPS);
        Finding loop = analysis.findings().stream()
                .filter(f -> f.type() == FindingType.NESTED_LOOP_HIGH_LOOPS).findFirst().orElseThrow();
        assertThat(loop.severity()).isEqualTo(Severity.HIGH);
        assertThat(loop.message()).contains("Seq Scan on shop.reviews r", "ran 5000 times");
        Finding sort = analysis.findings().stream()
                .filter(f -> f.type() == FindingType.SORT_SPILLED_TO_DISK).findFirst().orElseThrow();
        assertThat(sort.message()).contains("8240 kB");
    }

    @Test
    void findingsAreOrderedBySeverity() {
        PlanAnalysis analysis = analyzer.analyze(Plans.load("n_plus_one_analyzed.json"), SHOP_TABLES);
        assertThat(analysis.findings()).extracting(Finding::severity).isSortedAccordingTo(Enum::compareTo);
    }

    @Test
    void plannedNestedLoopUsesOuterRowEstimate() {
        String json = """
                [{"Plan": {"Node Type": "Nested Loop", "Total Cost": 100, "Plan Rows": 5000, "Plans": [
                  {"Node Type": "Seq Scan", "Relation Name": "products", "Schema": "shop", "Alias": "p",
                   "Total Cost": 50, "Plan Rows": 5000},
                  {"Node Type": "Index Scan", "Index Name": "orders_pkey", "Relation Name": "orders",
                   "Schema": "shop", "Alias": "o", "Total Cost": 0.5, "Plan Rows": 1}]}}]""";
        PlanAnalysis analysis = analyzer.analyze(Plans.parse(json), Map.of("shop.products", 5000L));
        assertThat(analysis.findings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.NESTED_LOOP_HIGH_LOOPS);
            assertThat(f.severity()).isEqualTo(Severity.LOW);
            assertThat(f.message()).contains("is expected to run 5000 times");
        });
    }

    @Test
    void ignoresNodesThatNeverExecuted() {
        String json = """
                [{"Plan": {"Node Type": "Seq Scan", "Relation Name": "orders", "Schema": "shop",
                  "Total Cost": 18334, "Plan Rows": 1, "Filter": "(customer_id = 1)",
                  "Actual Rows": 0, "Actual Loops": 0, "Actual Total Time": 0}}]""";
        assertThat(analyzer.analyze(Plans.parse(json), SHOP_TABLES).findings()).isEmpty();
    }

    @Test
    void rejectsDocumentsWithoutAPlan() {
        assertThatThrownBy(() -> Plans.parse("[{\"Nope\": 1}]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void humanReadableNumbers() {
        assertThat(PlanAnalyzer.human(999)).isEqualTo("999");
        assertThat(PlanAnalyzer.human(25_000)).isEqualTo("25k");
        assertThat(PlanAnalyzer.human(3_200_000)).isEqualTo("3.2M");
    }
}
