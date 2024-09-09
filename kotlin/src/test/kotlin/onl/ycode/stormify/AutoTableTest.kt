// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify

import ch.qos.logback.classic.Level.ERROR
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import onl.ycode.stormify.StormifyManager.stormify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.assertEquals

class Parent : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int? = null
    var data by db("")
    var other by db("")

    var myChildren by lazyDetails<ChildT>()
}

@DbTable(name = "child")
class ChildT : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int? = null
    var data by db("")
    var parent: Parent? by db(null)
}

private const val configName = "/hikari.properties"

class AutoTableTest {
    val logger = TestLogger()

    @BeforeEach
    fun setup() {
        try {
            stormify().dataSource = HikariDataSource(HikariConfig(configName).apply {
                (LoggerFactory.getLogger("com.zaxxer.hikari") as ch.qos.logback.classic.Logger).level = ERROR
            })
        } catch (e: Exception) {
            e.printStackTrace()
            println("********** Database not available **********")
            return
        }
        if (!stormify().dataSource.connection.use { it.isValid(10) })
            throw IllegalStateException("Database connection not available")
        stormify().registerPrimaryKeyResolver(0) { _, c -> c.lowercase().startsWith("id") }
    }

    @AfterTest
    fun cleanup() {
        logger.close()
    }

    @Test
    fun test() {
        if (!stormify().isDataSourcePresent)
            return

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

        "DROP TABLE IF EXISTS ${ChildT::class.db}".executeUpdate()
        "DROP TABLE IF EXISTS ${Parent::class.db}".executeUpdate()
        "CREATE TABLE IF NOT EXISTS ${Parent::class.db} (id INT PRIMARY KEY, data TEXT, other TEXT)".executeUpdate()
        "CREATE TABLE IF NOT EXISTS ${ChildT::class.db} (id INT PRIMARY KEY, data TEXT, parent INT, FOREIGN KEY(parent) REFERENCES ${Parent::class.db}(id))".executeUpdate()
        assertEquals(
            """DROP TABLE IF EXISTS child
DROP TABLE IF EXISTS parent
CREATE TABLE IF NOT EXISTS parent (id INT PRIMARY KEY, data TEXT, other TEXT)
CREATE TABLE IF NOT EXISTS child (id INT PRIMARY KEY, data TEXT, parent INT, FOREIGN KEY(parent) REFERENCES parent(id))
""", logger()
        )


        parent.create()
        child.create()
        val ch = findAll<ChildT>()[0]
        assertEquals(
            """INSERT INTO parent (data, id, other) VALUES (?, ?, ?) -- [I am a parent, 17, This is my other fields]
INSERT INTO child (data, id, parent) VALUES (?, ?, ?) -- [I am a child, 23, 17]
SELECT * FROM child
""", logger()
        )

        assertEquals("I am a child", ch.data)
        assertEquals("Parent[id=17]", ch.parent.toString())
        assertEquals("", logger())
        assertEquals("I am a parent", ch.parent?.data)
        assertEquals("SELECT * FROM parent WHERE id = ? -- [17]\n", logger())

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
        assertEquals(
            """INSERT INTO child (data, id, parent) VALUES (?, ?, ?) -- [I am a child 2, 2, 17]
INSERT INTO child (data, id, parent) VALUES (?, ?, ?) -- [I am a child 3, 3, 17]
""", logger()
        )

        assertEquals("[ChildT[id=2], ChildT[id=3], ChildT[id=23]]", parent.myChildren.toString())
        assertEquals("SELECT * FROM child WHERE parent = ? -- [17]\n", logger())

        assertEquals("[ChildT[id=2], ChildT[id=3], ChildT[id=23]]", parent.myChildren.toString())
        assertEquals("[ChildT[id=2], ChildT[id=3], ChildT[id=23]]", parent.myChildren.toString())
        parent.myChildren = emptyList()
        assertEquals("[]", parent.myChildren.toString())
        assertEquals("", logger())
    }
}
