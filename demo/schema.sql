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
