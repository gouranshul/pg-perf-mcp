-- Missing index on orders.customer_id: every call scans 1M orders.
\set cid random(1, 200000)
SELECT id, status, total, created_at FROM shop.orders WHERE customer_id = :cid ORDER BY created_at DESC;
