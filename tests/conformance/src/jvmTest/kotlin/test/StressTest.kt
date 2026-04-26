package test

import onl.ycode.stormify.Stormify
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class StressTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    @Test
    fun testStress() = withDb("STRESS") { s ->
        if (!TestDDL.supportsHighConcurrency()) { println("Skipping"); return@withDb }

        TestDDL.dropTable("stress_table")
        s.executeUpdate(TestDDL.createTable("stress_table",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}"))
        val model = StressTable(17, "42")
        s.create(model)

        val selectExpr = TestDDL.selectExpr("( 1 + 2 ) * 3")
        repeat(1000) { assertEquals(9, s.readOne<Int>(selectExpr)) }

        val countSimple = AtomicInteger(0)
        val countSelect = AtomicInteger(0)
        val countInserts = AtomicInteger(0)

        val pool = Executors.newFixedThreadPool(100)
        repeat(1000) { pool.submit { assertEquals(9, s.readOne<Int>(selectExpr)); countSimple.incrementAndGet() } }
        repeat(1000) { pool.submit {
            val found = s.readOne<StressTable>("SELECT * FROM stress_table WHERE id = ?", model.id)!!
            assertEquals(model.data, found.data); countSelect.incrementAndGet()
        } }
        repeat(1000) { pool.submit {
            val nextId = countInserts.incrementAndGet()
            s.create(StressTable(nextId, "Index #$nextId"))
        } }

        pool.shutdown()
        pool.awaitTermination(100, TimeUnit.SECONDS)

        assertEquals(1000, s.readOne<Int>("SELECT COUNT(*) FROM stress_table"))
        assertEquals(1000, countSimple.get(), "Simple queries")
        assertEquals(1000, countSelect.get(), "ORM queries")
        assertEquals(1000, countInserts.get(), "Insert queries")
    }
}
