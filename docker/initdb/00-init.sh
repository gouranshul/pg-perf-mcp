#!/usr/bin/env bash
# Creates extensions, the read-only role and the demo schema.
set -euo pipefail
: "${MCP_DB_PASSWORD:?MCP_DB_PASSWORD must be set (see .env.example)}"
psql -v ON_ERROR_STOP=1 -v readonly_password="$MCP_DB_PASSWORD" \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f /docker/init.sql
