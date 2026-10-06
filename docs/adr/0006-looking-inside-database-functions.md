# 0006. Looking inside database functions

- Status: accepted
- Date: 2026-10-06

## Context

`EXPLAIN` treats a call to a user-defined function as one opaque expression. A query such as
`SELECT p.id, shop.product_rating(p.id) FROM shop.products p ... LIMIT 20` shows an index scan on
`products`, and the 180 ms spent inside the function is charged to that scan. The real problem (a
sequential scan of 250k `reviews` rows, run once per product) is invisible to `explain_query`, and
an assistant following its findings would tune the wrong thing. Business logic in PL/pgSQL and SQL
functions is common, so this is a real gap.

We need, for one query: time per function, the statements executed inside the functions with
their call counts, and the actual plan of those statements. All of it from a read-only, non-superuser
role, without leaking data values.

## Decision

`explain_function` runs the query once with `EXPLAIN (ANALYZE)` in the usual read-only, rolled-back
transaction (ADR 0001) and combines three sources:

| Source | Gives | Notes |
|--------|-------|-------|
| `auto_explain` with `log_nested_statements`, `log_format=json`, `log_level=notice` | The actual plan of every statement executed inside the functions, as a notice to *this* client | Enabled with `set_config(..., is_local => true)` inside a savepoint, and the savepoint is rolled back right after the query, so nothing leaks to the pooled connection. |
| `pg_stat_xact_user_functions` | Calls, total and self time per function | Read before and after: the view holds the backend's *pending* statistics, which can include calls from earlier transactions on the same connection until they are flushed (at most once a second). A test failed on exactly this before the delta was added. |
| `pg_stat_statements` (`toplevel = false`) | Calls and time of every nested statement, including fast ones with no captured plan | Before/after delta computed server side from a one-row JSON snapshot, so the 200-row result cap cannot truncate it. Matched to `auto_explain` plans by `Query Identifier`, not by text. |

`FunctionAnalyzer` (pure, unit-tested) then reports: a function that dominates execution time, a
function called once per row, a read-only function left `VOLATILE`, and every MEDIUM/HIGH plan
finding of the nested statements, labelled with the function it came from. Statements are
attributed to functions by matching their words in order against `pg_get_functiondef` (PL/pgSQL
removes `INTO` targets from the executed text, so a substring match would fail).

`slow_functions` ranks functions from `pg_stat_user_functions` as the discovery step.

### Privileges

`auto_explain` settings are superuser-only. On PostgreSQL 15+ the role is granted
`SET ON PARAMETER` for exactly the seven settings it changes, which lets it change them for its
own transaction and nothing else. `auto_explain` is preloaded but stays inactive
(`log_min_duration = -1`) for every other session. Without the grant or without the module, the
tool still returns timings and says in `notes` what is missing. If `auto_explain` is not loaded,
`set_config` succeeds silently on a placeholder, so the tool checks `pg_settings` first rather
than trusting the call.

### No data values in plans

A custom plan prints the value a PL/pgSQL variable held (`product_id = '7'::bigint`), and such a
value can come from a table row. `plan_cache_mode = force_generic_plan` (also transaction-local)
makes nested plans show `$1` instead. The trade-off: during its first five executions PL/pgSQL
would normally use custom plans, so a plan here can differ from those early executions. Generic
plans are what a busy function settles on, which is the case that matters for performance.

### Bounded notice volume

Every captured plan is a notice held in driver memory until the statement ends. A function looping
over many fast statements would produce one per iteration. `pgperf.functions.nested-plan-threshold`
(default 1 ms) limits capture to slow executions, while `pg_stat_statements` still counts all of
them. Tests use 0 ms because the test seed is tiny.

## Alternatives considered

- **Parse function bodies and `EXPLAIN` each statement.** Needs a PL/pgSQL parser, cannot know
  variable values or which branches run, and the executed text differs from the source.
- **`EXPLAIN (GENERIC_PLAN)` of the `pg_stat_statements` text.** PL/pgSQL statements reference
  variables by name (`product_id = p_product_id`), which is not valid standalone SQL.
- **`plpgsql_check` profiler.** Excellent, but an extra extension most managed databases lack, and
  PL/pgSQL only.
- **Reading `auto_explain` output from the server log.** Needs log access and parsing, and mixes in
  other sessions. Notices go only to the session that asked.

## Consequences

- One tool call shows where time goes inside functions, with plans an assistant can act on.
- `explain_function` executes the query, like `explain_query` with `analyze=true`. Functions that
  write fail in the read-only transaction (tested).
- Concurrent sessions running the same nested statements at the same moment can inflate the
  `pg_stat_statements` delta slightly. Per-function numbers are per backend and unaffected.
- Simple SQL functions that the planner inlines never run as separate calls; their work already
  appears in the outer plan, and the tool says so.
