package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.schemasync.model.SqlTypeCode
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Integration tests against the docker containers managed by
 * `testing/test.sh`. Each dialect is independent: if the corresponding
 * container is not reachable the test prints a skip notice and passes,
 * so the suite remains green when run without docker.
 *
 * To run:
 *   ./testing/test.sh up postgresql mysql mariadb mssql
 *   gradle :schema-sync:test
 */
class ServerDictionaryTest {

    @Test
    fun postgresIntrospection() {
        runIfAvailable(
            driver = "org.postgresql.Driver",
            url = "jdbc:postgresql://localhost:15432/stormify_test",
            user = "stormify",
            password = "Stormify1!",
            label = "postgresql",
        ) { stormify ->
            seedAnsiSchema(stormify, supportsBoolean = true, varbinary = "BYTEA")
            val intro = DbIntrospector(stormify)
            val tables = intro.listTables().filter { it.name in setOf("ss_author", "ss_book") }
            assertEquals(2, tables.size, "tables: ${tables.map { it.name }}")
            val cols = intro.listColumns().filter { it.table.startsWith("ss_") }.associateBy { it.table to it.name }
            assertEquals(SqlTypeCode.VARCHAR, cols["ss_book" to "title"]?.sqlType)
            assertEquals(SqlTypeCode.NUMERIC, cols["ss_book" to "price"]?.sqlType)
            assertEquals(SqlTypeCode.BOOLEAN, cols["ss_author" to "active"]?.sqlType)
            assertEquals("ss_author", cols["ss_book" to "author_id"]?.referencedTable)
        }
    }

    @Test
    fun mysqlIntrospection() {
        runIfAvailable(
            driver = "com.mysql.cj.jdbc.Driver",
            url = "jdbc:mysql://localhost:13306/stormify_test",
            user = "stormify",
            password = "Stormify1!",
            label = "mysql",
        ) { stormify ->
            seedMysqlSchema(stormify)
            assertMysqlIntrospection(stormify)
        }
    }

    @Test
    fun mariadbIntrospection() {
        runIfAvailable(
            driver = "org.mariadb.jdbc.Driver",
            url = "jdbc:mariadb://localhost:13307/stormify_test",
            user = "stormify",
            password = "Stormify1!",
            label = "mariadb",
        ) { stormify ->
            seedMysqlSchema(stormify)
            assertMysqlIntrospection(stormify)
        }
    }

    @Test
    fun mssqlIntrospection() {
        runIfAvailable(
            driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            url = "jdbc:sqlserver://localhost:11433;databaseName=stormify_test;encrypt=false",
            user = "sa",
            password = "Stormify1!",
            label = "mssql",
        ) { stormify ->
            seedAnsiSchema(stormify, supportsBoolean = false, varbinary = "VARBINARY(MAX)")
            val intro = DbIntrospector(stormify)
            val cols = intro.listColumns().filter { it.table.startsWith("ss_") }.associateBy { it.table to it.name }
            assertEquals(SqlTypeCode.VARCHAR, cols["ss_book" to "title"]?.sqlType)
            assertEquals(SqlTypeCode.DECIMAL, cols["ss_book" to "price"]?.sqlType)
            // Bit -> BOOLEAN in our mapping.
            assertEquals(SqlTypeCode.BOOLEAN, cols["ss_author" to "active"]?.sqlType)
            assertEquals("ss_author", cols["ss_book" to "author_id"]?.referencedTable)
        }
    }

    private fun assertMysqlIntrospection(stormify: Stormify) {
        val intro = DbIntrospector(stormify)
        val cols = intro.listColumns().filter { it.table.startsWith("ss_") }.associateBy { it.table to it.name }
        assertEquals(SqlTypeCode.VARCHAR, cols["ss_book" to "title"]?.sqlType)
        assertEquals(SqlTypeCode.DECIMAL, cols["ss_book" to "price"]?.sqlType)
        // tinyint(1) -> BOOLEAN per MySQL convention.
        assertEquals(SqlTypeCode.BOOLEAN, cols["ss_author" to "active"]?.sqlType)
        assertEquals("ss_author", cols["ss_book" to "author_id"]?.referencedTable)
    }

    /** Drops & re-creates author/book using ANSI-ish DDL. [supportsBoolean]
     *  controls whether the boolean column uses `BOOLEAN` (PG) or `BIT` (MSSQL). */
    private fun seedAnsiSchema(stormify: Stormify, supportsBoolean: Boolean, varbinary: String) {
        val boolType = if (supportsBoolean) "BOOLEAN" else "BIT"
        runCatching { stormify.executeUpdate("DROP TABLE ss_book") }
        runCatching { stormify.executeUpdate("DROP TABLE ss_author") }
        stormify.executeUpdate(
            """
            CREATE TABLE ss_author (
                id INTEGER PRIMARY KEY,
                full_name VARCHAR(120) NOT NULL,
                active $boolType
            )
            """.trimIndent(),
        )
        stormify.executeUpdate(
            """
            CREATE TABLE ss_book (
                id INTEGER PRIMARY KEY,
                title VARCHAR(255) NOT NULL,
                price NUMERIC(10, 2),
                author_id INTEGER NOT NULL,
                cover $varbinary,
                CONSTRAINT ss_book_author_fk FOREIGN KEY (author_id) REFERENCES ss_author(id)
            )
            """.trimIndent(),
        )
    }

    private fun seedMysqlSchema(stormify: Stormify) {
        runCatching { stormify.executeUpdate("DROP TABLE ss_book") }
        runCatching { stormify.executeUpdate("DROP TABLE ss_author") }
        stormify.executeUpdate(
            """
            CREATE TABLE ss_author (
                id INT PRIMARY KEY,
                full_name VARCHAR(120) NOT NULL,
                active TINYINT(1)
            )
            """.trimIndent(),
        )
        stormify.executeUpdate(
            """
            CREATE TABLE ss_book (
                id INT PRIMARY KEY,
                title VARCHAR(255) NOT NULL,
                price DECIMAL(10, 2),
                author_id INT NOT NULL,
                cover BLOB,
                CONSTRAINT ss_book_author_fk FOREIGN KEY (author_id) REFERENCES ss_author(id)
            )
            """.trimIndent(),
        )
    }

    private fun runIfAvailable(
        driver: String,
        url: String,
        user: String,
        password: String,
        label: String,
        block: (Stormify) -> Unit,
    ) {
        runCatching { Class.forName(driver) }
        // Probe with raw JDBC first so the test can skip cleanly when the
        // container isn't running, instead of dragging Stormify into the
        // failure path with a non-obvious wrapped error.
        try {
            DriverManager.getConnection(url, user, password).close()
        } catch (ex: Exception) {
            println("[$label skipped — container not reachable: ${ex.message}]")
            return
        }
        val stormify = Stormify(DriverManagerDataSource(url, user, password))
        block(stormify)
    }
}
