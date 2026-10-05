-- Low-selectivity filter on an unindexed column: sequential scan.
SELECT count(*) FROM shop.orders WHERE status = 'pending';
