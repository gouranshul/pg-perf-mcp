-- Leading-wildcard LIKE: a btree index can never help; needs pg_trgm or full-text search.
SELECT id, sku, name, price FROM shop.products WHERE name LIKE '%Bamboo Mug%' ORDER BY price LIMIT 50;
