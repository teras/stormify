package onl.ycode.stormify.schemasync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path

/* ============================================================================
 *  Mixed multi-table scenario — exercises every diff status in one run and
 *  verifies each lands in (or stays out of) the right export.
 * ============================================================================
 */
class HeadlessMixedScenarioTest {

    /** A single fixture shared by a handful of assertions; rebuilds are cheap
     *  enough that test isolation per @Test wins over reuse, but we factor the
     *  setup so every test reads as the assertion it cares about. */
    private fun mixedFixture(tmp: Path): SqliteFixture = SqliteFixture(tmp,
        // customers — perfectly in sync. orders — DIFF (DB has note that entity
        // doesn't, entity has discount that DB doesn't). products — entity-only
        // (DB doesn't have it). archived_logs — DB-only (no entity claims it).
        ddl = """
            CREATE TABLE customers(id INTEGER PRIMARY KEY, name TEXT NOT NULL);
            CREATE TABLE orders(id INTEGER PRIMARY KEY, qty INTEGER NOT NULL DEFAULT 1, note TEXT);
            CREATE TABLE archived_logs(id INTEGER PRIMARY KEY, message TEXT NOT NULL);
        """.trimIndent(),
        entities = mapOf(
            "Customer" to entity("customers", listOf(
                "@DbField(primaryKey = true) var id: Int = 0",
                "var name: String = \"\"",
            )),
            "Order" to entity("orders", listOf(
                "@DbField(primaryKey = true) var id: Int = 0",
                "var qty: Int = 1",
                "var discount: Int = 0",
            )),
            "Product" to entity("products", listOf(
                "@DbField(primaryKey = true) var id: Int = 0",
                "var name: String = \"\"",
                "var price: Int = 0",
            )),
        ),
    )

    @Test
    fun `default categories surface ALTER for orders, CREATE for products, but no archived_logs`(@TempDir tmp: Path) {
        val sql = mixedFixture(tmp).exportSql()
        // orders.discount is ENTITY_ONLY → ALTER.
        assertTrue(sql.contains("ALTER TABLE orders") && sql.contains("discount"),
            "expected ALTER orders ADD discount:\n$sql")
        // products is whole-table ENTITY_ONLY → CREATE.
        assertTrue(sql.contains("CREATE TABLE products"))
        // archived_logs is DB_ONLY → SQL says nothing about it.
        assertFalse(sql.contains("archived_logs"))
        // customers is in sync → no statements for it.
        assertFalse(sql.contains("ALTER TABLE customers") || sql.contains("CREATE TABLE customers"))
    }

    @Test
    fun `default categories surface splice for orders-note and new entity for archived_logs`(@TempDir tmp: Path) {
        val patch = mixedFixture(tmp).exportKt()
        assertTrue(patch.contains("Order.kt") && patch.contains("+    var note"),
            "expected splice adding note property:\n$patch")
        assertTrue(patch.contains("--- /dev/null") && patch.contains("ArchivedLogs.kt"),
            "expected new entity for archived_logs:\n$patch")
        // customers and products should not appear: customers is in sync,
        // products is entity-only (already exists in source).
        assertFalse(patch.contains("Customer.kt"), "no patch for in-sync entity")
        assertFalse(patch.contains("--- a/src/main/kotlin/demo/Product.kt"), "no patch for ENTITY_ONLY existing source")
    }

    @Test
    fun `--categories db-only narrows the patch to brand-new entity files only`(@TempDir tmp: Path) {
        // db-only is in TABLE_PRESENCE → must include all other groups too.
        val patch = mixedFixture(tmp).exportKt(
            categories = "db-only,tables,views,single,multi,missing-db,missing-kt",
        )
        assertTrue(patch.contains("ArchivedLogs.kt"), "expected the new ArchivedLogs entity:\n$patch")
        assertFalse(patch.contains("Order.kt"), "DIFF on orders should be excluded")
    }

    @Test
    fun `--categories missing-db keeps only DIFF tables with entity-only columns`(@TempDir tmp: Path) {
        // orders has a missing-DB column (discount); customers is SYNCED.
        // missing-db lives in COLUMN_STATUS — needs the BOTH presence to apply.
        val sql = mixedFixture(tmp).exportSql(
            categories = "missing-db,tables,views,single,multi,both",
        )
        assertTrue(sql.contains("ALTER TABLE orders") && sql.contains("discount"))
        assertFalse(sql.contains("CREATE TABLE products"),
            "products is ENTITY_ONLY, not BOTH+missing-db, must stay out:\n$sql")
    }
}

/* ============================================================================
 *  Foreign keys, autoincrement, and richer Kotlin type variety.
 * ============================================================================
 */
class HeadlessSchemaShapesTest {

    // Each pair asserts the actual default DDL fragment the slot profile
    // produces for the given Kotlin type — not what one might guess from the
    // type name. Boolean, integral, decimal and timestamp families all route
    // through different slots and DDL templates, so the matrix doubles as a
    // smoke test that the type-classifier wiring is intact.
    // Each pair asserts the actual default DDL fragment the slot profile
    // produces for the given Kotlin type — not what one might guess from the
    // type name. Boolean/integral/decimal/text all route through different
    // slots and DDL templates, so the matrix doubles as a smoke test that the
    // type-classifier wiring is intact. Programmatic [Files.createTempDirectory]
    // because @TempDir + @ParameterizedTest mix poorly when a Path also
    // sits in the parameter list.
    @ParameterizedTest(name = "Kotlin type {0} → SQL DDL fragment {1}")
    @CsvSource(value = [
        "Boolean,        INTEGER",
        "Long,           NUMERIC(10)",
        "Double,         NUMERIC(14,2)",
        "String,         VARCHAR(200)",
    ])
    fun `Kotlin types map to expected SQL DDL fragments`(
        kotlinType: String,
        sqlFragment: String,
    ) {
        val tmp = Files.createTempDirectory("headless-types-")
        val ctx = SqliteFixture(tmp,
            ddl = "CREATE TABLE widgets(id INTEGER PRIMARY KEY, label TEXT NOT NULL)",
            entities = mapOf("Widget" to entity("widgets", listOf(
                "@DbField(primaryKey = true) var id: Int = 0",
                "var label: String = \"\"",
                "var value: $kotlinType? = null",
            ))),
        )
        val sql = ctx.exportSql()
        assertTrue(sql.contains("ALTER TABLE widgets") && sql.contains("value"))
        assertTrue(sql.contains(sqlFragment),
            "expected SQL to mention `$sqlFragment` for Kotlin $kotlinType:\n$sql")
    }

    @Test
    fun `splice for db-only String column lands as a typed property`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            ddl = "CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL, label TEXT)",
            entities = mapOf("Widget" to entity("widgets", listOf(
                "@DbField(primaryKey = true) var id: Int = 0",
                "var name: String = \"\"",
            ))),
        )
        val patch = ctx.exportKt()
        // Nullable text → String? in Kotlin.
        assertTrue(patch.contains("+    var label: String?"),
            "expected new String? splice for nullable label:\n$patch")
    }
}

/* ============================================================================
 *  Filtering: glob shapes + AND/OR semantics across filter flags.
 * ============================================================================
 */
class HeadlessFilterMatrixTest {

    @Test
    fun `--filter-entity and --filter-property intersect (AND across the two flags)`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            ddl = """
                CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL);
                CREATE TABLE orders(id INTEGER PRIMARY KEY);
            """.trimIndent(),
            entities = mapOf(
                "Widget" to entity("widgets", listOf(
                    "@DbField(primaryKey = true) var id: Int = 0",
                    "var name: String = \"\"",
                    "var qty: Int = 0",
                    "var price: Int = 0",
                )),
                "Order" to entity("orders", listOf(
                    "@DbField(primaryKey = true) var id: Int = 0",
                    "var qty: Int = 0",
                )),
            ),
        )
        // Entity glob keeps only widgets; property glob keeps only qty.
        // The intersection: ALTER widgets ADD qty (and nothing else).
        val sql = ctx.exportSql(
            entityFilters = listOf("widgets"),
            propertyFilters = listOf("qty"),
        )
        assertTrue(sql.contains("widgets") && sql.contains("qty"))
        assertFalse(sql.contains("price"), "price filtered by --filter-property:\n$sql")
        assertFalse(sql.contains("orders"), "orders filtered by --filter-entity:\n$sql")
    }

    @Test
    fun `multiple --filter-entity values OR together`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            ddl = """
                CREATE TABLE alpha(id INTEGER PRIMARY KEY);
                CREATE TABLE bravo(id INTEGER PRIMARY KEY);
                CREATE TABLE gamma(id INTEGER PRIMARY KEY);
            """.trimIndent(),
            entities = mapOf(
                "Alpha" to entity("alpha", listOf(
                    "@DbField(primaryKey = true) var id: Int = 0",
                    "var qty: Int = 0",
                )),
                "Bravo" to entity("bravo", listOf(
                    "@DbField(primaryKey = true) var id: Int = 0",
                    "var qty: Int = 0",
                )),
                "Gamma" to entity("gamma", listOf(
                    "@DbField(primaryKey = true) var id: Int = 0",
                    "var qty: Int = 0",
                )),
            ),
        )
        val sql = ctx.exportSql(entityFilters = listOf("alpha", "gamma"))
        assertTrue(sql.contains("ALTER TABLE alpha"))
        assertTrue(sql.contains("ALTER TABLE gamma"))
        assertFalse(sql.contains("ALTER TABLE bravo"), "bravo not in OR set:\n$sql")
    }

    @ParameterizedTest(name = "glob {0} matches {1}, rejects {2}")
    @CsvSource(value = [
        "*_at,        created_at,    user_label",
        "user_*,      user_label,    created_at",
        "????,        size,          length",     // exactly four chars
        "*label*,     surlabel,      qty",        // wildcard on both ends
    ])
    fun `glob shapes work in --filter-property`(
        glob: String, hit: String, miss: String,
        @TempDir tmp: Path,
    ) {
        // DB only has id+title; both [hit] and [miss] are entity-only columns,
        // so they're guaranteed to land in the SQL absent any filter — the
        // glob is the only thing keeping them in or out.
        val ctx = SqliteFixture(tmp,
            ddl = "CREATE TABLE widgets(id INTEGER PRIMARY KEY, title TEXT NOT NULL)",
            entities = mapOf("Widget" to entity("widgets", listOf(
                "@DbField(primaryKey = true) var id: Int = 0",
                "var title: String = \"\"",
                "var $hit: String = \"\"",
                "var $miss: String = \"\"",
            ))),
        )
        val sql = ctx.exportSql(propertyFilters = listOf(glob))
        assertTrue(sql.contains(hit), "$hit must pass glob $glob:\n$sql")
        assertFalse(sql.contains(miss), "$miss must fail glob $glob:\n$sql")
    }
}

/* ============================================================================
 *  Non-togglable diff kinds (TYPE_MISMATCH, default mismatch on SYNCED) must
 *  never produce SQL or patch entries — they require human review and don't
 *  have a sensible auto-action.
 * ============================================================================
 */
class HeadlessNonTogglableTest {

    @Test
    fun `type-mismatch column produces neither SQL nor splice`(@TempDir tmp: Path) {
        // DB stores label as TEXT; entity declares it as Int → TYPE_MISMATCH.
        val ctx = SqliteFixture(tmp,
            ddl = "CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL, label TEXT NOT NULL)",
            entities = mapOf("Widget" to entity("widgets", listOf(
                "@DbField(primaryKey = true) var id: Int = 0",
                "var name: String = \"\"",
                "var label: Int = 0",
            ))),
        )
        val sql = ctx.exportSql()
        val patch = ctx.exportKt()
        assertFalse(sql.contains("ALTER TABLE widgets"), "no ALTER for TYPE_MISMATCH:\n$sql")
        assertFalse(sql.contains("label"), "no SQL for label:\n$sql")
        assertFalse(patch.contains("label"), "no splice for label:\n$patch")
    }

    @Test
    fun `default-mismatch on a synced column is left alone`(@TempDir tmp: Path) {
        // Same TEXT type both sides, but DEFAULTs disagree.
        val ctx = SqliteFixture(tmp,
            ddl = "CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL DEFAULT 'foo')",
            entities = mapOf("Widget" to entity("widgets", listOf(
                "@DbField(primaryKey = true) var id: Int = 0",
                "var name: String = \"bar\"",
            ))),
        )
        val sql = ctx.exportSql()
        val patch = ctx.exportKt()
        assertTrue(sql.contains("nothing to do") || !sql.contains("ALTER"),
            "default-mismatch SYNCED column must not generate ALTER:\n$sql")
        assertEquals("", patch.trim(), "default-mismatch must not generate a splice")
    }
}

/* ============================================================================
 *  Convenience entity builder — the fixture variants above feed it many
 *  different bodies, so we factor it out once.
 * ============================================================================
 */
private fun entity(
    tableName: String,
    body: List<String>,
    pkg: String = "demo",
    classNameOverride: String? = null,
): String = buildString {
    appendLine("package $pkg")
    appendLine("import onl.ycode.stormify.annotation.DbTable")
    appendLine("import onl.ycode.stormify.annotation.DbField")
    appendLine("@DbTable(\"$tableName\")")
    val cls = classNameOverride
        ?: tableName.split("_").joinToString("") { it.replaceFirstChar { ch -> ch.uppercase() } }
    appendLine("class $cls {")
    for (line in body) appendLine("    $line")
    appendLine("}")
}
