package io.github.gouranshul.pgperf.analysis;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The plan of one statement executed inside a database function, as reported by
 * {@code auto_explain} ({@code log_format = json}, {@code log_analyze = on}).
 *
 * @param queryId    {@code Query Identifier}, the same id {@code pg_stat_statements} uses; 0 if absent
 * @param queryText  the statement as written in the function body
 * @param durationMs how long this execution took
 * @param plan       the plan with actual rows and timings
 */
public record NestedPlan(long queryId, String queryText, double durationMs, ExplainResult plan) {

    /** auto_explain's message: {@code duration: 1.234 ms  plan:} followed by the JSON document. */
    private static final Pattern NOTICE = Pattern.compile("^duration: ([0-9.]+) ms\\s+plan:\\s*(\\{.*})\\s*$",
            Pattern.DOTALL);

    /** Parses one notice; empty for notices that are not auto_explain JSON plans. */
    public static Optional<NestedPlan> parse(String notice, JsonMapper json) {
        Matcher m = NOTICE.matcher(notice);
        if (!m.matches()) {
            return Optional.empty();
        }
        JsonNode doc;
        try {
            doc = json.readTree(m.group(2));
        } catch (JacksonException e) {
            return Optional.empty();
        }
        JsonNode plan = doc.path("Plan");
        if (plan.isMissingNode()) {
            return Optional.empty();
        }
        double duration = Double.parseDouble(m.group(1));
        return Optional.of(new NestedPlan(
                doc.path("Query Identifier").asLong(0),
                doc.path("Query Text").asString("").strip(),
                duration,
                new ExplainResult(PlanNode.from(plan), null, duration)));
    }
}
