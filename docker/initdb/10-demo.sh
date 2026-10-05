#!/usr/bin/env bash
# Loads the demo shop schema and seed data (about a minute on a laptop).
set -euo pipefail
for f in /demo/schema.sql /demo/seed.sql; do
  echo "pg-perf-mcp: loading $f"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f "$f"
done
