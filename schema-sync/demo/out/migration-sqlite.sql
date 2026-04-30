-- schema-sync migration
-- Generated 2026-04-28 for sqlite

-- ── CREATE addresses (entity com.example.shop.Address) ──
CREATE TABLE addresses (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    owner INTEGER DEFAULT 0 NOT NULL,
    street VARCHAR(200) DEFAULT '' NOT NULL,
    city VARCHAR(50) DEFAULT '' NOT NULL,
    state VARCHAR(50) DEFAULT '' NOT NULL,
    zip VARCHAR(10) DEFAULT '' NOT NULL,
    country VARCHAR(50) DEFAULT '' NOT NULL,
    is_primary INTEGER DEFAULT 0 NOT NULL,
    validated_at TEXT
);

-- ── audit_events (DB only) ──
-- table exists in DB but no matching entity
--   event_id  INTEGER
--   payload  TEXT

-- ── CREATE audit_log (entity com.example.shop.AuditLog) ──
CREATE TABLE audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    actor INTEGER DEFAULT 0 NOT NULL,
    action VARCHAR(50) DEFAULT '' NOT NULL,
    entity_type VARCHAR(50) DEFAULT '' NOT NULL,
    entity_id VARCHAR(50) DEFAULT '' NOT NULL,
    occurred_at TEXT
);

-- ── CREATE categories (entity com.example.shop.Category) ──
CREATE TABLE categories (
    id TEXT NOT NULL PRIMARY KEY,
    name VARCHAR(50) DEFAULT '' NOT NULL,
    description VARCHAR(1000),
    parent TEXT,
    sort_order NUMERIC(10) DEFAULT 0 NOT NULL
);

-- ── CREATE invoices (entity com.example.shop.Invoice) ──
CREATE TABLE invoices (
    id INTEGER DEFAULT 0 NOT NULL PRIMARY KEY,
    order_id INTEGER DEFAULT 0 NOT NULL,
    amount NUMERIC(14,2) DEFAULT 0 NOT NULL,
    paid INTEGER DEFAULT 0 NOT NULL,
    issued_at TEXT,
    due_date TEXT,
    generated_at TEXT
);

-- ── legacy_carts (DB only) ──
-- table exists in DB but no matching entity
--   cart_id  INTEGER
--   user_id  INTEGER
--   created_at  TEXT

-- ── CREATE order_items (entity com.example.shop.OrderItem) ──
CREATE TABLE order_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    order_id INTEGER DEFAULT 0 NOT NULL,
    quantity NUMERIC(10) DEFAULT 1 NOT NULL,
    unit_price NUMERIC(14,2) DEFAULT 0 NOT NULL,
    discount_percent NUMERIC(3,2) DEFAULT 0 NOT NULL
);
-- WARNING: 1 fields skipped due to missing slot assignment

-- ── orders ──
ALTER TABLE orders ADD COLUMN currency VARCHAR(10) DEFAULT 'EUR' NOT NULL;
ALTER TABLE orders ADD COLUMN notes VARCHAR(1000);
ALTER TABLE orders ADD COLUMN status_ordinal NUMERIC(1) DEFAULT 0 NOT NULL;
ALTER TABLE orders ADD COLUMN placed_at TEXT;
ALTER TABLE orders ADD COLUMN shipped_at TEXT;

-- ── CREATE payments (entity com.example.shop.Payment) ──
CREATE TABLE payments (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    invoice INTEGER DEFAULT 0 NOT NULL,
    method VARCHAR(50) DEFAULT '' NOT NULL,
    transaction_ref VARCHAR(200) DEFAULT '' NOT NULL,
    amount NUMERIC(14,2) DEFAULT 0 NOT NULL,
    occurred_at TEXT
);

-- ── CREATE product_tags (entity com.example.shop.ProductTag) ──
CREATE TABLE product_tags (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    tag INTEGER DEFAULT 0 NOT NULL
);
-- WARNING: 1 fields skipped due to missing slot assignment

-- ── products ──
-- TODO SQLite cannot ADD a NOT NULL column without DEFAULT; set an auto-default in F6 for UUID or initialize category in the entity
ALTER TABLE products ADD COLUMN in_stock INTEGER DEFAULT 1 NOT NULL;
ALTER TABLE products ADD COLUMN launch_date TEXT;
ALTER TABLE products ADD COLUMN last_updated_at TEXT;

-- ── CREATE reviews (entity com.example.shop.Review) ──
CREATE TABLE reviews (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    author INTEGER DEFAULT 0 NOT NULL,
    rating NUMERIC(1) DEFAULT 5 NOT NULL,
    title VARCHAR(50) DEFAULT '' NOT NULL,
    body VARCHAR(1000),
    verified INTEGER DEFAULT 0 NOT NULL,
    posted_at TEXT
);
-- WARNING: 1 fields skipped due to missing slot assignment

-- ── CREATE settings (entity com.example.shop.Setting) ──
CREATE TABLE settings (
    "key" VARCHAR(50) DEFAULT '' NOT NULL PRIMARY KEY,
    "value" VARCHAR(1000) DEFAULT '' NOT NULL,
    description VARCHAR(1000)
);

-- ── CREATE tags (entity com.example.shop.Tag) ──
CREATE TABLE tags (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    label VARCHAR(50) DEFAULT '' NOT NULL,
    color VARCHAR(10) DEFAULT '#000000' NOT NULL
);

-- ── users ──
ALTER TABLE users ADD COLUMN bio VARCHAR(1000);
ALTER TABLE users ADD COLUMN email_verified INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE users ADD COLUMN created_at TEXT;
ALTER TABLE users ADD COLUMN last_login_at TEXT;

-- ── CREATE wishlists (entity com.example.shop.Wishlist) ──
CREATE TABLE wishlists (
    id TEXT NOT NULL PRIMARY KEY,
    owner INTEGER DEFAULT 0 NOT NULL,
    name VARCHAR(50) DEFAULT '' NOT NULL,
    public_shared INTEGER DEFAULT 0 NOT NULL,
    created_at TEXT
);

