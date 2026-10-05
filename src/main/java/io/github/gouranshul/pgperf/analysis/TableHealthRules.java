package io.github.gouranshul.pgperf.analysis;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns raw {@code pg_stat_user_tables} numbers into plain-language warnings. Pure and thread safe.
 */
public final class TableHealthRules {

    static final double DEAD_RATIO_WARN = 0.10;
    static final long DEAD_TUPLES_MIN = 1_000;
    static final double MODIFIED_SINCE_ANALYZE_WARN = 0.20;
    static final long LARGE_TABLE_ROWS = PlanAnalyzer.LARGE_TABLE_ROWS;
    static final Duration STALE_VACUUM = Duration.ofDays(7);

    /**
     * Statistics for one table, as read from the catalog.
     *
     * @param autovacuumEnabled false when {@code autovacuum_enabled = false} is set on the table
     */
    public record Stats(String table, long liveTuples, long deadTuples, long modifiedSinceAnalyze,
            OffsetDateTime lastVacuum, OffsetDateTime lastAutovacuum, OffsetDateTime lastAnalyze,
            OffsetDateTime lastAutoanalyze, long seqScans, long seqTuplesRead, long idxScans,
            boolean autovacuumEnabled) {

        public Double deadRatio() {
            long total = liveTuples + deadTuples;
            return total == 0 ? null : (double) deadTuples / total;
        }

        OffsetDateTime latestVacuum() {
            return latest(lastVacuum, lastAutovacuum);
        }

        OffsetDateTime latestAnalyze() {
            return latest(lastAnalyze, lastAutoanalyze);
        }

        private static OffsetDateTime latest(OffsetDateTime a, OffsetDateTime b) {
            if (a == null) {
                return b;
            }
            return b == null || a.isAfter(b) ? a : b;
        }
    }

    public List<String> warnings(Stats s, OffsetDateTime now) {
        List<String> warnings = new ArrayList<>();
        if (!s.autovacuumEnabled()) {
            warnings.add("Autovacuum is disabled on this table (autovacuum_enabled = false), so dead tuples are"
                    + " never cleaned up automatically. Re-enable it: ALTER TABLE " + s.table()
                    + " RESET (autovacuum_enabled);");
        }
        Double deadRatio = s.deadRatio();
        if (deadRatio != null && deadRatio >= DEAD_RATIO_WARN && s.deadTuples() >= DEAD_TUPLES_MIN) {
            OffsetDateTime vacuumed = s.latestVacuum();
            warnings.add("%.0f%% of rows are dead (%d dead tuples), which bloats scans and indexes. Last vacuum: %s."
                    .formatted(100 * deadRatio, s.deadTuples(), vacuumed == null ? "never" : vacuumed)
                    + " Run VACUUM (ANALYZE) " + s.table() + "; and check autovacuum settings.");
        }
        if (s.latestAnalyze() == null && s.liveTuples() + s.deadTuples() > DEAD_TUPLES_MIN) {
            warnings.add("Table has never been analyzed, so the planner is guessing row counts. Run ANALYZE "
                    + s.table() + ";");
        } else if (s.liveTuples() > 0
                && (double) s.modifiedSinceAnalyze() / s.liveTuples() >= MODIFIED_SINCE_ANALYZE_WARN) {
            warnings.add("%.0f%% of rows changed since the last ANALYZE; planner statistics may be stale."
                    .formatted(100.0 * s.modifiedSinceAnalyze() / s.liveTuples()));
        }
        if (s.liveTuples() >= LARGE_TABLE_ROWS && s.seqScans() > 0 && s.seqScans() > s.idxScans()) {
            long avgRowsPerScan = s.seqTuplesRead() / s.seqScans();
            warnings.add(("Mostly sequential scans (%d seq vs %d index scans, ~%d rows read per seq scan) on a large"
                    + " table. Look for missing indexes with top_slow_queries and suggest_indexes.")
                    .formatted(s.seqScans(), s.idxScans(), avgRowsPerScan));
        }
        OffsetDateTime vacuumed = s.latestVacuum();
        if (s.autovacuumEnabled() && s.deadTuples() >= DEAD_TUPLES_MIN && vacuumed != null
                && Duration.between(vacuumed, now).compareTo(STALE_VACUUM) > 0
                && deadRatio != null && deadRatio >= DEAD_RATIO_WARN / 2) {
            warnings.add("Not vacuumed for %d days despite dead tuples; autovacuum may be falling behind."
                    .formatted(Duration.between(vacuumed, now).toDays()));
        }
        return List.copyOf(warnings);
    }
}
