package io.github.gouranshul.pgperf.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * One node of a PostgreSQL {@code EXPLAIN (FORMAT JSON)} plan, reduced to the fields the
 * analyzers need. Fields that only exist with {@code ANALYZE} are {@code null} otherwise.
 */
public record PlanNode(
        String nodeType,
        String relationName,
        String schema,
        String alias,
        String indexName,
        String parentRelationship,
        String joinType,
        double startupCost,
        double totalCost,
        double planRows,
        Double actualRows,
        Double actualLoops,
        Double actualTotalTimeMs,
        String filter,
        Double rowsRemovedByFilter,
        String indexCond,
        String hashCond,
        String mergeCond,
        String joinFilter,
        List<String> sortKeys,
        String sortSpaceType,
        Long sortSpaceUsedKb,
        Integer hashBatches,
        List<PlanNode> children) {

    public static PlanNode from(JsonNode json) {
        List<PlanNode> children = new ArrayList<>();
        for (JsonNode child : json.path("Plans")) {
            children.add(from(child));
        }
        List<String> sortKeys = new ArrayList<>();
        for (JsonNode key : json.path("Sort Key")) {
            sortKeys.add(key.asString());
        }
        return new PlanNode(
                text(json, "Node Type"),
                text(json, "Relation Name"),
                text(json, "Schema"),
                text(json, "Alias"),
                text(json, "Index Name"),
                text(json, "Parent Relationship"),
                text(json, "Join Type"),
                json.path("Startup Cost").asDouble(0),
                json.path("Total Cost").asDouble(0),
                json.path("Plan Rows").asDouble(0),
                number(json, "Actual Rows"),
                number(json, "Actual Loops"),
                number(json, "Actual Total Time"),
                text(json, "Filter"),
                number(json, "Rows Removed by Filter"),
                text(json, "Index Cond"),
                text(json, "Hash Cond"),
                text(json, "Merge Cond"),
                text(json, "Join Filter"),
                List.copyOf(sortKeys),
                text(json, "Sort Space Type"),
                json.has("Sort Space Used") ? json.path("Sort Space Used").asLong() : null,
                json.has("Hash Batches") ? json.path("Hash Batches").asInt() : null,
                List.copyOf(children));
    }

    public boolean analyzed() {
        return actualLoops != null;
    }

    /** True if the node was part of an ANALYZE run but never executed (e.g. pruned branch). */
    public boolean neverExecuted() {
        return actualLoops != null && actualLoops == 0;
    }

    public boolean isSeqScan() {
        return "Seq Scan".equals(nodeType);
    }

    /** Schema-qualified relation name, or just the relation if the plan was not VERBOSE. */
    public String qualifiedRelation() {
        if (relationName == null) {
            return null;
        }
        return schema == null ? relationName : schema + "." + relationName;
    }

    /** Human-readable label such as {@code Seq Scan on shop.orders o}. */
    public String label() {
        StringBuilder label = new StringBuilder(nodeType);
        if (joinType != null && nodeType.contains("Join") && !"Inner".equals(joinType)) {
            label.insert(0, joinType + " ");
        }
        if (indexName != null) {
            label.append(" using ").append(indexName);
        }
        if (relationName != null) {
            label.append(" on ").append(qualifiedRelation());
            if (alias != null && !alias.equals(relationName)) {
                label.append(' ').append(alias);
            }
        }
        return label.toString();
    }

    /** Cost attributable to this node alone (total cost minus its children's). */
    public double selfCost() {
        double childCost = children.stream().mapToDouble(PlanNode::totalCost).sum();
        return Math.max(0, totalCost - childCost);
    }

    /** Wall-clock time spent in this node alone across all loops, or null without ANALYZE. */
    public Double selfTimeMs() {
        if (actualTotalTimeMs == null || actualLoops == null) {
            return null;
        }
        double childTime = children.stream()
                .filter(c -> c.actualTotalTimeMs() != null && c.actualLoops() != null)
                .mapToDouble(c -> c.actualTotalTimeMs() * c.actualLoops())
                .sum();
        return Math.max(0, actualTotalTimeMs * actualLoops - childTime);
    }

    /** Visits this node and all descendants, depth first. */
    public void walk(Consumer<PlanNode> visitor) {
        visitor.accept(this);
        children.forEach(child -> child.walk(visitor));
    }

    public List<PlanNode> flatten() {
        List<PlanNode> all = new ArrayList<>();
        walk(all::add);
        return all;
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Double number(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }
}
