-- Demo online shop schema.
--
-- Several indexes are intentionally MISSING so the MCP tools have real problems to find:
--   * shop.orders.customer_id       (FK without an index: slow customer order history, slow joins)
--   * shop.orders.created_at        (ORDER BY created_at DESC LIMIT n scans and sorts the whole table)
--   * shop.order_items.product_id   (FK without an index: slow "who bought this product")
--   * shop.reviews.product_id       (FK without an index)
-- And one index is intentionally USELESS, for the unused_indexes tool:
--   * shop.idx_customers_last_login (never used by the workload)

SET search_path = shop;

CREATE TABLE customers (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email       text        NOT NULL UNIQUE,
    full_name   text        NOT NULL,
    country     char(2)     NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    last_login  timestamptz
);

CREATE TABLE products (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku         text           NOT NULL UNIQUE,
    name        text           NOT NULL,
    category    text           NOT NULL,
    price       numeric(10, 2) NOT NULL CHECK (price >= 0),
    stock       integer        NOT NULL DEFAULT 0
);

CREATE TABLE orders (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id  bigint         NOT NULL REFERENCES customers (id),
    status       text           NOT NULL,
    total        numeric(12, 2) NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL
);

CREATE TABLE order_items (
    order_id     bigint         NOT NULL REFERENCES orders (id),
    line_no      integer        NOT NULL,
    product_id   bigint         NOT NULL REFERENCES products (id),
    quantity     integer        NOT NULL CHECK (quantity > 0),
    unit_price   numeric(10, 2) NOT NULL,
    PRIMARY KEY (order_id, line_no)
);

CREATE TABLE reviews (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id   bigint      NOT NULL REFERENCES products (id),
    customer_id  bigint      NOT NULL REFERENCES customers (id),
    rating       smallint    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body         text,
    created_at   timestamptz NOT NULL
);

-- Deliberately useless index (nothing in the workload filters on last_login).
CREATE INDEX idx_customers_last_login ON customers (last_login);

-- Database functions. Each hides a slow statement inside its body, which EXPLAIN of the calling
-- query does not show: that is what explain_function and slow_functions are for. Table names are
-- schema-qualified because a function body resolves names with the caller's search_path.

-- PL/pgSQL, called once per row by the catalog page. reviews.product_id has no index, so every
-- call scans the whole reviews table.
CREATE FUNCTION product_rating(p_product_id bigint) RETURNS numeric
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_rating numeric;
BEGIN
    SELECT avg(rating) INTO v_rating FROM shop.reviews WHERE product_id = p_product_id;
    RETURN round(v_rating, 2);
END
$$;

-- SQL function: orders.customer_id has no index, so every call scans all orders. Left VOLATILE
-- (the default) although it only reads, which stops the planner from treating it as stable.
CREATE FUNCTION customer_lifetime_value(p_customer_id bigint) RETURNS numeric
LANGUAGE sql AS $$
    SELECT coalesce(sum(total), 0) FROM shop.orders WHERE customer_id = p_customer_id
$$;

-- Writes. explain_function must refuse to run it: the call happens in a read-only transaction.
CREATE FUNCTION mark_order_shipped(p_order_id bigint) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE shop.orders SET status = 'shipped' WHERE id = p_order_id;
END
$$;
