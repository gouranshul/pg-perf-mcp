# 0001. Read-only by design, enforced in depth

- Status: accepted
- Date: 2026-10-05

## Context

pg-perf-mcp hands an AI assistant a way to run SQL against a real database. The assistant's input
is untrusted: it may come from a prompt-injected document, a confused model or a malicious user.
A single check, however careful, will eventually have a bug. A missed `DELETE`, a `pg_sleep(3600)`
or `COPY ... TO PROGRAM` must not be one bug away.

## Decision

Make writes and runaway queries impossible through independent layers, each sufficient on its own
for the most important property (no writes):

| # | Layer | Where | Stops |
|---|-------|-------|-------|
| 1 | **SQL guard** | `guard.SqlGuard` | Anything but one plain `SELECT`: DML/DDL, multiple statements, data-modifying CTEs, `SELECT INTO`, `FOR UPDATE/SHARE`, `COPY`, `DO`, dangerous functions (`pg_sleep*`, `*_file`, `lo_*`, `dblink*`, `set_config`, `pg_terminate_backend`, `query_to_xml`, `hypopg*`, ...). |
| 2 | **Read-only role** | `docker/init.sql` | `mcp_readonly` has only `SELECT` on the app schema plus `pg_read_all_stats`/`pg_monitor`; `ALTER ROLE ... SET default_transaction_read_only = on` and `statement_timeout = '5s'` apply even if the app is misconfigured. |
| 3 | **Read-only connections** | `application.yml` (Hikari) | `readOnly=true` and `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY` on every pooled connection. |
| 4 | **Rolled-back read-only transaction per call** | `db.ReadOnlyExecutor` | Every tool call runs in `SET TRANSACTION READ ONLY` (Spring `enforceReadOnly`) and is rolled back even on success. `statement_timeout` and `lock_timeout` are set with `set_config(..., is_local => true)` so they cannot leak to the next call. |
| 5 | **Result caps** | `db.ReadOnlyExecutor` | JDBC `maxRows` (default 200) bounds every result; JDBC query timeout is a client-side backstop. |

Around these sit the perimeter controls: a bearer API key on the endpoint, an audit line per call
and a rejection counter for monitoring abuse.

### The guard uses two independent readers

The guard accepts SQL only if **both** agree:

1. `PgLexer`, a small tokenizer that follows PostgreSQL's lexical rules exactly: nested
   `/* /* */ */` comments, `E'...\''` escapes, `standard_conforming_strings` (a backslash is a
   literal in `'...'`), dollar quoting, `U&` strings and quoted identifiers. It splits statements
   and finds function calls (`identifier (` including schema-qualified and quoted names).
2. JSqlParser, which builds an AST to check statement type, every CTE body, `INTO` and locking
   clauses at every nesting level.

Using only a general-purpose parser leaves room for *parser differentials*. Example:
`SELECT 'abc\', pg_sleep(100) --'` is a single string literal to a parser that treats `\'` as an
escape, but PostgreSQL reads the string `'abc\'`, then a live `pg_sleep(100)`, then a comment. The
PostgreSQL-faithful lexer sees the call and rejects it (`SqlGuardTest`). The executor also forces
`standard_conforming_strings = on` per transaction so the lexer's assumption always holds.

The guard **fails closed**: SQL that either reader cannot parse is rejected.

## Consequences

- Some valid read-only SQL is refused (e.g. syntax JSqlParser does not support, or an unquoted
  column literally named `update`). The rejection message says why and how to work around it.
- `EXPLAIN ANALYZE` really executes the query, but only inside layers 2 to 5: it cannot write,
  is cancelled after the timeout and is rolled back.
- **Read-only is not the same as no data access.** Tools return plans and statistics, never result
  rows, but the role can read whatever it is granted. Error messages that can echo row values
  (data exceptions such as `CAST(email AS int)` under `analyze=true`, raised exceptions) are reduced
  to their SQLSTATE; only messages that describe the query itself are shown. Grant the role only the
  schemas you are comfortable exposing, or point it at a replica with masked data.
- Views or user-defined functions that the role may call still run, but only inside layers 2 to 5:
  they cannot write and are cancelled at the timeout.
- Every layer has its own tests: the guard has 100+ parameterized cases including injection
  tricks; `ReadOnlyExecutorIT` sends writes *around* the guard and proves the database refuses them.
