package io.github.gouranshul.pgperf.analysis;

import java.util.List;

/**
 * Output of {@link IndexAdvisor}.
 *
 * @param suggestions candidate indexes, most impactful first
 * @param notes       observations that do not translate into a plain btree index
 */
public record IndexAdvice(List<Suggestion> suggestions, List<String> notes) {

    /**
     * One candidate index. The statement is text for a human to review: it is never executed.
     *
     * @param table     schema-qualified table
     * @param columns   key columns in order, with " DESC" where the sort order matters
     * @param statement {@code CREATE INDEX CONCURRENTLY ...} to review and run manually
     * @param reasons   why the plan points at this index
     */
    public record Suggestion(String table, List<String> columns, String statement, List<String> reasons) {
    }
}
