-- Join on the unindexed foreign key orders.customer_id.
SELECT c.email, sum(o.total) AS revenue
FROM shop.customers c
JOIN shop.orders o ON o.customer_id = c.id
WHERE c.country = 'NL' AND c.created_at > now() - interval '30 days'
GROUP BY c.email
ORDER BY revenue DESC
LIMIT 10;
