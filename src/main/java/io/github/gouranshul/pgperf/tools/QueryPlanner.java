package io.github.gouranshul.pgperf.tools;

import io.github.gouranshul.pgperf.analysis.ExplainResult;
import io.github.gouranshul.pgperf.analysis.PlanNode;
import io.github.gouranshul.pgperf.db.CatalogQueries;
import io.github.gouranshul.pgperf.db.ReadOnlySession;
import io.github.gouranshul.pgperf.guard.ValidatedSql;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Runs EXPLAIN for SQL that has already passed the guard. */
@Component
class QueryPlanner {

    /** {@code EXPLAIN (GENERIC_PLAN)} for queries with $n placeholders arrived in PostgreSQL 16. */
    private static final int GENERIC_PLAN_MIN_VERSION = 160000;

    private final CatalogQueries catalog;
    private final JsonMapper json;

    QueryPlanner(CatalogQueries catalog, JsonMapper json) {
        this.catalog = catalog;
        this.json = json;
    }

    /**
     * Plans (and with {@code analyze}, executes) the validated statement inside the caller's
     * read-only, rolled-back transaction.
     */
    ExplainResult explain(ReadOnlySession db, ValidatedSql sql, boolean analyze) {
        String options;
        if (sql.hasParameters()) {
            if (analyze) {
                throw new ToolException("The query contains $" + sql.parameterCount() + "-style placeholders, so it"
                        + " cannot be executed with analyze=true. Replace them with realistic values, or use"
                        + " analyze=false for a generic plan.");
            }
            if (catalog.serverVersionNum(db) < GENERIC_PLAN_MIN_VERSION) {
                throw new ToolException("Queries with $n placeholders need PostgreSQL 16+ (EXPLAIN GENERIC_PLAN)."
                        + " Replace the placeholders with realistic values.");
            }
            options = "GENERIC_PLAN, VERBOSE, FORMAT JSON";
        } else if (analyze) {
            options = "ANALYZE, BUFFERS, VERBOSE, FORMAT JSON";
        } else {
            options = "VERBOSE, FORMAT JSON";
        }
        // Safe to concatenate: the guard proved this is exactly one SELECT statement.
        String explain = "EXPLAIN (" + options + ") " + sql.sql();
        String plan = sql.hasParameters() ? db.queryForStringSimpleProtocol(explain) : db.queryForString(explain);
        return ExplainResult.parse(json.readTree(plan));
    }

    /** Schema-qualified names of every table the plan reads. */
    static Set<String> relations(ExplainResult plan) {
        Set<String> names = new LinkedHashSet<>();
        for (PlanNode node : plan.root().flatten()) {
            if (node.relationName() != null && node.schema() != null) {
                names.add(node.qualifiedRelation());
            }
        }
        return names;
    }
}
