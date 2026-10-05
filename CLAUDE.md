# CLAUDE.md

Guidance for AI coding assistants working in this repository.

## Purpose

`pg-perf-mcp` is an MCP (Model Context Protocol) server that lets AI assistants diagnose
PostgreSQL performance problems: slow queries, EXPLAIN plans, index suggestions, table health
and lock contention. It is **read-only by design**. Nothing it exposes may write to the
database, and index suggestions are returned as text and never executed.

## Build and test

```bash
./mvnw verify                   # full build: unit + Testcontainers integration tests + JaCoCo gate
./mvnw test -Dtest=SqlGuardTest # one test class
docker compose up --build       # Postgres (seeded demo shop) + the MCP server on :8080
./demo/workload.sh              # generate slow-query statistics against the compose DB
```

Integration tests use Testcontainers and need a running Docker daemon.

## Stack

Java 25 (virtual threads on), Spring Boot 4.1, Spring AI 2.0 MCP server (WebMVC, streamable
HTTP, annotation model: `@McpTool`, `@McpToolParam`, `@McpResource`, `@McpPrompt`),
PostgreSQL 18 + `pg_stat_statements`, `JdbcClient` + HikariCP (no JPA), JSqlParser,
JUnit 5 / AssertJ / Testcontainers / JaCoCo, Micrometer + Actuator + optional OTLP tracing.

## Package layout (`io.github.gouranshul.pgperf`)

| Package      | Contents                                                              |
|--------------|-----------------------------------------------------------------------|
| `config`     | datasource, security (API key filter), MCP and app properties         |
| `guard`      | `SqlGuard`: JSqlParser-based single-SELECT allowlist                   |
| `db`         | `ReadOnlyExecutor`: rolled-back transaction, per-call timeout, row cap |
| `tools`      | one class per MCP tool group                                          |
| `resources`  | MCP resources (`pg://schema/...`)                                     |
| `prompts`    | MCP prompts (`diagnose_slow_database`)                                |
| `analysis`   | pure, unit-testable plan analyzer and index heuristics                |
| `audit`      | structured audit log + metrics around tool calls                      |

Other top-level folders: `demo/` (schema, seed, workload), `docker/` (Postgres init),
`docs/adr/` (architecture decision records).

## Hard rules

1. Generic, original work only. No company names, internal domains or proprietary code.
   Nothing finance, valuation or fund related. The demo domain is a generic online shop.
2. Commits must use a personal email or the GitHub noreply address, never a work domain.
3. Never commit secrets. Use `.env.example` with placeholders. Run gitleaks
   (`docker run --rm -v "$PWD:/repo" zricethezav/gitleaks:latest git /repo`) before every push.
4. Verify every dependency version against Maven Central or official docs before pinning it.
   Note any API differences from the original plan in `docs/adr`.
5. The build stays green: `./mvnw verify` passes at the end of every change.
6. Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `ci:`, `chore:`), small and meaningful.

## Safety invariants (do not weaken)

- Every user-supplied SQL string goes through `SqlGuard.validate` before it touches JDBC.
- All database access from tools goes through `ReadOnlyExecutor` (read-only tx, always rolled
  back, `SET LOCAL statement_timeout`, row cap).
- Never execute `CREATE INDEX` or any DDL/DML. Suggestions are text only.
- Errors returned to the MCP client must not contain stack traces or connection details.
