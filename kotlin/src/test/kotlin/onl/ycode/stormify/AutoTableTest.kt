// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify

import ch.qos.logback.classic.Level.ERROR
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import onl.ycode.stormify.StormifyManager.stormify
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@DbTable(name = "kt_parent")
class Parent : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int? = null
    var data by db("")
    var other by db("")

    var myChildren by lazyDetails<ChildT>()
}

@DbTable(name = "kt_child")
class ChildT : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int? = null
    var data by db("")
    var parent: Parent? by db(null)
}

private fun textType(): String {
    val d = stormify().sqlDialect
    return when {
        d == SqlDialect.ORACLE_NEW || d == SqlDialect.ORACLE_OLD -> "VARCHAR2(4000)"
        d == SqlDialect.SQL_SERVER_NEW || d == SqlDialect.SQL_SERVER_OLD -> "NVARCHAR(MAX)"
        else -> "TEXT"
    }
}

private fun intPrimaryKey(column: String): String {
    val d = stormify().sqlDialect
    return if (d == SqlDialect.ORACLE_NEW || d == SqlDialect.ORACLE_OLD) "$column NUMBER(10) PRIMARY KEY"
    else "$column INT PRIMARY KEY"
}

private fun intColumn(column: String): String {
    val d = stormify().sqlDialect
    return if (d == SqlDialect.ORACLE_NEW || d == SqlDialect.ORACLE_OLD) "$column NUMBER(10)"
    else "$column INT"
}

private fun dropTable(name: String) {
    val d = stormify().sqlDialect
    if (d == SqlDialect.ORACLE_NEW || d == SqlDialect.ORACLE_OLD) {
        try { stormify().executeUpdate("DROP TABLE $name") } catch (_: Exception) {}
    } else {
        stormify().executeUpdate("DROP TABLE IF EXISTS $name")
    }
}

class AutoTableTest {
    val logger = TestLogger()

    companion object {
        private var dbAvailable = false

        @JvmStatic
        @BeforeAll
        fun initDatabase() {
            val configPath = System.getProperty("stormify.test.config")?.ifEmpty { null }
            try {
                val config = if (configPath != null) HikariConfig(configPath) else HikariConfig().apply {
                    jdbcUrl = "jdbc:sqlite::memory:"
                }
                (LoggerFactory.getLogger("com.zaxxer.hikari") as ch.qos.logback.classic.Logger).level = ERROR
                stormify().dataSource = HikariDataSource(config)
            } catch (e: Exception) {
                e.printStackTrace()
                println("********** Database not available **********")
                return
            }
            if (!stormify().dataSource.connection.use { it.isValid(10) })
                throw IllegalStateException("Database connection not available")
            stormify().registerPrimaryKeyResolver(0) { _, c -> c.lowercase().startsWith("id") }
            dbAvailable = true
            println("Connected to database, dialect: ${stormify().sqlDialect}")
        }
    }

    @BeforeEach
    fun checkDb() {
        assumeTrue(dbAvailable, "Database not available")
        logger() // clear pending log entries
    }

    @AfterTest
    fun cleanup() {
        logger.close()
    }

    @Test
    fun testAutoTable() {
        val s = stormify()

        // Drop and create tables using dialect-aware DDL
        dropTable("kt_child")
        dropTable("kt_parent")
        s.executeUpdate("CREATE TABLE kt_parent (${intPrimaryKey("id")}, data ${textType()}, other ${textType()})")
        s.executeUpdate("CREATE TABLE kt_child (${intPrimaryKey("id")}, data ${textType()}, ${intColumn("parent")}, FOREIGN KEY(parent) REFERENCES kt_parent(id))")
        logger() // clear DDL logs

        val parent = Parent().apply {
            data = "I am a parent"
            other = "This is my other fields"
            id = 17
        }
        val child = ChildT().apply {
            data = "I am a child"
            this.parent = parent
            id = 23
        }

        parent.create()
        child.create()
        val ch = findAll<ChildT>()[0]
        logger() // clear insert/select logs

        assertEquals("I am a child", ch.data)
        assertEquals("Parent[id=17]", ch.parent.toString())
        assertEquals("", logger())
        assertEquals("I am a parent", ch.parent?.data)
        logger() // clear populate log

        ChildT().apply {
            data = "I am a child 2"
            this.parent = parent
            id = 2
        }.create()

        ChildT().apply {
            data = "I am a child 3"
            this.parent = parent
            id = 3
        }.create()
        logger() // clear insert logs

        val childIds = parent.myChildren.mapNotNull { it.id }.sorted()
        assertEquals(listOf(2, 3, 23), childIds)
        logger() // clear select log

        assertFalse(parent.myChildren.isEmpty())
        parent.myChildren = emptyList()
        assertEquals("[]", parent.myChildren.toString())
        assertEquals("", logger())
    }
}
