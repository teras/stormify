package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.schemasync.model.SqlTypeCode
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SQLite is the cheapest dialect to verify because it runs in-process with
 * no external dependency. The dictionary path here exercises:
 *  - `sqlite_master` join with `pragma_table_info` for column listing.
 *  - `pragma_foreign_key_list` for FK edges.
 *  - The declared-type parser ([SqliteDictionary] internals) producing
 *    correct [SqlTypeCode] mappings via affinity rules.
 */
class SqliteDictionaryTest {

    private val tempDb: File = File.createTempFile("schema-sync-test-", ".db").apply { deleteOnExit() }

    @AfterTest
    fun cleanup() {
        tempDb.delete()
    }

    @Test
    fun introspectsSyntheticSchema() {
        Class.forName("org.sqlite.JDBC")
        val stormify = Stormify(DriverManagerDataSource("jdbc:sqlite:${tempDb.absolutePath}", null, null))
        seedSqliteSchema(stormify)
        val intro = DbIntrospector(stormify)

        val tables = intro.listTables()
        assertEquals(setOf("author", "book", "recent_books"), tables.map { it.name }.toSet())
        assertTrue(tables.any { it.name == "recent_books" && it.kind == DbIntrospector.TableId.Kind.VIEW })

        val cols = intro.listColumns().associateBy { it.table to it.name }

        val title = assertNotNull(cols["book" to "title"])
        assertEquals(SqlTypeCode.VARCHAR, title.sqlType)
        assertEquals(false, title.nullable)
        assertEquals(255, title.precision)

        val price = assertNotNull(cols["book" to "price"])
        assertEquals(SqlTypeCode.NUMERIC, price.sqlType)
        assertEquals(10, price.precision)

        val active = assertNotNull(cols["author" to "active"])
        assertEquals(SqlTypeCode.BOOLEAN, active.sqlType)
        assertEquals("1", active.defaultValue)

        val cover = assertNotNull(cols["book" to "cover"])
        assertEquals(SqlTypeCode.BLOB, cover.sqlType)

        val authorFk = assertNotNull(cols["book" to "author_id"])
        assertEquals("author", authorFk.referencedTable)
        assertEquals("id", authorFk.referencedColumn)
    }

    private fun seedSqliteSchema(stormify: Stormify) {
        stormify.executeUpdate("DROP VIEW IF EXISTS recent_books")
        stormify.executeUpdate("DROP TABLE IF EXISTS book")
        stormify.executeUpdate("DROP TABLE IF EXISTS author")
        stormify.executeUpdate(
            """
            CREATE TABLE author (
                id INTEGER PRIMARY KEY,
                full_name TEXT NOT NULL,
                email VARCHAR(120),
                active BOOLEAN DEFAULT 1
            )
            """.trimIndent(),
        )
        stormify.executeUpdate(
            """
            CREATE TABLE book (
                id INTEGER PRIMARY KEY,
                title VARCHAR(255) NOT NULL,
                price NUMERIC(10, 2),
                author_id INTEGER NOT NULL,
                published_on DATE,
                cover BLOB,
                FOREIGN KEY (author_id) REFERENCES author(id)
            )
            """.trimIndent(),
        )
        stormify.executeUpdate("CREATE VIEW recent_books AS SELECT * FROM book")
    }
}
