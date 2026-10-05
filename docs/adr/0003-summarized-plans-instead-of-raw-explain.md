# 0003. Return summarized plans, not raw EXPLAIN output

- Status: accepted
- Date: 2026-10-05

## Context

`EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT JSON)` for a moderately complex query is thousands of
lines. Passing it to an LLM wastes context, costs tokens, and buries the two or three facts that
matter under hundreds that do not. Models also misread plan trees: they confuse per-loop and total
rows, and attribute a child's time to its parent.

## Decision

`explain_query` returns a `PlanAnalysis`, computed by `analysis.PlanAnalyzer`, a pure function
with no I/O:

- **Top 5 costliest nodes** by *self* time (ANALYZE) or *self* cost (plan only). Self values
  subtract the children, so the node that is actually slow is ranked first, not the root.
- **Findings**, each with severity, the node, what was observed and a next step:
  - sequential scan on a large table (size from `pg_class.reltuples`, not the plan's output rows),
  - row estimate off by 10x or more (per loop, ignoring tiny counts),
  - sort spilling to disk, hash join spilling into batches,
  - nested loop whose inner side runs 1,000+ times (worse when the inner side is a seq scan).

The plan is requested with `VERBOSE` so that relations are schema-qualified and conditions are
alias-qualified, which the index advisor relies on.

## Consequences

- Responses are a few hundred tokens and point straight at the problem.
- The analyzer is unit-tested against JSON fixtures shaped like real PostgreSQL output, without a
  database. Coverage of the package is gated at 80% lines in CI.
- Information is lost by design. If the summary is not enough, the roadmap includes an opt-in
  "raw plan" flag, kept off by default.
