# Privacy policy

Last updated: 2026-10-16

This policy covers pg-perf-mcp, the open-source MCP server in this repository, and the `pg-perf`
Claude Code plugin in [`plugins/pg-perf`](plugins/pg-perf). Both are self-hosted. The author does
not operate any service for them and does not receive, collect, store or sell any data from them.

## The plugin

The plugin contains no code. It only tells Claude Code how to reach a pg-perf-mcp server that you
run yourself. It asks you for two values, the server's endpoint URL and its API key, and Claude
Code keeps them on your machine (the key in secure storage). Claude Code then connects only to the
endpoint you entered, sends the API key in the `Authorization` header, and sends the tool calls
Claude makes. The plugin does not read credentials from your environment and sends nothing to any
other destination.

## The server

The server runs where you deploy it and connects only to the PostgreSQL database you configure,
with the read-only role you provide. It does not contact the author or any third party, and it has
no analytics or usage reporting.

What it reads from your database: statistics (such as `pg_stat_statements`, `pg_stat_user_tables`
and `pg_stat_user_functions`), catalog metadata (table, index and function definitions) and query
plans. Tools return plans and statistics, not the rows of your tables. Error messages that could
contain row values are reduced to their SQLSTATE code. See
[ADR 0001](docs/adr/0001-read-only-defense-in-depth.md) for the details and the limits of this.

What it records, all on infrastructure you control:

- **Audit log**: one JSON line per tool call, written to the server's standard output: time,
  request id, tool name, arguments, duration, row count and outcome. SQL arguments are recorded as a
  SHA-256 hash, the first 80 characters and the length, not in full.
- **Metrics**: counters and timings available at `/actuator/prometheus`, behind the API key, for
  you to collect. The server does not send them anywhere.
- **Traces**: off by default. If you set `OTEL_TRACING_ENABLED=true`, spans are exported to the
  OTLP endpoint you configure, and nowhere else.

## What reaches Anthropic

When you use the plugin or the server with Claude, the tool results (query plans, statistics, SQL
text, function definitions, schema details) become part of your conversation and are processed by
Anthropic under [Anthropic's privacy policy](https://www.anthropic.com/legal/privacy) and the terms
of your Claude plan. Grant the database role access only to what you are comfortable sharing in a
conversation.

## Contact

Questions or concerns: open an issue at
[github.com/gouranshul/pg-perf-mcp/issues](https://github.com/gouranshul/pg-perf-mcp/issues). Report
security problems as described in [SECURITY.md](SECURITY.md).
