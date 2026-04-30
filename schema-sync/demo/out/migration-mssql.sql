-- schema-sync migration
-- Generated 2026-04-28 for mssql

-- ── CamelPolicyEntity (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   firstName  nvarchar(2147483647)
--   lastName  nvarchar(2147483647)

-- ── UPPER_POLICY_ENTITY (DB only) ──
-- table exists in DB but no matching entity
--   ID  int
--   FIRST_NAME  nvarchar(2147483647)
--   LAST_NAME  nvarchar(2147483647)

-- ── CREATE addresses (entity com.example.shop.Address) ──
CREATE TABLE addresses (
    id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    owner BIGINT DEFAULT 0 NOT NULL,
    street VARCHAR(200) DEFAULT '' NOT NULL,
    city VARCHAR(50) DEFAULT '' NOT NULL,
    state VARCHAR(50) DEFAULT '' NOT NULL,
    zip VARCHAR(10) DEFAULT '' NOT NULL,
    country VARCHAR(50) DEFAULT '' NOT NULL,
    is_primary BIT DEFAULT 0 NOT NULL,
    validated_at DATETIMEOFFSET
);

-- ── agg_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)

-- ── all_types (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   byte_val  smallint
--   short_val  smallint
--   int_val  int
--   long_val  bigint
--   float_val  real
--   double_val  float
--   bool_val  bit
--   string_val  nvarchar(2147483647)

-- ── annotated_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   custom_col  nvarchar(2147483647)
--   read_only_on_create  nvarchar(2147483647)
--   read_only_on_update  nvarchar(2147483647)

-- ── at_child (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)
--   is_active  bit
--   parent  int

-- ── audit_events (DB only) ──
-- table exists in DB but no matching entity
--   event_id  bigint identity
--   payload  varchar(2147483647)

-- ── CREATE audit_log (entity com.example.shop.AuditLog) ──
CREATE TABLE audit_log (
    id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    actor BIGINT DEFAULT 0 NOT NULL,
    action VARCHAR(50) DEFAULT '' NOT NULL,
    entity_type VARCHAR(50) DEFAULT '' NOT NULL,
    entity_id VARCHAR(50) DEFAULT '' NOT NULL,
    occurred_at DATETIMEOFFSET
);

-- ── auto_child (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   data  nvarchar(2147483647)
--   parent  int

-- ── auto_increment (DB only) ──
-- table exists in DB but no matching entity
--   id  int identity
--   name  nvarchar(2147483647)

-- ── auto_parent (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   other  nvarchar(2147483647)
--   data  nvarchar(2147483647)

-- ── bd_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   val  decimal(38,10)

-- ── bi_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   val  decimal(38)

-- ── blacklist_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)
--   secret  nvarchar(2147483647)

-- ── blob_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   blob_data  varbinary
--   clob_as_chars  nvarchar(2147483647)
--   clob_as_string  nvarchar(2147483647)

-- ── camel_entity (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   first_name  nvarchar(2147483647)
--   last_name  nvarchar(2147483647)

-- ── CREATE categories (entity com.example.shop.Category) ──
CREATE TABLE categories (
    id UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    name VARCHAR(50) DEFAULT '' NOT NULL,
    description VARCHAR(1000),
    parent UNIQUEIDENTIFIER,
    sort_order NUMERIC(10) DEFAULT 0 NOT NULL
);

-- ── child (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)
--   parent  int

-- ── conversion_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   value  nvarchar(2147483647)

-- ── ct_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)

-- ── double_db_name (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)

-- ── dual_key (DB only) ──
-- table exists in DB but no matching entity
--   id1  int
--   id2  int
--   data  nvarchar(2147483647)

-- ── enc_bad (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   narrow  varchar(200)

-- ── enc_cols (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   col_iso1  varchar(200)
--   col_iso7  varchar(200)
--   col_u8  varchar(200)
--   col_u16  nvarchar(200)

-- ── enum_notnull_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   status  int

-- ── enum_string_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   status  nvarchar(2147483647)
--   priority  int

-- ── enum_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   plain_status  int
--   custom_status  int

-- ── event (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   title  nvarchar(2147483647)
--   event_date  date

-- ── findall_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)

-- ── fly_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)

-- ── generic_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   value  nvarchar(2147483647)

-- ── CREATE invoices (entity com.example.shop.Invoice) ──
CREATE TABLE invoices (
    id BIGINT DEFAULT 0 NOT NULL PRIMARY KEY,
    order_id BIGINT DEFAULT 0 NOT NULL,
    amount NUMERIC(14,2) DEFAULT 0 NOT NULL,
    paid BIT DEFAULT 0 NOT NULL,
    issued_at DATETIMEOFFSET,
    due_date DATE,
    generated_at DATETIMEOFFSET
);

-- ── large_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   value  nvarchar(2147483647)

-- ── legacy_carts (DB only) ──
-- table exists in DB but no matching entity
--   cart_id  int
--   user_id  int
--   created_at  datetime2

-- ── map_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)

-- ── nested_level1 (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   label  nvarchar(2147483647)

-- ── nested_level2 (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   info  nvarchar(2147483647)

-- ── nested_level3 (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   tag  nvarchar(2147483647)

-- ── nt_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)

-- ── nullable_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)
--   nullable_int  int
--   nullable_string  nvarchar(2147483647)

-- ── CREATE order_items (entity com.example.shop.OrderItem) ──
CREATE TABLE order_items (
    id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    order_id BIGINT DEFAULT 0 NOT NULL,
    quantity NUMERIC(10) DEFAULT 1 NOT NULL,
    unit_price NUMERIC(14,2) DEFAULT 0 NOT NULL,
    discount_percent NUMERIC(3,2) DEFAULT 0 NOT NULL
);
-- WARNING: 1 fields skipped due to missing slot assignment

-- ── orders ──
ALTER TABLE orders ADD currency VARCHAR(10) DEFAULT 'EUR' NOT NULL;
ALTER TABLE orders ADD notes VARCHAR(1000);
ALTER TABLE orders ADD status_ordinal NUMERIC(1) DEFAULT 0 NOT NULL;
ALTER TABLE orders ADD placed_at DATETIMEOFFSET;
ALTER TABLE orders ADD shipped_at DATETIMEOFFSET;

-- ── CREATE payments (entity com.example.shop.Payment) ──
CREATE TABLE payments (
    id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    invoice BIGINT DEFAULT 0 NOT NULL,
    method VARCHAR(50) DEFAULT '' NOT NULL,
    transaction_ref VARCHAR(200) DEFAULT '' NOT NULL,
    amount NUMERIC(14,2) DEFAULT 0 NOT NULL,
    occurred_at DATETIMEOFFSET
);

-- ── pk_resolver_test (DB only) ──
-- table exists in DB but no matching entity
--   pk_id  int
--   name  nvarchar(2147483647)

-- ── plain_child (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)
--   is_active  bit
--   parent  int

-- ── CREATE product_tags (entity com.example.shop.ProductTag) ──
CREATE TABLE product_tags (
    id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    tag INT DEFAULT 0 NOT NULL
);
-- WARNING: 1 fields skipped due to missing slot assignment

-- ── products ──
ALTER TABLE products ADD category UNIQUEIDENTIFIER NOT NULL;
ALTER TABLE products ADD in_stock BIT DEFAULT 1 NOT NULL;
ALTER TABLE products ADD launch_date DATE;
ALTER TABLE products ADD last_updated_at DATETIMEOFFSET;

-- ── ref_child (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   title  nvarchar(2147483647)
--   parent  int

-- ── ref_parent (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)

-- ── refl_enum_string_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   status  nvarchar(2147483647)
--   priority  int

-- ── refl_enum_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   plain_status  int
--   custom_status  int

-- ── CREATE reviews (entity com.example.shop.Review) ──
CREATE TABLE reviews (
    id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    author BIGINT DEFAULT 0 NOT NULL,
    rating NUMERIC(1) DEFAULT 5 NOT NULL,
    title VARCHAR(50) DEFAULT '' NOT NULL,
    body VARCHAR(1000),
    verified BIT DEFAULT 0 NOT NULL,
    posted_at DATETIMEOFFSET
);
-- WARNING: 1 fields skipped due to missing slot assignment

-- ── CREATE settings (entity com.example.shop.Setting) ──
CREATE TABLE settings (
    [key] VARCHAR(50) DEFAULT '' NOT NULL PRIMARY KEY,
    [value] VARCHAR(1000) DEFAULT '' NOT NULL,
    description VARCHAR(1000)
);

-- ── stress_table (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   data  nvarchar(2147483647)

-- ── strict_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)

-- ── CREATE tags (entity com.example.shop.Tag) ──
CREATE TABLE tags (
    id INT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    label VARCHAR(50) DEFAULT '' NOT NULL,
    color VARCHAR(10) DEFAULT '#000000' NOT NULL
);

-- ── test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)

-- ── tree_node (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)
--   parent  int

-- ── tx_ret (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)

-- ── tx_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)

-- ── unicode_test (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   value  nvarchar(2147483647)

-- ── user_entity (DB only) ──
-- table exists in DB but no matching entity
--   id  int
--   name  nvarchar(2147483647)
--   email  nvarchar(2147483647)
--   created_by  nvarchar(2147483647)

-- ── users ──
ALTER TABLE users ADD bio VARCHAR(1000);
ALTER TABLE users ADD email_verified BIT DEFAULT 0 NOT NULL;
ALTER TABLE users ADD created_at DATETIMEOFFSET;
ALTER TABLE users ADD last_login_at DATETIMEOFFSET;

-- ── CREATE wishlists (entity com.example.shop.Wishlist) ──
CREATE TABLE wishlists (
    id UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    owner BIGINT DEFAULT 0 NOT NULL,
    name VARCHAR(50) DEFAULT '' NOT NULL,
    public_shared BIT DEFAULT 0 NOT NULL,
    created_at DATETIMEOFFSET
);

