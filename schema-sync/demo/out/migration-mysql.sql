-- schema-sync migration
-- Generated 2026-04-28 for mysql

-- ── CamelPolicyEntity (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   firstName  TEXT
--   lastName  TEXT

-- ── UPPER_POLICY_ENTITY (DB only) ──
-- table exists in DB but no matching entity
--   ID  INT
--   FIRST_NAME  TEXT
--   LAST_NAME  TEXT

-- ── CREATE addresses (entity com.example.shop.Address) ──
CREATE TABLE addresses (
    id BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    owner BIGINT DEFAULT 0 NOT NULL,
    street VARCHAR(200) DEFAULT '' NOT NULL,
    city VARCHAR(50) DEFAULT '' NOT NULL,
    state VARCHAR(50) DEFAULT '' NOT NULL,
    zip VARCHAR(10) DEFAULT '' NOT NULL,
    country VARCHAR(50) DEFAULT '' NOT NULL,
    is_primary TINYINT(1) DEFAULT 0 NOT NULL,
    validated_at TIMESTAMP
);

-- ── agg_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT

-- ── all_types (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   byte_val  SMALLINT
--   short_val  SMALLINT
--   int_val  INT
--   long_val  BIGINT
--   float_val  DOUBLE
--   double_val  DOUBLE
--   bool_val  BIT
--   string_val  TEXT

-- ── annotated_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   custom_col  TEXT
--   read_only_on_create  TEXT
--   read_only_on_update  TEXT

-- ── at_child (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT
--   is_active  BIT
--   parent  INT

-- ── audit_events (DB only) ──
-- table exists in DB but no matching entity
--   event_id  BIGINT
--   payload  TEXT

-- ── CREATE audit_log (entity com.example.shop.AuditLog) ──
CREATE TABLE audit_log (
    id BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    actor BIGINT DEFAULT 0 NOT NULL,
    action VARCHAR(50) DEFAULT '' NOT NULL,
    entity_type VARCHAR(50) DEFAULT '' NOT NULL,
    entity_id VARCHAR(50) DEFAULT '' NOT NULL,
    occurred_at TIMESTAMP
);

-- ── auto_child (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   data  TEXT
--   parent  INT

-- ── auto_increment (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT

-- ── auto_parent (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   other  TEXT
--   data  TEXT

-- ── bd_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   val  DECIMAL(38,10)

-- ── bi_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   val  DECIMAL(38)

-- ── blacklist_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT
--   secret  TEXT

-- ── blob_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   blob_data  LONGBLOB
--   clob_as_chars  TEXT
--   clob_as_string  TEXT

-- ── camel_entity (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   first_name  TEXT
--   last_name  TEXT

-- ── CREATE categories (entity com.example.shop.Category) ──
CREATE TABLE categories (
    id CHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(50) DEFAULT '' NOT NULL,
    description VARCHAR(1000),
    parent CHAR(36),
    sort_order NUMERIC(10) DEFAULT 0 NOT NULL
);

-- ── child (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT
--   parent  INT

-- ── conversion_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   value  TEXT

-- ── ct_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT

-- ── double_db_name (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT

-- ── dual_key (DB only) ──
-- table exists in DB but no matching entity
--   id1  INT
--   id2  INT
--   data  TEXT

-- ── enc_bad (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   narrow  VARCHAR(200)

-- ── enc_cols (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   col_iso1  VARCHAR(200)
--   col_iso7  VARCHAR(200)
--   col_u8  VARCHAR(200)
--   col_u16  VARCHAR(200)

-- ── enum_notnull_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   status  INT

-- ── enum_string_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   status  TEXT
--   priority  INT

-- ── enum_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   plain_status  INT
--   custom_status  INT

-- ── event (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   title  TEXT
--   event_date  DATE

-- ── findall_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT

-- ── fly_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT

-- ── generic_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   value  TEXT

-- ── CREATE invoices (entity com.example.shop.Invoice) ──
CREATE TABLE invoices (
    id BIGINT DEFAULT 0 NOT NULL PRIMARY KEY,
    order_id BIGINT DEFAULT 0 NOT NULL,
    amount NUMERIC(14,2) DEFAULT 0 NOT NULL,
    paid TINYINT(1) DEFAULT 0 NOT NULL,
    issued_at TIMESTAMP,
    due_date DATE,
    generated_at TIMESTAMP
);

-- ── large_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   value  TEXT

-- ── legacy_carts (DB only) ──
-- table exists in DB but no matching entity
--   cart_id  INT
--   user_id  INT
--   created_at  TIMESTAMP

-- ── map_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT

-- ── nested_level1 (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   label  TEXT

-- ── nested_level2 (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   info  TEXT

-- ── nested_level3 (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   tag  TEXT

-- ── nt_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT

-- ── nullable_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT
--   nullable_int  INT
--   nullable_string  TEXT

-- ── CREATE order_items (entity com.example.shop.OrderItem) ──
CREATE TABLE order_items (
    id BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    order_id BIGINT DEFAULT 0 NOT NULL,
    quantity NUMERIC(10) DEFAULT 1 NOT NULL,
    unit_price NUMERIC(14,2) DEFAULT 0 NOT NULL,
    discount_percent NUMERIC(3,2) DEFAULT 0 NOT NULL
);
-- WARNING: 1 fields skipped due to missing slot assignment

-- ── orders ──
ALTER TABLE orders ADD COLUMN currency VARCHAR(10) DEFAULT 'EUR' NOT NULL;
ALTER TABLE orders ADD COLUMN notes VARCHAR(1000);
ALTER TABLE orders ADD COLUMN status_ordinal NUMERIC(1) DEFAULT 0 NOT NULL;
ALTER TABLE orders ADD COLUMN placed_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN shipped_at TIMESTAMP;

-- ── CREATE payments (entity com.example.shop.Payment) ──
CREATE TABLE payments (
    id BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    invoice BIGINT DEFAULT 0 NOT NULL,
    method VARCHAR(50) DEFAULT '' NOT NULL,
    transaction_ref VARCHAR(200) DEFAULT '' NOT NULL,
    amount NUMERIC(14,2) DEFAULT 0 NOT NULL,
    occurred_at TIMESTAMP
);

-- ── pk_resolver_test (DB only) ──
-- table exists in DB but no matching entity
--   pk_id  INT
--   name  TEXT

-- ── plain_child (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT
--   is_active  BIT
--   parent  INT

-- ── CREATE product_tags (entity com.example.shop.ProductTag) ──
CREATE TABLE product_tags (
    id BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    tag INT DEFAULT 0 NOT NULL
);
-- WARNING: 1 fields skipped due to missing slot assignment

-- ── products ──
ALTER TABLE products ADD COLUMN category CHAR(36) NOT NULL;
ALTER TABLE products ADD COLUMN in_stock TINYINT(1) DEFAULT 1 NOT NULL;
ALTER TABLE products ADD COLUMN launch_date DATE;
ALTER TABLE products ADD COLUMN last_updated_at TIMESTAMP;

-- ── ref_child (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   title  TEXT
--   parent  INT

-- ── ref_parent (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT

-- ── refl_enum_string_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   status  TEXT
--   priority  INT

-- ── refl_enum_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   plain_status  INT
--   custom_status  INT

-- ── CREATE reviews (entity com.example.shop.Review) ──
CREATE TABLE reviews (
    id BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    author BIGINT DEFAULT 0 NOT NULL,
    rating NUMERIC(1) DEFAULT 5 NOT NULL,
    title VARCHAR(50) DEFAULT '' NOT NULL,
    body VARCHAR(1000),
    verified TINYINT(1) DEFAULT 0 NOT NULL,
    posted_at TIMESTAMP
);
-- WARNING: 1 fields skipped due to missing slot assignment

-- ── CREATE settings (entity com.example.shop.Setting) ──
CREATE TABLE settings (
    `key` VARCHAR(50) DEFAULT '' NOT NULL PRIMARY KEY,
    `value` VARCHAR(1000) DEFAULT '' NOT NULL,
    description VARCHAR(1000)
);

-- ── stress_table (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   data  TEXT

-- ── strict_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT

-- ── CREATE tags (entity com.example.shop.Tag) ──
CREATE TABLE tags (
    id INT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    label VARCHAR(50) DEFAULT '' NOT NULL,
    color VARCHAR(10) DEFAULT '#000000' NOT NULL
);

-- ── test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT

-- ── tree_node (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT
--   parent  INT

-- ── tx_ret (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT

-- ── tx_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT

-- ── unicode_test (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   value  TEXT

-- ── user_entity (DB only) ──
-- table exists in DB but no matching entity
--   id  INT
--   name  TEXT
--   email  TEXT
--   created_by  TEXT

-- ── users ──
ALTER TABLE users ADD COLUMN bio VARCHAR(1000);
ALTER TABLE users ADD COLUMN email_verified TINYINT(1) DEFAULT 0 NOT NULL;
ALTER TABLE users ADD COLUMN created_at TIMESTAMP;
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMP;

-- ── CREATE wishlists (entity com.example.shop.Wishlist) ──
CREATE TABLE wishlists (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner BIGINT DEFAULT 0 NOT NULL,
    name VARCHAR(50) DEFAULT '' NOT NULL,
    public_shared TINYINT(1) DEFAULT 0 NOT NULL,
    created_at TIMESTAMP
);

