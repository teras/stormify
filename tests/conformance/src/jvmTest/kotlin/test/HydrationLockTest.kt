package test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Loading a shadow reference takes a lock of the entity's own, never the entity's
 * monitor: a thread that synchronizes on an entity for its own purposes must not block
 * the load, and the load must not block it.
 */
class HydrationLockTest {
    @Test
    fun loadingDoesNotTakeTheEntityMonitor() = TestHelper.withDb("HYDRATION-NO-MONITOR") { s ->
        TestDDL.dropTable("auto_child")
        TestDDL.dropTable("auto_parent")
        s.executeUpdate(TestDDL.createTable("auto_parent",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, other ${TestDDL.textType()}"))
        s.executeUpdate(TestDDL.createTable("auto_child",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, ${TestDDL.intColumn("parent")}"))
        s.executeUpdate("INSERT INTO auto_parent (id, data, other) VALUES (1, 'P1', 'O1')")
        s.executeUpdate("INSERT INTO auto_child (id, data, parent) VALUES (10, 'C10', 1)")

        val stub = s.findById<AutoChildEntity>(10)!!.parent!!
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            synchronized(stub) {
                held.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        held.await(10, TimeUnit.SECONDS)
        var data: String? = null
        val loader = thread { data = stub.data }
        loader.join(5_000)
        val loadedWhileMonitorHeld = !loader.isAlive
        release.countDown()
        holder.join()
        loader.join()
        assertTrue(loadedWhileMonitorHeld, "the load waited for the entity's monitor")
        assertEquals("P1", data)
    }
}
