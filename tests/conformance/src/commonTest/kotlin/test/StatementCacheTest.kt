package test

import onl.ycode.stormify.Stormify
import kotlin.test.*

/**
 * Verifies the per-connection PreparedStatement cache + reset() path don't change observable
 * semantics. Same SQL repeated many times should produce the same results as fresh prepares,
 * and the cache must survive crossing transaction boundaries (cache is per-Connection, not
 * per-transaction). Re-binding old parameter values after reset must NOT leak — each iteration
 * sees only the values it bound.
 */
open class StatementCacheTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    @Test
    fun repeatedFindByIdSeesFreshParams() = withDb("PSCacheFindById") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        for (id in 1..10) s.create(TestC(id, "row_$id"))

        // Same SQL string repeated — must hit the cache, but each execute has fresh params.
        for (id in 1..10) {
            val r = s.findById<TestC>(id)
            assertEquals("row_$id", r?.name, "iteration $id sees stale params (cache mis-bind)")
        }
        // And again, in reverse — proves reset doesn't keep stale bindings around.
        for (id in 10 downTo 1) {
            val r = s.findById<TestC>(id)
            assertEquals("row_$id", r?.name, "reverse pass row $id wrong")
        }
    }

    @Test
    fun cacheSurvivesAcrossTransactions() = withDb("PSCacheAcrossTx") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        // Two distinct transactions on the same Stormify; cache lives on the
        // underlying Connection, so the second tx benefits from prepares done by the first.
        s.transaction {
            for (id in 1..5) s.create(TestC(id, "first_$id"))
        }
        s.transaction {
            for (id in 6..10) s.create(TestC(id, "second_$id"))
        }
        val all = s.findAll<TestC>("ORDER BY id")
        assertEquals(10, all.size)
        assertEquals("first_3", all[2].name)
        assertEquals("second_8", all[7].name)
    }

    @Test
    fun mixedQueriesShareConnection() = withDb("PSCacheMixed") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        for (id in 1..20) s.create(TestC(id, "n$id"))

        s.transaction {
            // Three distinct cacheable SQLs interleaved — exercises bucket lookup
            // and LRU touch when each one is "released" back to the cache.
            for (id in 1..20) {
                val byId = s.findById<TestC>(id)
                assertNotNull(byId)
                val list = s.read<TestC>("SELECT * FROM test WHERE id <= ?", id)
                assertEquals(id, list.size)
                val one = s.readOne<TestC>("SELECT * FROM test WHERE name = ?", "n$id")
                assertEquals(id, one?.id)
            }
        }
    }
}
