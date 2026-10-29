@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package test

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.DataSource
import onl.ycode.stormify.Stormify
import platform.posix.usleep
import kotlin.concurrent.atomics.AtomicInt
import kotlin.native.concurrent.FutureState
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Native counterpart of the JVM GatedDataSource: the next connection waits for [release]. */
private class NativeGatedDataSource(private val real: DataSource) : DataSource {
    val armed = AtomicInt(0)
    val entered = AtomicInt(0)
    val open = AtomicInt(0)
    override fun getConnection(): Connection {
        if (armed.compareAndSet(1, 0)) {
            entered.store(1)
            var waited = 0
            while (open.load() == 0 && waited < 30_000) { usleep(1_000u); waited++ }
        }
        return real.getConnection()
    }
}

private fun waitUntil(timeoutMs: Int, condition: () -> Boolean): Boolean {
    var waited = 0
    while (!condition() && waited < timeoutMs) { usleep(1_000u); waited++ }
    return condition()
}

/**
 * The three load states on native: while one worker loads a shadow reference (or its
 * sibling batch), another worker reading the same or a sibling entity waits for the load
 * instead of seeing it loaded with empty fields.
 */
class NativeConcurrentHydrationTest {
    private fun scenario(name: String, otherIndex: Int) = TestHelper.withDb(name) { base ->
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
        val gated = NativeGatedDataSource(base.dataSource)
        val s = Stormify(gated)
        s.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
        val parents = s.read<AutoChildEntity>("SELECT * FROM auto_child ORDER BY id").map { it.parent!! }

        val loaderWorker = Worker.start()
        val readerWorker = Worker.start()
        try {
            gated.armed.store(1)
            val loader = loaderWorker.execute(TransferMode.SAFE, { parents }) { it[0].data }
            assertTrue(waitUntil(30_000) { gated.entered.load() == 1 }, "loader never reached the query")

            val reader = readerWorker.execute(TransferMode.SAFE, { parents to otherIndex }) { (p, i) -> p[i].data }
            usleep(300_000u)
            assertTrue(reader.state != FutureState.COMPUTED, "the other worker read before the load finished")

            gated.open.store(1)
            assertEquals("parent 1", loader.result)
            assertEquals("parent ${otherIndex + 1}", reader.result)
        } finally {
            gated.open.store(1)
            loaderWorker.requestTermination().result
            readerWorker.requestTermination().result
        }
    }

    @Test
    fun sameEntityWaitsForTheLoad() = scenario("NATIVE-CONCURRENT-HYDRATE-SAME", 0)

    @Test
    fun siblingWaitsForTheBatch() = scenario("NATIVE-CONCURRENT-HYDRATE-SIBLING", 1)
}
