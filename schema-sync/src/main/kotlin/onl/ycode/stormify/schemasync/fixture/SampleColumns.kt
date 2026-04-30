package onl.ycode.stormify.schemasync.fixture

import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.model.SlotCategory

/** Offline fallback when no JDBC connection is configured. */
val sampleColumns: List<ColumnRef> = listOf(
    col("users", "first_name", SlotCategory.TEXT, "VARCHAR(50)"),
    col("users", "last_name", SlotCategory.TEXT, "VARCHAR(50)"),
    col("users", "email", SlotCategory.TEXT, "VARCHAR(255)"),
    col("users", "phone", SlotCategory.TEXT, "VARCHAR(20)"),
    col("users", "bio", SlotCategory.TEXT, "TEXT"),
    col("users", "is_active", SlotCategory.INTEGRAL, "SMALLINT"),
    col("users", "user_id", SlotCategory.INTEGRAL, "BIGINT"),
    col("orders", "order_id", SlotCategory.INTEGRAL, "BIGINT"),
    col("orders", "customer_id", SlotCategory.INTEGRAL, "BIGINT"),
    col("orders", "item_count", SlotCategory.INTEGRAL, "INTEGER"),
    col("orders", "total_amount", SlotCategory.DECIMAL, "NUMERIC(14,2)"),
    col("orders", "tax_amount", SlotCategory.DECIMAL, "NUMERIC(14,2)"),
    col("orders", "tax_rate", SlotCategory.DECIMAL, "NUMERIC(5,4)"),
    col("orders", "discount_pct", SlotCategory.INTEGRAL, "SMALLINT"),
    col("products", "name", SlotCategory.TEXT, "VARCHAR(100)"),
    col("products", "description", SlotCategory.TEXT, "TEXT"),
    col("products", "sku", SlotCategory.TEXT, "VARCHAR(20)"),
    col("products", "weight_kg", SlotCategory.DECIMAL, "NUMERIC(11,3)"),
    col("products", "stock_quantity", SlotCategory.INTEGRAL, "INTEGER"),
    col("products", "unit_price", SlotCategory.DECIMAL, "NUMERIC(14,2)"),
    col("locations", "latitude", SlotCategory.DECIMAL, "NUMERIC(9,6)"),
    col("locations", "longitude", SlotCategory.DECIMAL, "NUMERIC(9,6)"),
    col("locations", "country_code", SlotCategory.TEXT, "VARCHAR(3)"),
    col("locations", "address", SlotCategory.TEXT, "VARCHAR(200)"),
    col("invoices", "invoice_number", SlotCategory.TEXT, "VARCHAR(20)"),
    col("invoices", "amount_due", SlotCategory.DECIMAL, "NUMERIC(14,2)"),
    col("invoices", "exchange_rate", SlotCategory.DECIMAL, "NUMERIC(19,8)"),
    col("audit_log", "user_agent", SlotCategory.TEXT, "TEXT"),
    col("audit_log", "ip_address", SlotCategory.TEXT, "VARCHAR(45)"),
    col("audit_log", "timestamp_ms", SlotCategory.INTEGRAL, "BIGINT"),
    col("config", "ph_level", SlotCategory.DECIMAL, "NUMERIC(5,1)"),
    col("config", "co2_ppm", SlotCategory.DECIMAL, "NUMERIC(10,5)"),
    col("config", "completion", SlotCategory.INTEGRAL, "SMALLINT"),
)

private fun col(table: String, name: String, category: SlotCategory, dbType: String): ColumnRef =
    ColumnRef(schema = null, table = table, name = name, category = category, dbType = dbType)
