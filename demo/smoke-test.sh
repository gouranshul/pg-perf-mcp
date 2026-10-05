#!/usr/bin/env bash
# End-to-end check of a running stack (docker compose up): health, authentication and real MCP
# tool calls over streamable HTTP with curl.
#
#   MCP_API_KEY=... ./demo/smoke-test.sh
#
# Reads MCP_API_KEY and APP_PORT from .env when they are not already set.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -f .env ]]; then
  MCP_API_KEY="${MCP_API_KEY:-$(grep -E '^MCP_API_KEY=' .env | cut -d= -f2-)}"
  APP_PORT="${APP_PORT:-$(grep -E '^APP_PORT=' .env | cut -d= -f2- || true)}"
fi
: "${MCP_API_KEY:?MCP_API_KEY is not set}"
BASE="${BASE_URL:-http://localhost:${APP_PORT:-8080}}"
PROTOCOL="2025-06-18"

fail() { echo "SMOKE TEST FAILED: $*" >&2; exit 1; }

echo "1. Health endpoint is open"
curl -fsS "$BASE/actuator/health" | grep -q '"status":"UP"' || fail "health is not UP"

echo "2. MCP endpoint rejects requests without the API key"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/mcp")
[[ "$code" == "401" ]] || fail "expected 401 without a key, got $code"

mcp() {
  curl -fsS "$BASE/mcp" \
    -H "Authorization: Bearer $MCP_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "MCP-Protocol-Version: $PROTOCOL" \
    ${SESSION:+-H "Mcp-Session-Id: $SESSION"} \
    "$@"
}

echo "3. initialize"
headers=$(mktemp)
mcp -D "$headers" -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"$PROTOCOL\",\"capabilities\":{},\"clientInfo\":{\"name\":\"smoke-test\",\"version\":\"1\"}}}" \
  | grep -q '"pg-perf-mcp"' || fail "initialize did not return server info"
SESSION=$(grep -i '^mcp-session-id:' "$headers" | cut -d' ' -f2 | tr -d '\r\n')
rm -f "$headers"
[[ -n "$SESSION" ]] || fail "no Mcp-Session-Id header"
mcp -o /dev/null -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

echo "4. tools/list"
mcp -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | grep -q '"suggest_indexes"' || fail "tools/list"

echo "5. tools/call explain_query finds the missing orders.customer_id index"
mcp -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"explain_query","arguments":{"sql":"SELECT id, status FROM shop.orders WHERE customer_id = 42"}}}' \
  | grep -q 'SEQ_SCAN_ON_LARGE_TABLE' || fail "explain_query did not flag the sequential scan"

echo "6. tools/call refuses a write"
mcp -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"explain_query","arguments":{"sql":"DELETE FROM shop.orders","analyze":true}}}' \
  | grep -q 'Rejected by the SQL guard' || fail "write was not rejected"

echo "Smoke test passed."
