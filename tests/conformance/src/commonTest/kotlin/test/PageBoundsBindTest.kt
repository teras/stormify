package test

import onl.ycode.stormify.pagedList
import onl.ycode.stormify.pagedQuery
import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.DataSource
import onl.ycode.kdbc.Statement
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.PageSpec
import onl.ycode.stormify.biglist.PagedList
import onl.ycode.stormify.biglist.PagedQuery
import kotlin.test.Test
import kotlin.test.assertEquals

/** Records the text of every statement prepared through it. */
internal class RecordingDataSource(private val real: DataSource) : DataSource {
    val statements = mutableListOf<String>()
    override fun getConnection(): Connection = RecordingConnection(real.getConnection(), statements)
}

internal class RecordingConnection(private val real: Connection, private val log: MutableList<String>) :
    Connection by real {
    override fun initStatement(sql: String, returnGeneratedKeys: Boolean, columnNames: Array<String>?): Statement {
        log += sql
        return real.initStatement(sql, returnGeneratedKeys, columnNames)
    }

    override fun acquirePreparedStatement(sql: String): Statement {
        log += sql
        return real.acquirePreparedStatement(sql)
    }
}

/**
 * Every page of a paged read must run the same SQL text, with the bounds bound as
 * parameters: a text per page costs a hard parse on Oracle and a statement cache miss
 * everywhere.
 */
open class PageBoundsBindTest {
    private fun withRecording(name: String, test: (Stormify, RecordingDataSource) -> Unit) =
        TestHelper.withDb(name) { s ->
            TestDDL.dropTable("test")
            s.executeUpdate(TestDDL.createTable("test", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
            s.create((1..25).map { TestC(it, "N${(it - 1) % 13}") })
            val recording = RecordingDataSource(s.dataSource)
            val s2 = Stormify(recording)
            s2.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
            s2.asDefault(); test(s2, recording)
        }

    /** The statements recorded while [action] ran. */
    private fun RecordingDataSource.during(action: () -> Unit): List<String> {
        val from = statements.size
        action()
        return statements.subList(from, statements.size).toList()
    }

    @Test
    fun pagedListPagesShareOneStatement() = withRecording("PAGE-BIND-LIST") { _, rec ->
        val list = pagedList<TestC>().facet("name")
        list.pageSize = 10
        list[0] // first page also learns the size, through a statement of its own
        val second = rec.during { list[10] }
        val third = rec.during { list[20] }
        assertEquals(1, second.size, "$second")
        assertEquals(second, third)
        assertEquals(21, list[20].id)
    }

    @Test
    fun pagedQueryPagesShareOneStatement() = withRecording("PAGE-BIND-QUERY") { _, rec ->
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
        val first = rec.during { assertEquals(10, query.execute(PageSpec(0, 10)).rows.size) }
        val second = rec.during { assertEquals(10, query.execute(PageSpec(1, 10)).rows.size) }
        assertEquals(first, second)
    }

    @Test
    fun filterValuePagesShareOneStatement() = withRecording("PAGE-BIND-VALUES") { _, rec ->
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
        var firstRows = emptyList<String>()
        val first = rec.during { firstRows = query.filterValues("name", PageSpec(0, 5)).rows }
        val second = rec.during { query.filterValues("name", PageSpec(1, 5)) }
        assertEquals(5, firstRows.size)
        // The count query and the page query, the page query the same text on both pages
        assertEquals(first, second)
        val counted = rec.during { query.filterValuesWithCounts("name", PageSpec(0, 5)) }
        val countedNext = rec.during { query.filterValuesWithCounts("name", PageSpec(1, 5)) }
        assertEquals(counted, countedNext)
    }
}
