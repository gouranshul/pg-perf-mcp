package io.github.gouranshul.pgperf.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gouranshul.pgperf.analysis.TableHealthRules.Stats;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TableHealthRulesTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    private final TableHealthRules rules = new TableHealthRules();

    @Test
    void healthyTableHasNoWarnings() {
        Stats s = new Stats("shop.products", 50_000, 100, 500, null, NOW.minusHours(2), null, NOW.minusHours(2),
                10, 500_000, 90_000, true);
        assertThat(rules.warnings(s, NOW)).isEmpty();
        assertThat(s.deadRatio()).isCloseTo(0.002, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void flagsDeadTuplesAndDisabledAutovacuum() {
        Stats s = new Stats("shop.orders", 1_000_000, 250_000, 0, null, null, NOW.minusDays(1), null,
                5, 5_000_000, 100, false);
        assertThat(rules.warnings(s, NOW))
                .anySatisfy(w -> assertThat(w).contains("Autovacuum is disabled", "RESET (autovacuum_enabled)"))
                .anySatisfy(w -> assertThat(w).contains("20% of rows are dead", "Last vacuum: never"));
    }

    @Test
    void flagsNeverAnalyzedTables() {
        Stats s = new Stats("shop.reviews", 250_000, 0, 250_000, null, null, null, null, 0, 0, 0, true);
        assertThat(rules.warnings(s, NOW)).singleElement().asString().contains("never been analyzed");
    }

    @Test
    void flagsStaleStatistics() {
        Stats s = new Stats("shop.orders", 100_000, 0, 40_000, null, null, null, NOW.minusDays(1), 0, 0, 10, true);
        assertThat(rules.warnings(s, NOW)).singleElement().asString().contains("40% of rows changed");
    }

    @Test
    void flagsSequentialScanHeavyLargeTables() {
        Stats s = new Stats("shop.order_items", 3_000_000, 0, 0, null, NOW, null, NOW, 400, 1_200_000_000L, 12, true);
        assertThat(rules.warnings(s, NOW)).singleElement().asString()
                .contains("Mostly sequential scans (400 seq vs 12 index scans, ~3000000 rows read");
    }

    @Test
    void flagsAutovacuumFallingBehind() {
        Stats s = new Stats("shop.orders", 100_000, 9_000, 0, null, NOW.minusDays(30), null, NOW, 0, 0, 10, true);
        assertThat(rules.warnings(s, NOW)).singleElement().asString().contains("Not vacuumed for 30 days");
    }

    @Test
    void emptyTableHasNoDeadRatio() {
        Stats s = new Stats("shop.empty", 0, 0, 0, null, null, null, null, 0, 0, 0, true);
        assertThat(s.deadRatio()).isNull();
        assertThat(rules.warnings(s, NOW)).isEmpty();
    }
}
