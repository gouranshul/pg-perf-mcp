-- Missing index on orders.created_at: ORDER BY ... LIMIT sorts the whole table.
SELECT id, customer_id, status, total FROM shop.orders ORDER BY created_at DESC LIMIT 20;
