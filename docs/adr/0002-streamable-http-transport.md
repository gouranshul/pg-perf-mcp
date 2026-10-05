# 0002. Streamable HTTP transport

- Status: accepted
- Date: 2026-10-05

## Context

MCP servers can be reached over **stdio** (the client launches the server as a subprocess) or over
HTTP. The HTTP options are the older HTTP+SSE transport (deprecated in the MCP specification) and
**streamable HTTP**, a single endpoint that answers JSON or upgrades to an SSE stream when needed.

A database diagnostics server is different from a local file tool:

- it holds database credentials, which should live with the server, not on every developer laptop;
- several people and assistants may share one instance pointed at a staging or replica database;
- it runs well as a container next to the database (see `docker-compose.yml`).

## Decision

Use **streamable HTTP** (`spring-ai-starter-mcp-server-webmvc`, `protocol: STREAMABLE`, endpoint
`/mcp`) with the synchronous server API on virtual threads.

- WebMVC + virtual threads keeps the code blocking and simple (JDBC is blocking anyway) while
  scaling to many concurrent, mostly I/O-bound calls.
- The endpoint is protected by a bearer API key (constant-time comparison). Health stays open for
  container orchestration and never shows details.
- Clients connect with `claude mcp add --transport http ...` or, for clients that only speak
  stdio, through a bridge such as `mcp-remote`.

## Consequences

- One deployable service with standard HTTP concerns (TLS termination, auth, metrics, tracing),
  instead of a binary on every client machine.
- Streamable HTTP completes SSE responses in an async servlet dispatch. The API key filter therefore
  re-authenticates async and error dispatches (`ApiKeyAuthenticationFilter`); without that, Spring
  Security denied the async dispatch after the response was committed.
- A shared API key is coarse-grained. The roadmap item is the MCP authorization spec (OAuth 2.1
  resource server) for per-user identity.
