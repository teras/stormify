package test

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import kotlin.test.Test
import kotlin.test.assertEquals

@DbTable(name = "util_date")
class UtilDateEntity {
    @DbField(primaryKey = true)
    var id: Int = 0
    var stamp: java.util.Date? = null
}

/**
 * A `java.util.Date` property keeps the time of day, also when the instance read is
 * changed in place and written back: it must not be a `java.sql.Date`, which a driver
 * writes as a date alone.
 */
class JavaUtilDateReadTest {
    @Test
    fun timeOfDayIsKept() = TestHelper.withDb("UTIL-DATE-READ") { s ->
        TestDDL.dropTable("util_date")
        s.executeUpdate(TestDDL.createTable("util_date", "${TestDDL.intPrimaryKey("id")}, stamp ${TestDDL.timestampType()}"))
        s.create(UtilDateEntity().apply { id = 1; stamp = java.util.Date(1_700_000_060_000L) })
        assertEquals(1_700_000_060_000L, s.findById<UtilDateEntity>(1)!!.stamp!!.time, "create")
        val e = s.findById<UtilDateEntity>(1)!!
        e.stamp!!.time = 1_700_000_120_000L   // changed in place: the instance read is written back
        s.update(e)
        assertEquals(1_700_000_120_000L, s.findById<UtilDateEntity>(1)!!.stamp!!.time, "update")
        s.executeUpdate("INSERT INTO util_date (id, stamp) VALUES (?, ?)", 2, java.sql.Timestamp(1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, s.findById<UtilDateEntity>(2)!!.stamp!!.time, "raw insert")
        TestDDL.dropTable("util_date")
    }
}
