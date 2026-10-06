-- Runs once on first container start, as the bootstrap superuser.
-- Expects the psql variable :readonly_password (set by docker/initdb/00-init.sh).

CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE EXTENSION IF NOT EXISTS hypopg;

-- The role pg-perf-mcp connects as. It can read statistics and the demo schema, nothing else.
CREATE ROLE mcp_readonly LOGIN PASSWORD :'readonly_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;

GRANT pg_read_all_stats TO mcp_readonly;
GRANT pg_monitor TO mcp_readonly;

-- Session defaults enforced server side, independent of anything the client sends.
ALTER ROLE mcp_readonly SET default_transaction_read_only = on;
ALTER ROLE mcp_readonly SET statement_timeout = '5s';
ALTER ROLE mcp_readonly SET idle_in_transaction_session_timeout = '30s';

-- explain_function turns auto_explain on for its own transaction only (SET LOCAL) to capture the
-- plans of statements that run inside database functions. auto_explain is preloaded but inactive
-- (log_min_duration = -1) and its settings are superuser-only, so the role is granted SET on exactly
-- these (PostgreSQL 15+). Nothing here lets it write.
GRANT SET ON PARAMETER
    auto_explain.log_min_duration, auto_explain.log_analyze, auto_explain.log_nested_statements,
    auto_explain.log_format, auto_explain.log_level, auto_explain.log_verbose,
    auto_explain.log_parameter_max_length
TO mcp_readonly;

-- Demo schema for the online shop. Tables are created by demo/schema.sql.
CREATE SCHEMA shop;
GRANT USAGE ON SCHEMA shop TO mcp_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA shop GRANT SELECT ON TABLES TO mcp_readonly;

-- No write access anywhere, explicitly.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
