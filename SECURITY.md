# Security policy

## Supported versions

Security fixes go into the latest release and `main`. Older releases are not patched.

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub:
**[Report a vulnerability](https://github.com/gouranshul/pg-perf-mcp/security/advisories/new)**
(the repository's Security tab, "Report a vulnerability"). Do not open a public issue.

Include what you found, how to reproduce it (a SQL string, a tool call or a configuration), and the
impact you expect. You should get a first answer within 7 days. Once a fix is released, the advisory
is published with credit to you unless you prefer otherwise.

## What counts as a vulnerability

pg-perf-mcp is **read-only by design**. Anything that breaks that promise is in scope, for example:

- SQL that gets past the SQL guard and writes, changes settings or session state that outlives the
  call, or runs a blocked function;
- a tool that issues DDL or DML, or leaves state on a pooled connection (hypothetical indexes,
  `auto_explain` or `plan_cache_mode` settings);
- table data, credentials, connection details or stack traces in a tool result or error message;
- calling the MCP endpoint without the API key.

The layers that enforce this are described in the README's
[Security model](README.md#security-model) and in
[ADR 0001](docs/adr/0001-read-only-defense-in-depth.md).

Out of scope: the deliberately unindexed, bloated demo database and the placeholder secrets in
`.env.example`, which exist only for local demos.

## Running it safely

- Connect with a dedicated read-only role (see [`docker/init.sql`](docker/init.sql)), never a superuser.
- Prefer a replica or staging database: `explain_query` with `analyze=true` and `explain_function`
  execute the query (inside a read-only transaction that is rolled back and time-limited).
- Use a random `MCP_API_KEY` of 32 or more characters, and publish the port on localhost only
  (`-p 127.0.0.1:8080:8080`) unless it sits behind TLS.
