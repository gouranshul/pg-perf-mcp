#!/usr/bin/env bash
# Creates a blocking-lock scenario for the blocking_sessions tool:
#   session A updates a product row and then sits idle inside its transaction,
#   sessions B and C try to touch the same row and wait behind it.
#
#   ./demo/lock-scenario.sh          # holds the lock for 120 seconds
#   HOLD=30 ./demo/lock-scenario.sh
set -euo pipefail

HOLD="${HOLD:-120}"
cd "$(dirname "$0")/.."

psql_in_db() {
  docker compose exec -T db sh -c "psql -q -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"$1\""
}

psql_in_db "BEGIN; UPDATE shop.products SET stock = stock - 1 WHERE id = 42; SELECT pg_sleep(${HOLD}); COMMIT;" >/dev/null &
sleep 2
psql_in_db "UPDATE shop.products SET price = price * 1.10 WHERE id = 42;" >/dev/null &
psql_in_db "SELECT id FROM shop.products WHERE id = 42 FOR UPDATE;" >/dev/null &

echo "Lock scenario running for ${HOLD}s. Ask your assistant: \"Who is blocking whom?\""
wait
echo "Lock released."
