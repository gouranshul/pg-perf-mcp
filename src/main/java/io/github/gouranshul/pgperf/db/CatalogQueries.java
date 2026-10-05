package io.github.gouranshul.pgperf.db;

import io.github.gouranshul.pgperf.analysis.TableInfo;
import io.github.gouranshul.pgperf.analysis.TableInfo.ExistingIndex;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Read-only catalog lookups shared by several tools. All SQL here is static and parameterized. */
@Component
public class CatalogQueries {

    public boolean extensionInstalled(ReadOnlySession db, String extension) {
        return db.client().sql("SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = ?)")
                .param(extension)
                .query(Boolean.class)
                .single();
    }

    public int serverVersionNum(ReadOnlySession db) {
        return db.client().sql("SELECT current_setting('server_version_num')::int").query(Integer.class).single();
    }

    /**
     * Row estimates and valid, non-partial btree-usable indexes for the given tables.
     *
     * @param qualifiedNames names such as {@code shop.orders}
     */
    public Map<String, TableInfo> tables(ReadOnlySession db, Collection<String> qualifiedNames) {
        if (qualifiedNames.isEmpty()) {
            return Map.of();
        }
        record Row(String schema, String table, long rows, String index, List<String> columns) {
        }
        List<Row> rows = db.client().sql("""
                SELECT n.nspname AS schema_name, c.relname AS table_name, c.reltuples::bigint AS estimated_rows,
                       i.relname AS index_name,
                       ARRAY(SELECT a.attname::text
                             FROM unnest(ix.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
                             JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
                             ORDER BY k.ord) AS index_columns
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_index ix ON ix.indrelid = c.oid AND ix.indisvalid AND ix.indpred IS NULL
                LEFT JOIN pg_class i ON i.oid = ix.indexrelid
                WHERE c.relkind IN ('r', 'p', 'm')
                  AND (n.nspname || '.' || c.relname) = ANY (?)
                ORDER BY 1, 2, 4
                """)
                .param(qualifiedNames.toArray(String[]::new))
                .query((rs, n) -> new Row(rs.getString("schema_name"), rs.getString("table_name"),
                        rs.getLong("estimated_rows"), rs.getString("index_name"), strings(rs.getArray("index_columns"))))
                .list();

        Map<String, List<ExistingIndex>> indexes = new LinkedHashMap<>();
        Map<String, Row> firstRow = new LinkedHashMap<>();
        for (Row row : rows) {
            String key = row.schema() + "." + row.table();
            firstRow.putIfAbsent(key, row);
            List<ExistingIndex> list = indexes.computeIfAbsent(key, k -> new ArrayList<>());
            if (row.index() != null) {
                list.add(new ExistingIndex(row.index(), row.columns()));
            }
        }
        Map<String, TableInfo> result = new LinkedHashMap<>();
        firstRow.forEach((key, row) -> result.put(key,
                new TableInfo(row.schema(), row.table(), row.rows(), List.copyOf(indexes.get(key)))));
        return result;
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }
}
