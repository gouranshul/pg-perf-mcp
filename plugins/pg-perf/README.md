# pg-perf: read-only PostgreSQL performance diagnostics

Connects Claude Code to a [pg-perf-mcp](https://github.com/gouranshul/pg-perf-mcp) server, so
Claude can work out why a PostgreSQL database is slow using real numbers instead of guesses:
the most expensive statements from `pg_stat_statements`, summarized `EXPLAIN` plans, index
suggestions validated with hypothetical indexes, table health, lock contention, and what runs
inside slow database functions.

Everything is read-only by design. The server runs each query in a read-only transaction that is
rolled back, behind a SQL guard that accepts only a single `SELECT`. Index suggestions are returned
as text for a person to review and are never executed.

## What you need

This plugin contains no server code. It only tells Claude Code how to reach a pg-perf-mcp server
that you run yourself, for example with `docker compose up` from the
[project repository](https://github.com/gouranshul/pg-perf-mcp#quickstart), pointed at a database
role with read-only access.

Install it in Claude Code (2.1.275 or later):

```text
/plugin install pg-perf --marketplace gouranshul/pg-perf-mcp
```

On older versions, run `/plugin marketplace add gouranshul/pg-perf-mcp`, then
`/plugin install pg-perf@pg-perf-mcp`.

When you enable the plugin, Claude Code asks for two values:

- **pg-perf-mcp endpoint**: the URL of your server's MCP endpoint, `http://localhost:8080/mcp` by default
- **API key**: the `MCP_API_KEY` your server was started with. Claude Code keeps it in secure storage.

At install, Claude Code warns that the plugin's MCP server is not a registered directory connector.
That is expected: a connector is one server at a fixed public address, while pg-perf-mcp runs on
your own infrastructure next to your database, usually on `localhost`, so there is no shared
address to register. The server it connects to is the one you enter above, and it authenticates
with your API key.

## What it connects to and sends

The plugin declares one MCP server and nothing else: no hooks, scripts, skills, or commands.
Claude Code connects only to the endpoint you enter, sends your API key in the `Authorization`
header, and sends the tool calls Claude makes (for example the SQL to explain). Nothing is sent
anywhere else, and the plugin does not read credentials from your environment. The
[privacy policy](https://github.com/gouranshul/pg-perf-mcp/blob/main/PRIVACY.md) covers the
plugin and the server, including what reaches Anthropic as part of your conversation.

The MCP server is configured from your own values, so the plugin works in Claude Code. Chat and
Cowork on claude.ai skip it, because they cannot reach a server on your machine.

## Tools

`top_slow_queries`, `explain_query`, `suggest_indexes`, `unused_indexes`, `table_health`,
`blocking_sessions`, `slow_functions` and `explain_function`, plus the `diagnose_slow_database`
prompt. The [project README](https://github.com/gouranshul/pg-perf-mcp#tools) describes each one
and the security model.

## License

MIT, see [LICENSE](https://github.com/gouranshul/pg-perf-mcp/blob/main/LICENSE).
