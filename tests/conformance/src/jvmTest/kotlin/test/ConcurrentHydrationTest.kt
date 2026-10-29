package test

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.DataSource
import onl.ycode.stormify.Stormify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A data source whose next connection waits until [release] once [arm] is called. */
private class GatedDataSource(private val real: DataSource) : DataSource {
    @Volatile private var armed = false
    val entered = CountDownLatch(1)
    private val gate = CountDownLatch(1)
    fun arm() { armed = true }
    fun release() = gate.countDown()
    override fun getConnection(): Connection {
        if (armed) {
            armed = false
            entered.countDown()
            gate.await(30, TimeUnit.SECONDS)
        }
        return real.getConnection()
    }
}

/**
 * While one thread loads a shadow reference (or its sibling batch), another thread that
 * reads the same or a sibling entity must wait for the load, never see it as loaded with
 * empty fields.
 */
class ConcurrentHydrationTest {
    private fun scenario(name: String, readFromOtherThread: (List<AutoParentEntity>) -> AutoParentEntity) =
        TestHelper.withDb(name) { base ->
            TestDDL.dropTable("auto_child")
            TestDDL.dropTable("auto_parent")
            base.executeUpdate(TestDDL.createTable("auto_parent",
                "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, other ${TestDDL.textType()}"))
            base.executeUpdate(TestDDL.createTable("auto_child",
                "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, ${TestDDL.intColumn("parent")}"))
            for (p in 1..2) {
                base.create(AutoParentEntity().apply { id = p; data = "parent $p" })
                base.executeUpdate("INSERT INTO auto_child (id, data, parent) VALUES ($p, 'c$p', $p)")
            }
            val gated = GatedDataSource(base.dataSource)
            val s = Stormify(gated)
            s.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
            val parents = s.read<AutoChildEntity>("SELECT * FROM auto_child ORDER BY id").map { it.parent!! }

            gated.arm()
            var first: String? = null
            val loader = thread { first = parents[0].data }
            assertTrue(gated.entered.await(30, TimeUnit.SECONDS), "loader never reached the query")

            var seen: String? = "unset"
            val reader = thread { seen = readFromOtherThread(parents).data }
            reader.join(300)
            assertTrue(reader.isAlive, "the other thread read before the load finished: $seen")

            gated.release()
            loader.join(30_000)
            reader.join(30_000)
            assertEquals("parent 1", first)
            assertEquals(readFromOtherThread(parents).let { if (it === parents[0]) "parent 1" else "parent 2" }, seen)
        }

    @Test
    fun sameEntityWaitsForTheLoad() = scenario("CONCURRENT-HYDRATE-SAME") { it[0] }

    @Test
    fun siblingWaitsForTheBatch() = scenario("CONCURRENT-HYDRATE-SIBLING") { it[1] }
}
