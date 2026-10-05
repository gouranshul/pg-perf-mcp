#!/usr/bin/env bash
# A narrated walk through the tools, calling the running server over MCP exactly as an assistant
# would: find the slowest query, explain it, ask for an index, then try (and fail) to write.
# Used to record docs/demo.gif. Needs curl and jq.
#
#   ./demo/workload.sh && ./demo/tour.sh
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
PAUSE="${PAUSE:-1.5}"

bold=$'\e[1m'; dim=$'\e[2m'; cyan=$'\e[36m'; green=$'\e[32m'; yellow=$'\e[33m'; red=$'\e[31m'; reset=$'\e[0m'

# jq helper: shorten a string to n characters, marking the cut with an ellipsis.
CUT='def cut(n): if length > n then .[0:n-1] + "…" else . end; '

say()  { printf '\n%s%s%s\n' "$bold" "$1" "$reset"; sleep "$PAUSE"; }
call() { printf '%s→ %s%s %s%s%s\n' "$cyan" "$1" "$reset" "$dim" "$2" "$reset"; }

mcp() {
  curl -fsS "$BASE/mcp" \
    -H "Authorization: Bearer $MCP_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "MCP-Protocol-Version: $PROTOCOL" \
    ${SESSION:+-H "Mcp-Session-Id: $SESSION"} \
    "$@"
}

# Calls a tool and prints the JSON text it returned (the server answers as one SSE "data:" line).
tool() {
  local request
  request=$(jq -cn --arg name "$1" --argjson args "$2" \
    '{jsonrpc:"2.0", id:1, method:"tools/call", params:{name:$name, arguments:$args}}')
  mcp -d "$request" | sed -n 's/^data://p' | jq -r '.result.content[0].text'
}

headers=$(mktemp)
mcp -D "$headers" -o /dev/null -d "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"$PROTOCOL\",\"capabilities\":{},\"clientInfo\":{\"name\":\"tour\",\"version\":\"1\"}}}"
SESSION=$(grep -i '^mcp-session-id:' "$headers" | cut -d' ' -f2 | tr -d '\r\n')
rm -f "$headers"
mcp -o /dev/null -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

say "You: why is the shop database slow?"

call top_slow_queries '{"limit": 3}'
slow=$(tool top_slow_queries '{"limit":3}')
echo "$slow" | jq -r "$CUT"'.queries[] | "  \(.percentOfTotalTime | floor)% of DB time  \(.calls) calls  \(.query | cut(64))"'
top=$(echo "$slow" | jq -r '.queries[0].query')
sleep "$PAUSE"

call explain_query "$(jq -cn --arg sql "$top" '{sql:$sql}')"
tool explain_query "$(jq -cn --arg sql "$top" '{sql:$sql}')" \
  | jq -r "$CUT"'.analysis.findings[] | "  \(.severity) \(.type): \(.message | cut(76))"' \
  | sed -e "s/^  HIGH/  ${red}HIGH${reset}/" -e "s/^  MEDIUM/  ${yellow}MEDIUM${reset}/"
sleep "$PAUSE"

call suggest_indexes "$(jq -cn --arg sql "$top" '{sql:$sql}')"
tool suggest_indexes "$(jq -cn --arg sql "$top" '{sql:$sql}')" \
  | jq -r '.currentEstimatedCost as $before | .suggestions[]
      | "  \(.statement)\n  hypopg estimate: planner cost \($before | round) → \(.estimatedCostAfter | round) (nothing was created)"' \
  | sed "s/^  CREATE/  ${green}CREATE/; s/;$/;${reset}/"
sleep "$PAUSE"

say "A prompt-injected assistant tries to write:"
call explain_query '{"sql": "DELETE FROM shop.orders", "analyze": true}'
rejected=$(mcp -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"explain_query","arguments":{"sql":"DELETE FROM shop.orders","analyze":true}}}' \
  | sed -n 's/^data://p' | jq -r '.result.content[0].text')
printf '  %s%s%s\n' "$red" "$rejected" "$reset"

say "Read-only by design: suggestions are text for a human to review."
