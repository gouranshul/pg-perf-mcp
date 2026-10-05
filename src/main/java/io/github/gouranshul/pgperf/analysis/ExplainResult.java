package io.github.gouranshul.pgperf.analysis;

import tools.jackson.databind.JsonNode;

/**
 * A parsed {@code EXPLAIN (FORMAT JSON)} result.
 *
 * @param root            the top plan node
 * @param planningTimeMs  planning time, only present with ANALYZE (or SUMMARY)
 * @param executionTimeMs execution time, only present with ANALYZE
 */
public record ExplainResult(PlanNode root, Double planningTimeMs, Double executionTimeMs) {

    /** Parses the JSON array Postgres returns for {@code EXPLAIN (FORMAT JSON)}. */
    public static ExplainResult parse(JsonNode explainJson) {
        JsonNode top = explainJson.isArray() ? explainJson.path(0) : explainJson;
        JsonNode plan = top.path("Plan");
        if (plan.isMissingNode()) {
            throw new IllegalArgumentException("Not an EXPLAIN (FORMAT JSON) document: no \"Plan\" field");
        }
        return new ExplainResult(PlanNode.from(plan),
                top.has("Planning Time") ? top.path("Planning Time").asDouble() : null,
                top.has("Execution Time") ? top.path("Execution Time").asDouble() : null);
    }

    public boolean analyzed() {
        return root.analyzed();
    }
}
