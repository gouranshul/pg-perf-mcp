package io.github.gouranshul.pgperf.resources;

import io.github.gouranshul.pgperf.config.PgPerfProperties;
import io.github.gouranshul.pgperf.db.DatabaseErrors;
import io.github.gouranshul.pgperf.db.ReadOnlyExecutor;
import io.github.gouranshul.pgperf.db.ReadOnlySession;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Schema metadata as MCP resources, so an assistant can look up tables, columns and indexes
 * without spending tool calls. All lookups are catalog reads inside the read-only executor.
 */
@Component
public class SchemaResources {

    private static final Logger log = LoggerFactory.getLogger(SchemaResources.class);

    private final ReadOnlyExecutor executor;
    private final JsonMapper json;
    private final PgPerfProperties properties;

    public SchemaResources(ReadOnlyExecutor executor, JsonMapper json, PgPerfProperties properties) {
        this.executor = executor;
        this.json = json;
        this.properties = properties;
    }

    public record TableSummary(String table, long estimatedRows, String totalSize, List<String> indexes) {
    }

    public record SchemaOverview(int tableCount, boolean truncated, List<TableSummary> tables) {
    }

    public record Column(String name, String type, boolean nullable, String defaultValue) {
    }

    public record Index(String name, String definition, boolean primary, boolean unique, boolean valid, String size,
            long scans) {
    }

    /**
     * @param indexed whether an index starts with the foreign key columns; unindexed foreign keys
     *                make joins and parent-row deletes slow
     */
    public record ForeignKey(String name, List<String> columns, String referencesTable, String definition,
            boolean indexed) {
    }

    public record TableDetail(String table, long estimatedRows, String totalSize, List<Column> columns,
            List<Index> indexes, List<ForeignKey> foreignKeys) {
    }

    @McpResource(uri = "pg://schema/overview", name = "schema-overview", title = "Schema overview",
            mimeType = "application/json",
            description = "All user tables, largest first, with estimated row counts, total size (table + indexes"
                    + " + TOAST) and index names. Use it to find table names before calling other tools.")
    public String overview() {
        return guarded("pg://schema/overview", () -> executor.run(db -> {
            List<TableSummary> tables = db.client().sql("""
                    SELECT format('%I.%I', n.nspname, c.relname) AS table_name,
                           greatest(c.reltuples, 0)::bigint AS estimated_rows,
                           pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size,
                           ARRAY(SELECT i.relname::text FROM pg_index ix JOIN pg_class i ON i.oid = ix.indexrelid
                                 WHERE ix.indrelid = c.oid ORDER BY 1) AS indexes
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relkind IN ('r', 'p')
                      AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                      AND n.nspname NOT LIKE 'pg\\_toast%' AND n.nspname NOT LIKE 'pg\\_temp%'
                    ORDER BY pg_total_relation_size(c.oid) DESC, 1
                    """)
                    .query((rs, n) -> new TableSummary(rs.getString("table_name"), rs.getLong("estimated_rows"),
                            rs.getString("total_size"), strings(rs.getArray("indexes"))))
                    .list();
            return new SchemaOverview(tables.size(), tables.size() >= executor.maxRows(), tables);
        }));
    }

    @McpResource(uri = "pg://schema/{table}", name = "table-detail", title = "Table detail",
            mimeType = "application/json",
            description = "Columns (type, nullability, default), indexes (definition, size, scan count) and foreign"
                    + " keys (with whether an index supports them) for one table. {table} may be schema-qualified,"
                    + " e.g. pg://schema/shop.orders.")
    public String table(String table) {
        return guarded("pg://schema/" + table, () -> executor.run(db -> describe(db, table)));
    }

    private TableDetail describe(ReadOnlySession db, String requested) {
        record Match(long oid, String name, long rows, String size) {
        }
        List<Match> matches = db.client().sql("""
                SELECT c.oid, format('%I.%I', n.nspname, c.relname) AS table_name,
                       greatest(c.reltuples, 0)::bigint AS estimated_rows,
                       pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind IN ('r', 'p', 'm', 'v')
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                  AND (n.nspname || '.' || c.relname = ? OR c.relname = ?)
                ORDER BY 2
                """)
                .params(requested, requested)
                .query((rs, n) -> new Match(rs.getLong("oid"), rs.getString("table_name"), rs.getLong("estimated_rows"),
                        rs.getString("total_size")))
                .list();
        if (matches.isEmpty()) {
            throw new ResourceException("No table named '" + requested + "'. See pg://schema/overview.");
        }
        if (matches.size() > 1) {
            throw new ResourceException("'" + requested + "' is ambiguous; use one of "
                    + matches.stream().map(Match::name).toList());
        }
        Match match = matches.getFirst();
        long oid = match.oid();

        List<Column> columns = db.client().sql("""
                SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS data_type, NOT a.attnotnull AS nullable,
                       pg_get_expr(d.adbin, d.adrelid) AS default_value
                FROM pg_attribute a
                LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum
                """)
                .param(oid)
                .query((rs, n) -> new Column(rs.getString("attname"), rs.getString("data_type"),
                        rs.getBoolean("nullable"), rs.getString("default_value")))
                .list();

        List<Index> indexes = db.client().sql("""
                SELECT i.relname, pg_get_indexdef(ix.indexrelid) AS definition, ix.indisprimary, ix.indisunique,
                       ix.indisvalid, pg_size_pretty(pg_relation_size(ix.indexrelid)) AS size,
                       coalesce(s.idx_scan, 0) AS scans
                FROM pg_index ix
                JOIN pg_class i ON i.oid = ix.indexrelid
                LEFT JOIN pg_stat_all_indexes s ON s.indexrelid = ix.indexrelid
                WHERE ix.indrelid = ?
                ORDER BY i.relname
                """)
                .param(oid)
                .query((rs, n) -> new Index(rs.getString("relname"), rs.getString("definition"),
                        rs.getBoolean("indisprimary"), rs.getBoolean("indisunique"), rs.getBoolean("indisvalid"),
                        rs.getString("size"), rs.getLong("scans")))
                .list();

        List<ForeignKey> foreignKeys = db.client().sql("""
                SELECT con.conname, pg_get_constraintdef(con.oid) AS definition,
                       ARRAY(SELECT a.attname::text
                             FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                             JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum
                             ORDER BY k.ord) AS fk_columns,
                       con.confrelid::regclass::text AS references_table,
                       EXISTS (SELECT 1 FROM pg_index ix
                               WHERE ix.indrelid = con.conrelid
                                 AND (ix.indkey::int2[])[0:cardinality(con.conkey) - 1] @> con.conkey) AS indexed
                FROM pg_constraint con
                WHERE con.conrelid = ? AND con.contype = 'f'
                ORDER BY con.conname
                """)
                .param(oid)
                .query((rs, n) -> new ForeignKey(rs.getString("conname"), strings(rs.getArray("fk_columns")),
                        rs.getString("references_table"), rs.getString("definition"), rs.getBoolean("indexed")))
                .list();

        return new TableDetail(match.name(), match.rows(), match.size(), columns, indexes, foreignKeys);
    }

    /** Runs a resource read; failures become MCP errors with safe, helpful messages. */
    private String guarded(String uri, Supplier<Object> body) {
        try {
            return json.writeValueAsString(body.get());
        } catch (ResourceException e) {
            throw McpError.builder(ErrorCodes.RESOURCE_NOT_FOUND).message(e.getMessage()).build();
        } catch (DataAccessException e) {
            log.warn("Resource {} failed with a database error", uri, e);
            throw McpError.builder(ErrorCodes.INTERNAL_ERROR)
                    .message(DatabaseErrors.describe(e, properties.query().statementTimeout()))
                    .build();
        } catch (RuntimeException e) {
            String reference = UUID.randomUUID().toString();
            log.error("Resource {} failed unexpectedly (reference {})", uri, reference, e);
            throw McpError.builder(ErrorCodes.INTERNAL_ERROR)
                    .message("Internal error reading " + uri + ". Reference: " + reference)
                    .build();
        }
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static final class ResourceException extends RuntimeException {

        ResourceException(String message) {
            super(message);
        }
    }
}
