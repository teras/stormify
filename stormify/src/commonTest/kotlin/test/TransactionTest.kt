package test

import onl.ycode.stormify.Stormify
import kotlin.test.*

class TransactionTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    @Test
    fun testTransactions() = withDb("TRANSACTIONS") { s ->
        TestDDL.dropTable("tx_test")
        s.executeUpdate(TestDDL.createTable("tx_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", 2, "Test2")

        // Rollback
        try {
            s.transaction {
                for (id in 3..5) executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test$id")
                throw Exception("Request Rollback")
            }
        } catch (_: Exception) {}
        assertEquals("[Test2]", s.read<String>("SELECT name FROM tx_test ORDER BY id").toString())

        // Commit
        s.transaction {
            for (id in 3..5) executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test$id")
        }
        assertEquals("[Test2, Test3, Test4, Test5]", s.read<String>("SELECT name FROM tx_test ORDER BY id").toString())

        // Cleanup
        s.transaction { for (id in 3..5) executeUpdate("DELETE FROM tx_test WHERE id = ?", id) }

        // Nested with rollback
        s.transaction {
            for (id in 3..4) executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test$id")
            try {
                transaction {
                    for (id in 5..6) executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test$id")
                    throw Exception("Request Rollback")
                }
            } catch (_: Exception) {}
            assertEquals("[Test2, Test3, Test4]", read<String>("SELECT name FROM tx_test ORDER BY id").toString())

            transaction {
                for (id in 5..6) executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test$id")
                transaction {
                    for (id in 3..6) executeUpdate("DELETE FROM tx_test WHERE id = ?", id)
                    assertEquals("[Test2]", read<String>("SELECT name FROM tx_test ORDER BY id").toString())
                }
            }
        }
        assertEquals("[Test2]", s.read<String>("SELECT name FROM tx_test ORDER BY id").toString())
    }
}
