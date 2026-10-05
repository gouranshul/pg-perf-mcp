#!/usr/bin/env bash
# Runs a mix of deliberately bad queries against the compose database so that
# pg_stat_statements has something interesting to report.
#
#   ./demo/workload.sh              # 60 seconds, 4 clients
#   DURATION=20 CLIENTS=2 ./demo/workload.sh
#
# Uses pgbench inside the db container, so nothing needs to be installed locally.
set -euo pipefail

DURATION="${DURATION:-60}"
CLIENTS="${CLIENTS:-4}"
cd "$(dirname "$0")/.."

echo "Running bad-query workload for ${DURATION}s with ${CLIENTS} clients..."
docker compose exec -T db sh -c "pgbench -n -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" \
  -T ${DURATION} -c ${CLIENTS} -j ${CLIENTS} \
  -f /demo/workload/01_customer_order_history.sql@3 \
  -f /demo/workload/02_latest_orders.sql@3 \
  -f /demo/workload/03_product_search.sql@2 \
  -f /demo/workload/04_pending_orders_count.sql@1 \
  -f /demo/workload/05_country_revenue_join.sql@1 \
  -f /demo/workload/06_n_plus_one.sql@5"

echo "Done. Ask your assistant: \"Diagnose why the shop database is slow.\""
