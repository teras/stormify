DROP TABLE IF EXISTS payments;
DROP TABLE IF EXISTS invoices;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS product_tags;
DROP TABLE IF EXISTS tags;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS categories;
DROP TABLE IF EXISTS addresses;
DROP TABLE IF EXISTS wishlists;
DROP TABLE IF EXISTS reviews;
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS settings;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS legacy_carts;
DROP TABLE IF EXISTS audit_events;

CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(40) NOT NULL,
    email VARCHAR(255),
    password_hash VARCHAR(120) NOT NULL,
    active TINYINT(1) DEFAULT 1
);

CREATE TABLE products (
    sku VARCHAR(20) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    price DECIMAL(14,2),
    weight_kg DECIMAL(11,3)
);

CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer BIGINT NOT NULL,
    total_amount DECIMAL(14,2),
    tax_amount DECIMAL(14,2)
);

CREATE TABLE legacy_carts (
    cart_id INT PRIMARY KEY,
    user_id INT,
    created_at TIMESTAMP NULL
);

CREATE TABLE audit_events (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payload TEXT
);
