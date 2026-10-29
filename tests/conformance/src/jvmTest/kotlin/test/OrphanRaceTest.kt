package test

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.Stormify
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class OrphanRaceTest {
    private fun groupLock(e: AutoTable): ReentrantLock {
        val f = AutoTable::class.java.getDeclaredField("_siblingGroup").apply { isAccessible = true }
        val g = f.get(e)!!
        return g.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(g) as ReentrantLock
    }
    private fun waitQueued(lock: ReentrantLock, t: Thread) { while (!lock.hasQueuedThread(t)) Thread.sleep(5) }

    @Test
    fun twoOrphanSiblingsReadConcurrently() = TestHelper.withDb("ORPHAN-RACE") { base ->
        TestDDL.dropTable("auto_child")
        TestDDL.dropTable("auto_parent")
        base.executeUpdate(TestDDL.createTable("auto_parent",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, other ${TestDDL.textType()}"))
        base.executeUpdate(TestDDL.createTable("auto_child",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, ${TestDDL.intColumn("parent")}"))
        for (p in 1..2) base.executeUpdate("INSERT INTO auto_child (id, data, parent) VALUES ($p, 'c$p', ${90 + p})")
        val s = Stormify(base.dataSource)
        s.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
        val parents = s.read<AutoChildEntity>("SELECT * FROM auto_child ORDER BY id").map { it.parent!! }
        val results = arrayOfNulls<String>(2)
        val lock = groupLock(parents[0])
        val threads = ArrayList<Thread>()
        lock.lock()
        try {
            for (i in 1 downTo 0) {
                val t = thread { results[i] = try { "value=" + parents[i].data } catch (e: Exception) { "threw" } }
                threads.add(t); waitQueued(lock, t)
            }
        } finally {
            lock.unlock()
        }
        threads.forEach { it.join(30_000) }
        // Both rows are missing: each access must report it.
        assertEquals(listOf("threw", "threw"), results.toList())
        // and a later access must report it as well
        assertEquals("threw", try { "value=" + parents[0].data } catch (e: Exception) { "threw" })
        assertEquals("threw", try { "value=" + parents[1].data } catch (e: Exception) { "threw" })
    }
}
