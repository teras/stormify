package test

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.db
import onl.ycode.stormify.lazyDetails
import onl.ycode.logger.LogLevel
import onl.ycode.logger.SilentLogger
import onl.ycode.logger.WatchLogger
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * [DecimalKeyBatchTest] with `java.math.BigDecimal` keys, the usual JVM choice,
 * whose `toString()` keeps the scale (`100` versus `100.00`).
 */
class JavaDecimalKeyBatchTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault()
        test(it)
    }

    private fun Stormify.createSchema() {
        TestDDL.dropTable("jdec_child")
        TestDDL.dropTable("jdec_parent")
        executeUpdate(TestDDL.createTable("jdec_parent",
            "id ${TestDDL.decimalType(12, 2)} PRIMARY KEY, name ${TestDDL.textType()}"))
        executeUpdate(TestDDL.createTable("jdec_child",
            "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("parent")}, other ${TestDDL.decimalType(12, 2)}"))
    }

    @Test
    fun batchHydrationMatchesKeysOfDifferentScale() = withDb("JAVA-DECIMAL-PK-BATCH") { s ->
        s.createSchema()
        s.executeUpdate("INSERT INTO jdec_parent (id, name) VALUES (100, 'Alpha')")
        s.executeUpdate("INSERT INTO jdec_parent (id, name) VALUES (200, 'Beta')")
        s.executeUpdate("INSERT INTO jdec_child (id, parent, other) VALUES (1, 100, 100)")
        s.executeUpdate("INSERT INTO jdec_child (id, parent, other) VALUES (2, 200, NULL)")

        val children = s.read<JDecChild>("SELECT * FROM jdec_child ORDER BY id")
        val first = children[0].parent
        val second = children[1].parent
        assertNotNull(first)
        assertNotNull(second)
        assertEquals("Alpha", first.name)
        assertEquals("Beta", second.name)
        assertEquals(0, first.id!!.compareTo(BigDecimal(100)))
        assertSame(first, children[0].other)
    }

    /**
     * The grouped children query keys its parents by id; the id read back through the
     * child's integer column (`100`) must find the parent loaded with its own scale
     * (`100.00`), or every parent silently falls back to a query of its own.
     */
    @Test
    fun lazyDetailsBatchMatchesKeysOfDifferentScale() = withDb("JAVA-DECIMAL-PK-DETAILS") { s ->
        s.createSchema()
        s.executeUpdate("INSERT INTO jdec_parent (id, name) VALUES (100, 'Alpha')")
        s.executeUpdate("INSERT INTO jdec_parent (id, name) VALUES (200, 'Beta')")
        s.executeUpdate("INSERT INTO jdec_child (id, parent, other) VALUES (1, 100, NULL)")
        s.executeUpdate("INSERT INTO jdec_child (id, parent, other) VALUES (2, 100, NULL)")
        s.executeUpdate("INSERT INTO jdec_child (id, parent, other) VALUES (3, 200, NULL)")

        val parents = s.read<JDecParent>("SELECT * FROM jdec_parent ORDER BY id")
        var selects = 0
        val original = s.logger
        s.logger = WatchLogger(SilentLogger()) { level, message, _ ->
            if (level == LogLevel.DEBUG && message.startsWith("SELECT", ignoreCase = true) &&
                message.contains("jdec_child", ignoreCase = true)) selects++
        }.also { it.level = LogLevel.DEBUG }
        try {
            assertEquals(listOf(1, 2), parents[0].children.map { it.id!! }.sorted())
            assertEquals(listOf(3), parents[1].children.map { it.id!! })
        } finally {
            s.logger = original
        }
        assertEquals(1, selects, "one grouped query should serve both parents")
    }
}

@DbTable(name = "jdec_parent")
class JDecParent : AutoTable() {
    @DbField(primaryKey = true) var id: BigDecimal? = null
    var name: String? by db(null)
    var children: List<JDecChild> by lazyDetails<JDecChild>("parent")
}

@DbTable(name = "jdec_child")
class JDecChild : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var parent: JDecParent? by db(null)
    var other: JDecParent? by db(null)
}
