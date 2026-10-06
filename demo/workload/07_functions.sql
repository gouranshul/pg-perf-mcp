-- Catalog and customer pages built on database functions. The slow statements are inside the
-- functions: product_rating runs once per product row, customer_lifetime_value scans all orders.
\set cid random(1, 200000)
SELECT p.id, p.name, shop.product_rating(p.id) FROM shop.products p WHERE p.category = 'kitchen' ORDER BY p.id LIMIT 20;
SELECT shop.customer_lifetime_value(:cid);
