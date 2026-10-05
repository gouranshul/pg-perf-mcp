package io.github.gouranshul.pgperf.analysis;

import java.util.List;

/**
 * Catalog facts about one table, used by the advisors.
 *
 * @param schema        schema name
 * @param name          table name
 * @param estimatedRows {@code pg_class.reltuples}; negative if the table was never analyzed
 * @param indexes       existing indexes with their key columns in order (expression keys omitted)
 */
public record TableInfo(String schema, String name, long estimatedRows, List<ExistingIndex> indexes) {

    public String qualifiedName() {
        return schema + "." + name;
    }

    /**
     * @param name    index name
     * @param columns key columns in index order
     */
    public record ExistingIndex(String name, List<String> columns) {
    }
}
