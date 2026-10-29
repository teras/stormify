package test

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.DataSource
import onl.ycode.kdbc.SQLException
import onl.ycode.logger.LogLevel
import onl.ycode.logger.SilentLogger
import onl.ycode.logger.WatchLogger
import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** Hands out the real connections, except that the next one can be made to fail. */
private class FlakyDataSource(private val real: DataSource) : DataSource {
    var failNext = false
    override fun getConnection(): Connection {
        if (failNext) {
            failNext = false
            throw SQLException("injected connection failure")
        }
        return real.getConnection()
    }
}

/**
 * A lazy load that fails must leave the entity unloaded, so the next access loads it,
 * rather than marking it loaded before the query and leaving it empty for good.
 */
open class HydrationFailureTest {
    private fun withFlaky(name: String, test: (Stormify, FlakyDataSource) -> Unit) =
        TestHelper.withDb(name) { s ->
            val flaky = FlakyDataSource(s.dataSource)
            val s2 = Stormify(flaky)
            s2.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
            test(s2, flaky)
        }

    private fun schema(s: Stormify, parents: Int) {
        TestDDL.dropTable("auto_child")
        TestDDL.dropTable("auto_parent")
        s.executeUpdate(TestDDL.createTable("auto_parent",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, other ${TestDDL.textType()}"))
        s.executeUpdate(TestDDL.createTable("auto_child",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, " +
                    "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "auto_parent", "id")}"))
        for (p in 1..parents) {
            val parent = AutoParentEntity().apply { id = p; data = "parent $p"; other = "o$p" }
            s.create(parent)
            s.create(AutoChildEntity().apply { id = p; data = "child $p"; this.parent = parent })
        }
    }

    @Test
    fun failedSingleReferenceLoadIsRetried() = withFlaky("HYDRATE-FAIL-SINGLE") { s, flaky ->
        schema(s, parents = 1)
        val child = s.read<AutoChildEntity>("SELECT * FROM auto_child").single()
        val parent = child.parent!!
        flaky.failNext = true
        assertFails { parent.data }
        assertEquals("parent 1", parent.data)
        assertEquals("o1", parent.other)
    }

    @Test
    fun failedBatchReferenceLoadIsRetried() = withFlaky("HYDRATE-FAIL-BATCH") { s, flaky ->
        schema(s, parents = 3)
        val children = s.read<AutoChildEntity>("SELECT * FROM auto_child ORDER BY id")
        val parents = children.map { it.parent!! }
        flaky.failNext = true
        assertFails { parents[0].data }
        // Neither the trigger nor its siblings were left marked as loaded.
        assertEquals(listOf("parent 2", "parent 3", "parent 1"),
            listOf(parents[1].data, parents[2].data, parents[0].data))
    }

    /** After a failed grouped query its parents are batched again, not queried one by one. */
    @Test
    fun failedLazyDetailsBatchIsBatchedAgain() = withFlaky("HYDRATE-FAIL-DETAILS-REQUEUE") { s, flaky ->
        schema(s, parents = 3)
        val parents = s.read<AutoParentEntity>("SELECT * FROM auto_parent ORDER BY id")
        flaky.failNext = true
        assertFails { parents[0].children }
        var queries = 0
        val original = s.logger
        s.logger = WatchLogger(SilentLogger()) { level, message, _ ->
            if (level == LogLevel.DEBUG && message.startsWith("SELECT", ignoreCase = true) &&
                message.contains("auto_child", ignoreCase = true)) queries++
        }.also { it.level = LogLevel.DEBUG }
        try {
            assertEquals(listOf(listOf("child 1"), listOf("child 2"), listOf("child 3")),
                parents.map { p -> p.children.map { it.data } })
        } finally {
            s.logger = original
        }
        assertEquals(1, queries, "the retried parents were not batched again")
    }

    @Test
    fun failedLazyDetailsLoadIsRetried() = withFlaky("HYDRATE-FAIL-DETAILS") { s, flaky ->
        schema(s, parents = 3)
        val parents = s.read<AutoParentEntity>("SELECT * FROM auto_parent ORDER BY id")
        flaky.failNext = true
        assertFails { parents[0].children }
        assertEquals(listOf(listOf("child 1"), listOf("child 2"), listOf("child 3")),
            parents.map { p -> p.children.map { it.data } })
    }
}
