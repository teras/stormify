// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.StormifyJ
import onl.ycode.stormify.StormifyLifecycle
import onl.ycode.stormify.coroutines.PoolAcquireTimeoutException
import onl.ycode.stormify.coroutines.PoolClosedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Java API does what the Kotlin one does, written as Java code writes it ([JavaApiProbe]). */
class JavaApiTest {
    private fun withOrders(name: String, test: (StormifyJ, RecordingDataSource) -> Unit) = TestHelper.withDb(name) { s ->
        val d = TestDDL
        TestDDL.dropTable("java_item")
        TestDDL.dropTable("java_order")
        s.executeUpdate(d.createTable("java_order", d.intPrimaryKey("id")))
        s.executeUpdate(d.createTable("java_item", "${d.intPrimaryKey("id")}, ${d.intColumn("parent")}, " +
                d.foreignKey("parent", "java_order", "id")))
        for (id in 1..3) s.executeUpdate("INSERT INTO java_order (id) VALUES (?)", id)
        for ((id, parent) in listOf(1 to 1, 2 to 1, 3 to 3)) s.executeUpdate("INSERT INTO java_item (id, parent) VALUES (?, ?)", id, parent)
        try {
            val recording = RecordingDataSource(s.dataSource)
            val stormify = Stormify(recording)
            stormify.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
            test(StormifyJ(stormify), recording)
        } finally {
            TestDDL.dropTable("java_item")
            TestDDL.dropTable("java_order")
        }
    }

    @Test
    fun javaDetailsLoadTheChildrenOfTheWholeResultAtOnce() = withOrders("JAVA-DETAILS") { j, recording ->
        val from = recording.statements.size
        assertEquals(listOf(2, 0, 1), JavaApiProbe.itemCounts(j))
        val childQueries = recording.statements.drop(from).count { it.contains("java_item", ignoreCase = true) }
        assertEquals(1, childQueries, "one query for the children of the three orders")
    }

    @Test
    fun javaStreamsWithPlainLambdas() = withOrders("JAVA-STREAMING") { j, _ ->
        assertEquals(listOf(1, 2, 3), JavaApiProbe.streamedIds(j))
        assertEquals(listOf(1, 2, 3), JavaApiProbe.cursorIds(j))
    }

    @Test
    fun javaGetsTypedTableInfoAndPartialFilterSyntax() = withOrders("JAVA-TYPED") { j, _ ->
        assertEquals("java_order", JavaApiProbe.tableName(j).lowercase())
        val syntax = JavaApiProbe.orOnly()
        assertEquals("|", syntax.or)
        assertEquals("-", syntax.not)
    }

    @Test
    fun javaAndKotlinShareTheirInstances() {
        val previous = Stormify.defaultInstance
        try {
            TestHelper.withDb("JAVA-SHARED") { s -> assertTrue(JavaApiProbe.sharesTheInstance(s)) }
            StormifyLifecycle.clear()
            assertFailsWith<SQLException> { StormifyJ.getDefault() }
        } finally {
            previous?.asDefault()
        }
    }

    @Test
    fun poolFailuresAreSqlExceptions() {
        assertTrue(SQLException::class.java.isAssignableFrom(PoolClosedException::class.java))
        assertTrue(SQLException::class.java.isAssignableFrom(PoolAcquireTimeoutException::class.java))
    }
}
