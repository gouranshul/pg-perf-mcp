-- N+1 pattern from an ORM-style order page: one query per order, then one per line item.
-- Each product lookup hits unindexed order_items.product_id and reviews.product_id.
\set oid random(1, 1000000)
\set pid random(1, 50000)
SELECT id, customer_id, status, total FROM shop.orders WHERE id = :oid;
SELECT line_no, product_id, quantity, unit_price FROM shop.order_items WHERE order_id = :oid;
SELECT avg(rating) FROM shop.reviews WHERE product_id = :pid;
SELECT count(*) FROM shop.order_items WHERE product_id = :pid;
