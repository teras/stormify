DROP TABLE IF EXISTS payments, invoices, order_items, orders, product_tags, tags,
    products, categories, addresses, wishlists, reviews, audit_log, settings,
    users, legacy_carts, audit_events CASCADE;

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(40) NOT NULL,
    email VARCHAR(255),
    password_hash VARCHAR(120) NOT NULL,
    active BOOLEAN DEFAULT TRUE
);

CREATE TABLE products (
    sku VARCHAR(20) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    price NUMERIC(14,2),
    weight_kg NUMERIC(11,3)
);

CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    customer BIGINT NOT NULL,
    total_amount NUMERIC(14,2),
    tax_amount NUMERIC(14,2)
);

CREATE TABLE legacy_carts (
    cart_id INTEGER PRIMARY KEY,
    user_id INTEGER,
    created_at TIMESTAMP
);

CREATE TABLE audit_events (
    event_id BIGSERIAL PRIMARY KEY,
    payload TEXT
);
