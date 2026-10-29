package test

import onl.ycode.stormify.StormifyJ
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Java face of readRows / readRowsCursor. */
class StormifyJReadRowsTest {
    @Test
    fun javaReadRows() = TestHelper.withDb("READ-ROWS-JAVA") { s ->
        val j = StormifyJ(s.dataSource)
        val row = j.readRows("SELECT 1, 2" + if (TestDDL.selectExpr("1").contains("dual")) " FROM dual" else "").single()
        assertEquals(listOf("1", "2"), row.map { it.toString() })
        var count = 0
        j.readRowsCursor("SELECT 3" + if (TestDDL.selectExpr("1").contains("dual")) " FROM dual" else "",
            Consumer { count++ })
        assertEquals(1, count)
    }
}
