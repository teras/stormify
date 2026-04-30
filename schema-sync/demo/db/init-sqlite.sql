-- Demo schema (SQLite) — intentionally diverges from entities to exercise diff.
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS legacy_carts;
DROP TABLE IF EXISTS audit_events;

CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(40) NOT NULL,
    email VARCHAR(255),
    password_hash VARCHAR(120) NOT NULL,
    active INTEGER DEFAULT 1
    -- bio, email_verified, created_at, last_login_at missing → diff produces ALTERs
);

CREATE TABLE products (
    sku VARCHAR(20) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    price NUMERIC(14,2),
    weight_kg NUMERIC(11,3)
    -- category, in_stock, launch_date, last_updated_at missing → ALTERs
);

CREATE TABLE orders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    customer INTEGER NOT NULL,
    total_amount NUMERIC(14,2),
    tax_amount NUMERIC(14,2)
    -- status_ordinal, placed_at, currency, notes, shipped_at missing → ALTERs
);

-- Orphan tables (no entity counterpart → DB_ONLY).
CREATE TABLE legacy_carts (
    cart_id INTEGER PRIMARY KEY,
    user_id INTEGER,
    created_at TEXT
);

CREATE TABLE audit_events (
    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
    payload TEXT
);
