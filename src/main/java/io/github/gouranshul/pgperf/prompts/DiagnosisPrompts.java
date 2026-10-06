package io.github.gouranshul.pgperf.prompts;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

/** Guided workflows that chain the tools in a sensible order. */
@Component
public class DiagnosisPrompts {

    @McpPrompt(name = "diagnose_slow_database", title = "Diagnose a slow database",
            description = "Step-by-step investigation: find the most expensive queries, explain the worst one,"
                    + " suggest indexes, check table health, then summarize prioritized actions.")
    public GetPromptResult diagnoseSlowDatabase(
            @McpArg(name = "focus", required = false,
                    description = "Optional table name or symptom to focus on, e.g. 'orders' or 'checkout page'")
            String focus) {
        String focusLine = focus == null || focus.isBlank()
                ? ""
                : "\nThe user is particularly concerned about: " + focus.strip() + ". Prioritize findings related to it.\n";
        String text = """
                Diagnose why this PostgreSQL database is slow, using the pg-perf tools. Everything is read-only;
                never suggest that you ran a change yourself.
                %s
                1. Call top_slow_queries (orderBy=total_time). Note the statements that dominate total time.
                   Call it again with orderBy=calls to spot N+1 patterns (cheap statements run thousands of times).
                2. Take the worst statement. If it contains $1-style placeholders, replace them with realistic
                   values (read pg://schema/{table} for column types if needed). Call explain_query with
                   analyze=false first; use analyze=true only if the plan alone is inconclusive.
                3. Call suggest_indexes on the same statement. Prefer suggestions with usedByPlanner=true and a large
                   improvementPercent. Mention notes about LIKE '%%...%%' searches or low-selectivity filters.
                4. Call table_health for each table in the plan. Flag dead-tuple bloat, disabled autovacuum and
                   stale statistics.
                5. If slow statements call user-defined functions, call slow_functions, then explain_function with a
                   SELECT that calls the worst one with realistic arguments. EXPLAIN of the calling query hides
                   what runs inside a function; explain_function shows each statement inside it and its plan.
                6. Optionally call unused_indexes (write overhead) and blocking_sessions (if the symptom is hangs
                   or timeouts rather than slow queries).

                Finish with a summary:
                - Top problems, each with evidence (numbers from the tools) and impact.
                - Prioritized actions, highest impact and lowest risk first. Give exact SQL to review
                  (e.g. CREATE INDEX CONCURRENTLY ...), and say it must be run by a human, ideally off-peak.
                - What you could not verify and what to measure next.
                """.formatted(focusLine);
        return new GetPromptResult("Guided slow-database diagnosis",
                List.of(new PromptMessage(Role.USER, new TextContent(text))));
    }
}
