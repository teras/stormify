// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The JVM transaction registry keeps its per-thread stack in a `ThreadLocal`. A
 * pooled server thread that keeps a value of a library class in its
 * ThreadLocalMap pins the web application's classloader after undeploy, so the
 * thread must hold no entry once it is outside every transaction: neither after
 * a plain query (which only looks up the ambient transaction) nor after a
 * transaction has completed.
 */
class ActiveTxRegistryLeakTest {

    private val stacks: ThreadLocal<*> by lazy {
        val registry = Class.forName("onl.ycode.stormify.ActiveTxRegistry")
        val field = registry.getDeclaredField("stacks")
        field.isAccessible = true
        field.get(null) as ThreadLocal<*>
    }

    /**
     * Whether the current thread's ThreadLocalMap holds an entry for the registry. Read
     * from the map itself: `ThreadLocal.get()` would create the entry it is looking for.
     */
    private fun hasRegistryEntry(): Boolean {
        val map = Thread::class.java.getDeclaredField("threadLocals")
            .apply { isAccessible = true }.get(Thread.currentThread()) ?: return false
        val getEntry = map.javaClass.getDeclaredMethod("getEntry", ThreadLocal::class.java)
            .apply { isAccessible = true }
        return getEntry.invoke(map, stacks) != null
    }

    /** Runs [block] on a fresh thread and returns whether it left a registry entry there. */
    private fun leftOnFreshThread(block: () -> Unit): Boolean {
        var left = true
        var failure: Throwable? = null
        val t = Thread {
            try {
                block()
                left = hasRegistryEntry()
            } catch (e: Throwable) {
                failure = e
            }
        }
        t.start()
        t.join()
        failure?.let { throw it }
        return left
    }

    private fun Stormify.selectOne(): Int? = readOne<Int>(TestDDL.selectExpr("1"))

    // The check itself must see an entry when there is one.
    @Test
    fun entryIsSeenDuringTransaction() = TestHelper.withDb("ActiveTxRegistryLeakTest.inside") { s ->
        var inside = false
        leftOnFreshThread { s.transaction { inside = hasRegistryEntry() } }
        assertTrue(inside, "the registry entry must exist while a transaction runs")
    }

    @Test
    fun plainQueryLeavesNoThreadLocalEntry() = TestHelper.withDb("ActiveTxRegistryLeakTest.plainQuery") { s ->
        val left = leftOnFreshThread { assertEquals(1, s.selectOne()) }
        assertFalse(left, "a query outside any transaction must not create a registry entry")
    }

    @Test
    fun finishedTransactionLeavesNoThreadLocalEntry() = TestHelper.withDb("ActiveTxRegistryLeakTest.transaction") { s ->
        val left = leftOnFreshThread {
            s.transaction {
                assertEquals(1, s.selectOne())
                s.transaction { assertEquals(1, s.selectOne()) }
            }
            assertEquals(1, s.selectOne())
        }
        assertFalse(left, "the registry entry must be removed once the outermost transaction ends")
    }

    @Test
    fun failedTransactionLeavesNoThreadLocalEntry() = TestHelper.withDb("ActiveTxRegistryLeakTest.failed") { s ->
        val left = leftOnFreshThread {
            try {
                s.transaction { error("rollback") }
            } catch (_: Exception) {
            }
        }
        assertFalse(left, "the registry entry must be removed when the transaction rolls back")
    }
}
