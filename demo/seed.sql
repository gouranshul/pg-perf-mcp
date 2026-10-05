-- Seeds the demo shop with realistic volumes:
--   200k customers, 50k products, 1M orders, 3M order items, 250k reviews.
-- Deterministic (setseed) so every run produces the same data. The psql variable :scale
-- (default 1, see docker/initdb/10-demo.sh) shrinks every table proportionally.

SET search_path = shop;
SELECT setseed(0.42);

-- Customers ---------------------------------------------------------------------------------
INSERT INTO customers (email, full_name, country, created_at, last_login)
SELECT 'customer' || g || '@example.com',
       (ARRAY['Alex','Sam','Jordan','Taylor','Morgan','Casey','Riley','Jamie','Robin','Avery'])[1 + g % 10]
           || ' ' ||
       (ARRAY['Smith','Garcia','Kim','Novak','Rossi','Silva','Okafor','Khan','Muller','Tanaka'])[1 + (g / 10) % 10],
       (ARRAY['US','GB','DE','FR','IN','BR','JP','CA','AU','NL'])[1 + (g * 7) % 10],
       now() - (random() * interval '1500 days'),
       CASE WHEN random() < 0.8 THEN now() - (random() * interval '90 days') END
FROM generate_series(1, (200000 * :scale)::int) AS g;

-- Products ----------------------------------------------------------------------------------
INSERT INTO products (sku, name, category, price, stock)
SELECT 'SKU-' || lpad(g::text, 6, '0'),
       (ARRAY['Classic','Ultra','Eco','Smart','Mini','Pro','Vintage','Compact','Deluxe','Travel'])[1 + g % 10]
           || ' ' ||
       (ARRAY['Wireless','Cotton','Steel','Bamboo','Leather','Ceramic','Glass','Wool','Carbon','Linen'])[1 + (g / 10) % 10]
           || ' ' ||
       (ARRAY['Headphones','T-Shirt','Bottle','Desk Lamp','Backpack','Mug','Kettle','Scarf','Bike Light','Notebook'])[1 + (g / 100) % 10]
           || ' ' || g,
       (ARRAY['electronics','apparel','kitchen','home','outdoor','office'])[1 + g % 6],
       round((5 + random() * 495)::numeric, 2),
       (random() * 500)::int
FROM generate_series(1, (50000 * :scale)::int) AS g;

-- Orders: ~1M over three years. Customer ids are skewed so some customers have many orders.
INSERT INTO orders (customer_id, status, total, created_at)
SELECT 1 + floor(power(random(), 1.5) * ((200000 * :scale)::int - 1))::bigint,
       (ARRAY['pending','paid','paid','shipped','shipped','shipped','delivered','delivered','delivered','cancelled'])[1 + floor(random() * 10)::int],
       round((10 + random() * 990)::numeric, 2),
       now() - (random() * interval '1095 days')
FROM generate_series(1, (1000000 * :scale)::int);

-- Order items: exactly three lines per order, 3M rows. Popular products are bought more.
INSERT INTO order_items (order_id, line_no, product_id, quantity, unit_price)
SELECT o, l,
       1 + floor(power(random(), 2) * ((50000 * :scale)::int - 1))::bigint,
       1 + floor(random() * 4)::int,
       round((5 + random() * 495)::numeric, 2)
FROM generate_series(1, (1000000 * :scale)::int) AS o
CROSS JOIN generate_series(1, 3) AS l;

-- Reviews -----------------------------------------------------------------------------------
INSERT INTO reviews (product_id, customer_id, rating, body, created_at)
SELECT 1 + floor(power(random(), 2) * ((50000 * :scale)::int - 1))::bigint,
       1 + floor(random() * ((200000 * :scale)::int - 1))::bigint,
       1 + floor(random() * 5)::int,
       (ARRAY['Great value','Arrived late','Exactly as described','Would buy again','Broke after a week'])[1 + floor(random() * 5)::int],
       now() - (random() * interval '1000 days')
FROM generate_series(1, (250000 * :scale)::int);

-- Table bloat for the table_health tool: autovacuum is switched off on orders and a batch
-- update leaves ~20% dead tuples behind. Never do this in production.
ALTER TABLE orders SET (autovacuum_enabled = false);
UPDATE orders SET status = 'delivered' WHERE status = 'shipped' AND id % 2 = 0;

ANALYZE;
