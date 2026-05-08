package onl.ycode.stormify.schemasync

import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.config.ConfigStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import onl.ycode.stormify.schemasync.tui.RowCategory

/* -------------------------------------------------------------------------- */
/*  Pure-function helpers                                                     */
/* -------------------------------------------------------------------------- */

class HeadlessHelpersTest {

    @Test
    fun `globToRegex stars match anything case-insensitive`() {
        val rx = globToRegex("public.user_*")
        assertTrue(rx.matches("public.user_login"))
        assertTrue(rx.matches("PUBLIC.USER_LOGIN"))
        assertFalse(rx.matches("public.account"))
    }

    @Test
    fun `globToRegex question mark matches one char`() {
        val q = globToRegex("col?")
        assertTrue(q.matches("col1"))
        assertFalse(q.matches("col"))
        assertFalse(q.matches("col12"))
    }

    @Test
    fun `globToRegex escapes regex metacharacters`() {
        val rx = globToRegex("a.b")
        assertTrue(rx.matches("a.b"))
        assertFalse(rx.matches("axb"))
    }

    @Test
    fun `parseCategoryTokens accepts known tokens`() {
        val ok = parseCategoryTokens("tables,views,missing-db")
        assertNotNull(ok)
        assertEquals(setOf(RowCategory.TABLES, RowCategory.VIEWS, RowCategory.MISSING_DB_FIELDS), ok)
    }

    @Test
    fun `parseCategoryTokens trims and lowercases each token`() {
        val ok = parseCategoryTokens(" Tables , VIEWS ")
        assertEquals(setOf(RowCategory.TABLES, RowCategory.VIEWS), ok)
    }

    @Test
    fun `parseCategoryTokens rejects junk`() {
        assertNull(parseCategoryTokens("tables,wat,views"))
    }

    @Test
    fun `hasHeadlessFlag detects both forms`() {
        assertTrue(hasHeadlessFlag(arrayOf("--export-sql", "out.sql")))
        assertTrue(hasHeadlessFlag(arrayOf("--export-kt=patch.diff")))
        assertFalse(hasHeadlessFlag(arrayOf("--url", "jdbc:sqlite::memory:")))
    }
}

/* -------------------------------------------------------------------------- */
/*  CLI guardrails                                                            */
/* -------------------------------------------------------------------------- */

class HeadlessCliGuardsTest {

    @Test
    fun `missing connection fails fast with exit code 1`(@TempDir tmp: Path) {
        // Bootstrap a state with no ConnectionConfig.
        val store = ConfigStore(tmp.resolve(".schema-sync.toml"))
        val state = ConfigState(store.load().config, store)
        val rc = runHeadless(arrayOf("--export-sql", tmp.resolve("x.sql").toString()), state)
        assertEquals(1, rc)
    }

    @Test
    fun `--export-kt without --sources fails fast`(@TempDir tmp: Path) {
        val ctx = SqliteFixture.empty(tmp)
        val rc = runHeadless(arrayOf("--export-kt", tmp.resolve("x.patch").toString()), ctx.state)
        assertEquals(1, rc)
    }

    @Test
    fun `bad --categories token returns exit code 1`(@TempDir tmp: Path) {
        val ctx = SqliteFixture.empty(tmp)
        val rc = runHeadless(arrayOf(
            "--export-sql", tmp.resolve("x.sql").toString(),
            "--categories", "tables,bogus",
        ), ctx.state)
        assertEquals(1, rc)
    }
}

/* -------------------------------------------------------------------------- */
/*  Output coverage: each diff kind reaches the right export                  */
/* -------------------------------------------------------------------------- */

class HeadlessExportTest {

    @Test
    fun `entity-only column produces ALTER TABLE in SQL`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            ddl = "CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
            entities = mapOf("Widget" to widgetEntity(extras = listOf("var qty: Int = 0"))),
        )
        val sql = ctx.exportSql()
        assertTrue(sql.contains("ALTER TABLE widgets") && sql.contains("qty"),
            "expected ALTER for missing DB column:\n$sql")
        assertFalse(sql.contains("CREATE TABLE"), "should not emit CREATE for an existing table")
    }

    @Test
    fun `entity-only whole table produces CREATE TABLE in SQL`(@TempDir tmp: Path) {
        // No DB tables; entity declares one → ENTITY_ONLY whole-table.
        val ctx = SqliteFixture(tmp,
            ddl = "",
            entities = mapOf("Widget" to widgetEntity()),
        )
        val sql = ctx.exportSql()
        assertTrue(sql.contains("CREATE TABLE widgets"), "expected CREATE TABLE:\n$sql")
        assertFalse(sql.contains("ALTER TABLE"), "should not emit ALTER for a whole-table create")
    }

    @Test
    fun `db-only column produces splice in patch`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            ddl = "CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL, qty INTEGER NOT NULL DEFAULT 0)",
            entities = mapOf("Widget" to widgetEntity()),
        )
        val patch = ctx.exportKt()
        assertTrue(patch.contains("--- a/"), "expected unified-diff hunk for the existing entity file")
        assertTrue(patch.contains("+    var qty"), "expected qty to be spliced in:\n$patch")
        assertFalse(patch.contains("--- /dev/null"), "no whole-new-file section expected")
    }

    @Test
    fun `db-only whole table produces new entity file in patch`(@TempDir tmp: Path) {
        // Two DB tables, only one entity → orders is DB_ONLY.
        val ctx = SqliteFixture(tmp,
            ddl = """
                CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL);
                CREATE TABLE orders(id INTEGER PRIMARY KEY, qty INTEGER NOT NULL DEFAULT 1);
            """.trimIndent(),
            entities = mapOf("Widget" to widgetEntity()),
        )
        val patch = ctx.exportKt()
        assertTrue(patch.contains("--- /dev/null"), "expected /dev/null marker for new file:\n$patch")
        assertTrue(patch.contains("Orders.kt"), "expected new Orders.kt:\n$patch")
        assertTrue(patch.contains("class Orders"))
    }

    @Test
    fun `everything in sync emits empty patch and 'nothing to do' SQL`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            ddl = "CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
            entities = mapOf("Widget" to widgetEntity()),
        )
        val sql = ctx.exportSql()
        assertTrue(sql.contains("nothing to do"), "expected sentinel comment for empty migration:\n$sql")
        assertEquals("", ctx.exportKt().trim(), "patch should be empty when nothing changes")
    }

    @Test
    fun `simultaneous sql and kt exports both run`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            ddl = "CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL, qty INTEGER NOT NULL DEFAULT 0)",
            entities = mapOf("Widget" to widgetEntity(extras = listOf("var label: String = \"\""))),
        )
        val sqlOut = tmp.resolve("m.sql")
        val ktOut = tmp.resolve("p.patch")
        val rc = ctx.run(sqlOut = sqlOut, ktOut = ktOut)
        assertEquals(0, rc)
        assertTrue(Files.exists(sqlOut)); assertTrue(Files.exists(ktOut))
        assertTrue(Files.readString(sqlOut).contains("label"))
        assertTrue(Files.readString(ktOut).contains("qty"))
    }

    @Test
    fun `patch uses relative paths, never doubled-slash absolutes`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            ddl = "CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL, qty INTEGER NOT NULL DEFAULT 0)",
            entities = mapOf("Widget" to widgetEntity()),
        )
        val patch = ctx.exportKt()
        assertFalse(patch.contains("--- a//") || patch.contains("+++ b//"),
            "patch must not have doubled-slash absolute paths:\n${patch.take(200)}")
    }
}

/* -------------------------------------------------------------------------- */
/*  Filtering matrix: --categories / --filter-entity / --filter-property      */
/* -------------------------------------------------------------------------- */

class HeadlessFilteringTest {

    @Test
    fun `--filter-property restricts which columns reach SQL`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            ddl = "CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
            entities = mapOf("Widget" to widgetEntity(
                extras = listOf("var qty: Int = 0", "var price: Int = 0"),
            )),
        )
        val sql = ctx.exportSql(propertyFilters = listOf("qty"))
        assertTrue(sql.contains("qty"))
        assertFalse(sql.contains("price"), "price should be filtered out:\n$sql")
    }

    @Test
    fun `multiple --filter-property are OR-combined`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            ddl = "CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
            entities = mapOf("Widget" to widgetEntity(
                extras = listOf("var qty: Int = 0", "var price: Int = 0", "var label: String = \"\""),
            )),
        )
        val sql = ctx.exportSql(propertyFilters = listOf("qty", "price"))
        assertTrue(sql.contains("qty") && sql.contains("price"))
        assertFalse(sql.contains("label"), "label should be filtered out:\n$sql")
    }

    @Test
    fun `--filter-entity by glob restricts which tables reach SQL`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            ddl = """
                CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL);
                CREATE TABLE orders(id INTEGER PRIMARY KEY);
            """.trimIndent(),
            entities = mapOf(
                "Widget" to widgetEntity(extras = listOf("var qty: Int = 0")),
                "Order" to """
                    package demo
                    import onl.ycode.stormify.annotation.DbTable
                    import onl.ycode.stormify.annotation.DbField
                    @DbTable("orders")
                    class Order {
                        @DbField(primaryKey = true) var id: Int = 0
                        var note: String = ""
                    }
                """.trimIndent(),
            ),
        )
        val sql = ctx.exportSql(entityFilters = listOf("widgets"))
        assertTrue(sql.contains("widgets"))
        assertFalse(sql.contains("orders") || sql.contains("note"),
            "orders/note should be filtered out:\n$sql")
    }

    @Test
    fun `--filter-entity matches simple class name as well as tableKey`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            ddl = """
                CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL);
                CREATE TABLE orders(id INTEGER PRIMARY KEY);
            """.trimIndent(),
            entities = mapOf(
                "Widget" to widgetEntity(extras = listOf("var qty: Int = 0")),
                "Order" to """
                    package demo
                    import onl.ycode.stormify.annotation.DbTable
                    import onl.ycode.stormify.annotation.DbField
                    @DbTable("orders")
                    class Order {
                        @DbField(primaryKey = true) var id: Int = 0
                        var note: String = ""
                    }
                """.trimIndent(),
            ),
        )
        // Match by class name, not by tableKey.
        val sql = ctx.exportSql(entityFilters = listOf("Widget"))
        assertTrue(sql.contains("widgets"))
        assertFalse(sql.contains("orders"))
    }

    @Test
    fun `--categories tables-only excludes views from SQL even when DDL drift exists`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            ddl = """
                CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL);
                CREATE VIEW widgets_v AS SELECT id, name FROM widgets;
            """.trimIndent(),
            entities = mapOf("Widget" to widgetEntity(extras = listOf("var qty: Int = 0"))),
        )
        // With `--categories tables`, views never make it through. (A view has
        // no diff anyway in this fixture, but this asserts the category gate
        // is wired and not inverted.)
        val sql = ctx.exportSql(categories = "tables,both,missing-db,missing-kt,single,multi")
        assertTrue(sql.contains("widgets") && sql.contains("qty"))
        assertFalse(sql.contains("widgets_v"))
    }

    @Test
    fun `--categories entity-only restricts SQL to brand-new tables`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            // widgets exists; orders does NOT (entity-only).
            ddl = "CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
            entities = mapOf(
                "Widget" to widgetEntity(extras = listOf("var qty: Int = 0")),
                "Order" to """
                    package demo
                    import onl.ycode.stormify.annotation.DbTable
                    import onl.ycode.stormify.annotation.DbField
                    @DbTable("orders")
                    class Order {
                        @DbField(primaryKey = true) var id: Int = 0
                        var note: String = ""
                    }
                """.trimIndent(),
            ),
        )
        // entity-only is in TABLE_PRESENCE; the AND-across-groups rule needs
        // every group to pass, so include category tokens for every group.
        val sql = ctx.exportSql(categories = "entity-only,tables,views,single,multi,missing-db,missing-kt")
        assertTrue(sql.contains("CREATE TABLE orders"), "expected CREATE for entity-only orders:\n$sql")
        assertFalse(sql.contains("ALTER TABLE widgets"),
            "ALTER on widgets should be filtered out (widgets is BOTH, not entity-only):\n$sql")
    }

    @Test
    fun `--categories all keeps every diff kind`(@TempDir tmp: Path) {
        val ctx = SqliteFixture(tmp,
            ddl = "CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
            entities = mapOf(
                "Widget" to widgetEntity(extras = listOf("var qty: Int = 0")),
                "Order" to """
                    package demo
                    import onl.ycode.stormify.annotation.DbTable
                    import onl.ycode.stormify.annotation.DbField
                    @DbTable("orders")
                    class Order {
                        @DbField(primaryKey = true) var id: Int = 0
                        var note: String = ""
                    }
                """.trimIndent(),
            ),
        )
        val sql = ctx.exportSql(categories = "all")
        assertTrue(sql.contains("ALTER TABLE widgets"))
        assertTrue(sql.contains("CREATE TABLE orders"))
    }
}

/* -------------------------------------------------------------------------- */
/*  Test fixture helpers                                                      */
/* -------------------------------------------------------------------------- */

/** Default Widget entity body. [extras] are appended as new properties. */
private fun widgetEntity(extras: List<String> = emptyList()): String = buildString {
    appendLine("package demo")
    appendLine("import onl.ycode.stormify.annotation.DbTable")
    appendLine("import onl.ycode.stormify.annotation.DbField")
    appendLine("@DbTable(\"widgets\")")
    appendLine("class Widget {")
    appendLine("    @DbField(primaryKey = true) var id: Int = 0")
    appendLine("    var name: String = \"\"")
    for (e in extras) appendLine("    $e")
    appendLine("}")
}
