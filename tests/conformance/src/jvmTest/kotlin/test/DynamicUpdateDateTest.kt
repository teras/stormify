package test

import onl.ycode.logger.LogLevel
import onl.ycode.logger.WatchLogger
import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.StormifyEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DbTable(name = "dyn_date")
class DynDateEntity : StormifyEntity() {
    @DbField(primaryKey = true)
    var id: Int = 0
    var name: String? = null
    var stamp: java.util.Date? = null
}

/** A `java.util.Date` changed in place is a changed column under dynamic update. */
class DynamicUpdateDateTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault()
        test(it)
    }

    @Test
    fun testDateChangedInPlaceIsDetected() = withDb("DYN-DATE") { s ->
        TestDDL.dropTable("dyn_date")
        s.executeUpdate(
            TestDDL.createTable(
                "dyn_date",
                "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, stamp ${TestDDL.timestampType()}"
            )
        )
        s.executeUpdate(
            "INSERT INTO dyn_date (id, name, stamp) VALUES (?, ?, ?)",
            1, "a", java.sql.Timestamp(1_700_000_000_000L)
        )
        s.dynamicUpdate = true
        val e = s.findById<DynDateEntity>(1)!!
        e.stamp!!.time = 1_700_000_060_000L

        val captured = mutableListOf<String>()
        val previous = s.logger
        s.logger = WatchLogger(previous) { _, message, _ ->
            if (message.trimStart().startsWith("UPDATE", ignoreCase = true)) captured += message
        }.also { it.level = LogLevel.DEBUG }
        try {
            s.update(e)
        } finally {
            s.logger = previous
        }
        assertEquals(1, captured.size)
        assertTrue(captured[0].startsWith("UPDATE dyn_date SET stamp = ? WHERE id = ?"), captured[0])
        assertEquals(1_700_000_060_000L, s.findById<DynDateEntity>(1)!!.stamp!!.time)
    }
}
