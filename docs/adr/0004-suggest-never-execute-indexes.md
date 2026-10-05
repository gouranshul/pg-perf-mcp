# 0004. Suggest indexes, never execute them

- Status: accepted
- Date: 2026-10-05

## Context

The most common fix for a slow query is an index, and it is tempting to let the assistant create
it. But `CREATE INDEX` takes locks, consumes I/O and disk, slows every future write, and on a large
table can run for a long time. Whether it is worth it depends on the whole workload, not one query.
That is a decision for a human, at a time of their choosing.

## Decision

`suggest_indexes` returns `CREATE INDEX CONCURRENTLY` statements with a reason for each, and **never
executes them**. This is enforced structurally, not by convention: the server has no code path that
issues DDL, the connection is read-only, and the role has no `CREATE` privilege (ADR 0001).

Candidates come from `analysis.IndexAdvisor`, which reads the plan:

1. filtered sequential scans on large tables: equality columns first, then one range column;
2. join keys whose table is read by a sequential scan;
3. `ORDER BY ... LIMIT` that sorts a whole table: an index in sort order lets Postgres stop early.

Candidates are dropped when an existing valid, non-partial index already starts with the same
columns, and narrower candidates are merged into wider ones on the same table. Leading-wildcard
`LIKE` and low-selectivity filters become notes instead of misleading btree suggestions.

**Validation with hypopg.** If the `hypopg` extension is installed, each candidate is created as a
*hypothetical* index (backend memory only, nothing on disk or in the catalog), the query is
re-planned, and the response reports the estimated cost after, the improvement percentage, and
whether the planner would use the index at all. Without hypopg, the response says validation was
skipped.

Hypothetical indexes live in the database backend, not in the transaction, so rolling back does not
remove them. To keep them from leaking into later calls on a pooled connection, validation runs
inside a savepoint and always ends with `hypopg_reset()`, even on failure, and client SQL may not
call `hypopg*` functions at all (guard denylist). `IndexToolsIT` checks that nothing leaks.

## Consequences

- The assistant can say "this index would cut the estimated cost by 97%" with evidence, while a
  human stays in control of schema changes.
- hypopg estimates are planner costs, not measured runtimes; the response says so.
- The heuristics are deliberately simple and explainable; they are not a full workload advisor.
