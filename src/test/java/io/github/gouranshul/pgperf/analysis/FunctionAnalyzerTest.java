package io.github.gouranshul.pgperf.analysis;

import static io.github.gouranshul.pgperf.analysis.NestedPlanTest.SEQ_SCAN_ON_REVIEWS;
import static io.github.gouranshul.pgperf.analysis.NestedPlanTest.notice;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gouranshul.pgperf.analysis.FunctionAnalysis.FunctionCall;
import io.github.gouranshul.pgperf.analysis.FunctionAnalysis.FunctionSummary;
import io.github.gouranshul.pgperf.analysis.FunctionAnalysis.NestedStatement;
import io.github.gouranshul.pgperf.analysis.FunctionAnalysis.StatementStats;
import io.github.gouranshul.pgperf.analysis.PlanAnalysis.Finding;
import io.github.gouranshul.pgperf.analysis.PlanAnalysis.FindingType;
import io.github.gouranshul.pgperf.analysis.PlanAnalysis.Severity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FunctionAnalyzerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, Long> ROWS = Map.of("shop.reviews", 250_000L);

    private static final String RATING_SOURCE = """
            CREATE OR REPLACE FUNCTION shop.product_rating(p_product_id bigint)
             RETURNS numeric LANGUAGE plpgsql STABLE
            AS $function$
            DECLARE
                v_rating numeric;
            BEGIN
                SELECT avg(rating) INTO v_rating FROM shop.reviews WHERE product_id = p_product_id;
                RETURN round(v_rating, 2);
            END
            $function$""";
    private static final String CLV_SOURCE = """
            CREATE OR REPLACE FUNCTION shop.customer_lifetime_value(p_customer_id bigint)
             RETURNS numeric LANGUAGE sql
            AS $function$
                SELECT coalesce(sum(total), 0) FROM shop.orders WHERE customer_id = p_customer_id
            $function$""";
    /** PL/pgSQL drops the INTO target from the text it executes, leaving spaces behind. */
    private static final String RATING_STATEMENT =
            "SELECT avg(rating)        FROM shop.reviews WHERE product_id = p_product_id";

    private final FunctionAnalyzer analyzer = new FunctionAnalyzer();

    private static ExplainResult outer(double executionMs) {
        return Plans.parse("""
                [{"Plan": {"Node Type": "Result", "Total Cost": 0.26, "Plan Rows": 1,
                           "Actual Total Time": %s, "Actual Rows": 1, "Actual Loops": 1},
                  "Planning Time": 0.1, "Execution Time": %s}]""".formatted(executionMs, executionMs));
    }

    private static FunctionCall rating(long calls, double totalMs) {
        return new FunctionCall("shop.product_rating", "p_product_id bigint", "plpgsql", "s", calls, totalMs,
                totalMs, RATING_SOURCE);
    }

    private static NestedPlan ratingPlan(long queryId, double durationMs) {
        return NestedPlan.parse(notice(queryId, RATING_STATEMENT, durationMs, SEQ_SCAN_ON_REVIEWS), JSON).orElseThrow();
    }

    @Nested
    class PerRowFunction {

        private final FunctionAnalysis analysis = analyzer.analyze(outer(100),
                List.of(rating(20, 90)),
                List.of(new StatementStats(111, "SELECT avg(rating) FROM shop.reviews WHERE product_id = p_product_id",
                        20, 85)),
                List.of(ratingPlan(111, 3.0), ratingPlan(111, 6.5), ratingPlan(111, 4.0)),
                ROWS);

        @Test
        void summarizesTheFunction() {
            FunctionSummary f = analysis.functions().getFirst();
            assertThat(f.name()).isEqualTo("shop.product_rating");
            assertThat(f.volatility()).isEqualTo("STABLE");
            assertThat(f.calls()).isEqualTo(20);
            assertThat(f.meanTimeMs()).isEqualTo(4.5);
            assertThat(f.percentOfExecution()).isEqualTo(90.0);
            assertThat(f.definition()).contains("INTO v_rating");
        }

        @Test
        void joinsStatementCountersWithTheSlowestCapturedPlan() {
            NestedStatement s = analysis.statements().getFirst();
            assertThat(analysis.statements()).hasSize(1);
            assertThat(s.queryId()).isEqualTo(111);
            assertThat(s.function()).isEqualTo("shop.product_rating");
            assertThat(s.query()).isEqualTo("SELECT avg(rating) FROM shop.reviews WHERE product_id = p_product_id");
            assertThat(s.calls()).isEqualTo(20);
            assertThat(s.meanTimeMs()).isEqualTo(4.25);
            assertThat(s.percentOfExecution()).isEqualTo(85.0);
            assertThat(s.capturedPlanMs()).isEqualTo(6.5);
            assertThat(s.plan().findings()).extracting(Finding::type).contains(FindingType.SEQ_SCAN_ON_LARGE_TABLE);
        }

        @Test
        void reportsDominanceThePerRowCallAndTheScanInsideTheFunction() {
            assertThat(analysis.findings()).extracting(Finding::type).containsExactly(
                    FindingType.FUNCTION_DOMINATES_QUERY,
                    FindingType.FUNCTION_CALLED_PER_ROW,
                    FindingType.SEQ_SCAN_ON_LARGE_TABLE);
            assertThat(analysis.findings()).allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.HIGH));
            assertThat(analysis.findings().get(1).message()).contains("ran 20 times", "once per row");
            assertThat(analysis.findings().get(2).node())
                    .isEqualTo("inside shop.product_rating: Seq Scan on shop.reviews");
        }

        @Test
        void keepsTheOuterPlan() {
            assertThat(analysis.executionTimeMs()).isEqualTo(100.0);
            assertThat(analysis.outerPlan().analyzed()).isTrue();
        }
    }

    @Test
    void moderateShareAndFewCallsAreMediumOrNothing() {
        FunctionAnalysis analysis = analyzer.analyze(outer(100), List.of(rating(3, 30)), List.of(), List.of(), ROWS);

        assertThat(analysis.findings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.FUNCTION_DOMINATES_QUERY);
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.message()).contains("30%", "3 calls");
        });
        assertThat(analyzer.analyze(outer(100), List.of(rating(1, 5)), List.of(), List.of(), ROWS).findings())
                .isEmpty();
    }

    @Test
    void manyCheapCallsAreAMediumPerRowFinding() {
        FunctionAnalysis analysis = analyzer.analyze(outer(100), List.of(rating(500, 10)), List.of(), List.of(), ROWS);

        assertThat(analysis.findings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.FUNCTION_CALLED_PER_ROW);
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
        });
    }

    @Test
    void volatileSqlOrPlpgsqlFunctionsThatOnlyReadAreFlagged() {
        FunctionCall clv = new FunctionCall("shop.customer_lifetime_value", "p_customer_id bigint", "sql", "v", 1, 1,
                1, CLV_SOURCE);
        FunctionCall cFunction = new FunctionCall("public.c_func", "", "c", "v", 1, 1, 1, null);

        List<Finding> findings = analyzer.analyze(outer(100), List.of(clv, cFunction), List.of(), List.of(), ROWS)
                .findings();

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.VOLATILE_FUNCTION_ONLY_READS);
            assertThat(f.severity()).isEqualTo(Severity.LOW);
            assertThat(f.node()).isEqualTo("shop.customer_lifetime_value");
            assertThat(f.suggestion()).contains("STABLE");
        });
    }

    @Test
    void statementsWithoutCapturedPlansAndPlansWithoutCountersAreBothListed() {
        FunctionAnalysis analysis = analyzer.analyze(outer(100), List.of(rating(2, 10)),
                List.of(new StatementStats(222, "SELECT 1 FROM shop.reviews WHERE id = $1", 2, 0.5)),
                List.of(ratingPlan(0, 7)),
                ROWS);

        assertThat(analysis.statements()).hasSize(2);
        NestedStatement fromPlanOnly = analysis.statements().stream().filter(s -> s.calls() == null).findFirst()
                .orElseThrow();
        assertThat(fromPlanOnly.queryId()).isNull();
        assertThat(fromPlanOnly.capturedPlanMs()).isEqualTo(7.0);
        assertThat(fromPlanOnly.percentOfExecution()).isNull();
        NestedStatement countedOnly = analysis.statements().stream().filter(s -> s.calls() != null).findFirst()
                .orElseThrow();
        assertThat(countedOnly.plan()).isNull();
        assertThat(countedOnly.meanTimeMs()).isEqualTo(0.25);
    }

    @Test
    void withoutAnAnalyzedOuterPlanPercentagesAreUnknown() {
        ExplainResult estimated = Plans.parse("""
                [{"Plan": {"Node Type": "Result", "Total Cost": 0.26, "Plan Rows": 1}}]""");

        FunctionAnalysis analysis = analyzer.analyze(estimated, List.of(rating(1, 50)), List.of(), List.of(), ROWS);

        assertThat(analysis.functions().getFirst().percentOfExecution()).isNull();
        assertThat(analysis.findings()).isEmpty();
    }

    @Test
    void percentagesAreCappedAtOneHundred() {
        // Timers for the function and the whole query are separate, so the function can read a bit higher.
        assertThat(analyzer.analyze(outer(10), List.of(rating(1, 10.4)), List.of(), List.of(), ROWS)
                .functions().getFirst().percentOfExecution()).isEqualTo(100.0);
    }

    @Test
    void longDefinitionsAreTruncated() {
        String longSource = "CREATE FUNCTION f() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$ -- " + "x".repeat(5000);
        FunctionCall f = new FunctionCall("public.f", "", "sql", "s", 1, 1, 1, longSource);

        String definition = analyzer.analyze(outer(100), List.of(f), List.of(), List.of(), ROWS).functions()
                .getFirst().definition();

        assertThat(definition).hasSize(FunctionAnalyzer.MAX_DEFINITION_LENGTH).endsWith("…");
    }

    @Nested
    class Attribution {

        private final FunctionCall clv = new FunctionCall("shop.customer_lifetime_value", "", "sql", "v", 1, 1, 1,
                CLV_SOURCE);

        @Test
        void matchesWordsInOrderDespiteTheRemovedIntoClause() {
            assertThat(FunctionAnalyzer.attribute(RATING_STATEMENT, List.of(clv, rating(1, 1))))
                    .isEqualTo("shop.product_rating");
            assertThat(FunctionAnalyzer.attribute(
                    "SELECT coalesce(sum(total), $1) FROM shop.orders WHERE customer_id = p_customer_id",
                    List.of(rating(1, 1), clv)))
                    .isEqualTo("shop.customer_lifetime_value");
        }

        @Test
        void aSingleCalledFunctionOwnsEveryStatement() {
            assertThat(FunctionAnalyzer.attribute("SELECT something_else FROM shop.elsewhere", List.of(clv)))
                    .isEqualTo("shop.customer_lifetime_value");
        }

        @Test
        void unknownWhenNoSourceMatchesAndSeveralFunctionsRan() {
            assertThat(FunctionAnalyzer.attribute("SELECT something_else FROM shop.elsewhere",
                    List.of(clv, rating(1, 1)))).isNull();
            assertThat(FunctionAnalyzer.attribute(null, List.of(clv))).isNull();
            assertThat(FunctionAnalyzer.attribute("SELECT 1", List.of())).isNull();
        }

        @Test
        void tightestMatchWins() {
            List<String> needle = List.of("select", "a", "from", "t");
            assertThat(FunctionAnalyzer.subsequenceSpan(needle, List.of("select", "a", "from", "t"))).isEqualTo(4);
            assertThat(FunctionAnalyzer.subsequenceSpan(needle,
                    List.of("select", "x", "select", "a", "y", "from", "t"))).isEqualTo(5);
            assertThat(FunctionAnalyzer.subsequenceSpan(needle, List.of("select", "from", "a", "t"))).isEqualTo(-1);
            assertThat(FunctionAnalyzer.subsequenceSpan(List.of(), List.of("select"))).isEqualTo(-1);
        }
    }

    @Test
    void spellsOutVolatility() {
        assertThat(FunctionAnalyzer.volatility("i")).isEqualTo("IMMUTABLE");
        assertThat(FunctionAnalyzer.volatility("s")).isEqualTo("STABLE");
        assertThat(FunctionAnalyzer.volatility("v")).isEqualTo("VOLATILE");
        assertThat(FunctionAnalyzer.volatility(null)).isNull();
    }
}
