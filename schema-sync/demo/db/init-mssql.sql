IF OBJECT_ID('payments', 'U') IS NOT NULL DROP TABLE payments;
IF OBJECT_ID('invoices', 'U') IS NOT NULL DROP TABLE invoices;
IF OBJECT_ID('order_items', 'U') IS NOT NULL DROP TABLE order_items;
IF OBJECT_ID('orders', 'U') IS NOT NULL DROP TABLE orders;
IF OBJECT_ID('product_tags', 'U') IS NOT NULL DROP TABLE product_tags;
IF OBJECT_ID('tags', 'U') IS NOT NULL DROP TABLE tags;
IF OBJECT_ID('products', 'U') IS NOT NULL DROP TABLE products;
IF OBJECT_ID('categories', 'U') IS NOT NULL DROP TABLE categories;
IF OBJECT_ID('addresses', 'U') IS NOT NULL DROP TABLE addresses;
IF OBJECT_ID('wishlists', 'U') IS NOT NULL DROP TABLE wishlists;
IF OBJECT_ID('reviews', 'U') IS NOT NULL DROP TABLE reviews;
IF OBJECT_ID('audit_log', 'U') IS NOT NULL DROP TABLE audit_log;
IF OBJECT_ID('settings', 'U') IS NOT NULL DROP TABLE settings;
IF OBJECT_ID('users', 'U') IS NOT NULL DROP TABLE users;
IF OBJECT_ID('legacy_carts', 'U') IS NOT NULL DROP TABLE legacy_carts;
IF OBJECT_ID('audit_events', 'U') IS NOT NULL DROP TABLE audit_events;

CREATE TABLE users (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    username VARCHAR(40) NOT NULL,
    email VARCHAR(255),
    password_hash VARCHAR(120) NOT NULL,
    active BIT DEFAULT 1
);

CREATE TABLE products (
    sku VARCHAR(20) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(MAX),
    price DECIMAL(14,2),
    weight_kg DECIMAL(11,3)
);

CREATE TABLE orders (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    customer BIGINT NOT NULL,
    total_amount DECIMAL(14,2),
    tax_amount DECIMAL(14,2)
);

CREATE TABLE legacy_carts (
    cart_id INT PRIMARY KEY,
    user_id INT,
    created_at DATETIME2
);

CREATE TABLE audit_events (
    event_id BIGINT IDENTITY(1,1) PRIMARY KEY,
    payload VARCHAR(MAX)
);
