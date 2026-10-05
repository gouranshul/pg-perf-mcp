# 0005. Platform versions and API differences from the original plan

- Status: accepted
- Date: 2026-10-05

## Context

The project brief named the stack (Java 25, Spring Boot 4.x, Spring AI 2.x MCP server, PostgreSQL
18, JSqlParser, Testcontainers, JaCoCo, OpenTelemetry). Every version was checked against Maven
Central, Docker Hub or the GitHub releases API before pinning, and the APIs were checked against the
published jars and sources rather than older documentation.

## Versions pinned (verified 2026-10-05)

| Component | Version | Source |
|-----------|---------|--------|
| Java | 25 (Temurin 25.0.4) | Adoptium |
| Spring Boot | 4.1.1 (latest GA; 4.2.0 is still milestone) | Maven Central |
| Spring AI BOM | 2.0.1 (built against Boot 4.1.1) | Maven Central |
| MCP Java SDK | 2.0.0 (via Spring AI BOM) | Maven Central |
| JSqlParser | 5.4 | Maven Central |
| Testcontainers | 2.0.5 (managed by Boot) | Maven Central |
| JaCoCo plugin | 0.8.15 | Maven Central |
| Maven (wrapper) | 3.9.16, wrapper 3.3.4 | Maven Central |
| PostgreSQL image | `postgres:18.6` + `postgresql-18-hypopg` | Docker Hub / PGDG apt |
| JRE image | `eclipse-temurin:25-jre-noble` | Docker Hub |
| gitleaks | `zricethezav/gitleaks:v8.30.1` | Docker Hub |
| GitHub Actions | checkout v7, setup-java v6, upload-artifact v7, setup-buildx v4, build-push v7 | GitHub releases |

## Differences from the brief, and what we did

1. **MCP annotations moved into Spring AI.** In 2.0 they live in `org.springframework.ai.mcp.annotation`
   (`@McpTool`, `@McpToolParam`, `@McpResource`, `@McpPrompt`). Prompt arguments use `@McpArg`, not
   `@McpToolParam`. Streamable HTTP is now the default `protocol`; it is still set explicitly.
2. **Tool errors leak root causes by default.** Spring AI's tool callback turns a thrown exception into
   an error result containing the exception message *and its root cause message*, which can include
   host names and user names from JDBC. Tools therefore return `CallToolResult` themselves via
   `ToolRunner`, which maps exceptions to safe messages. Resources throw `McpError` with a safe
   message for the same reason.
3. **MCP SDK 2.0 types.** `Tool.inputSchema()` is a `Map`; errors are built with `McpError.builder(code)`.
4. **Jackson 3.** Boot 4 uses Jackson 3 (`tools.jackson.*`); JSON trees use `asString()` instead of
   `asText()`.
5. **Boot 4 modularization.** `EndpointRequest` is in
   `org.springframework.boot.security.autoconfigure.actuate.web.servlet`, `HealthEndpoint` in
   `org.springframework.boot.health.actuate.endpoint`. OTLP tracing uses
   `management.opentelemetry.tracing.export.otlp.endpoint` and `spring-boot-starter-opentelemetry`,
   which also brings an OTLP *metrics* registry; metric and log export are switched off explicitly
   so nothing is pushed unless asked.
6. **Testcontainers 2.** Artifacts are `testcontainers-postgresql` / `testcontainers-junit-jupiter`
   and the container class is `org.testcontainers.postgresql.PostgreSQLContainer`.
7. **Packaging.** A multi-stage Dockerfile (Boot layered jar, non-root user, JRE base) was chosen over
   Jib so the same file builds locally, in CI and in `docker compose`.
8. **gitleaks in CI** runs the official Docker image instead of `gitleaks-action`, matching the local
   command and avoiding the action's license requirement for organization accounts.
9. **Normalized queries.** `pg_stat_statements` returns `$1`-style queries. `explain_query` plans
   those with `EXPLAIN (GENERIC_PLAN)` (PostgreSQL 16+) instead of failing, and refuses
   `analyze=true` for them with a clear message.

## Consequences

Dependabot (Maven, GitHub Actions, Docker) keeps these current; this ADR records why the code looks
the way it does where it differs from older Spring AI examples.
