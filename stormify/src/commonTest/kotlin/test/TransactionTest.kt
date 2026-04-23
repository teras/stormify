package test

import onl.ycode.stormify.Stormify
import kotlin.test.*

open class TransactionTest {
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
                for (id in 3..5) s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test$id")
                throw Exception("Request Rollback")
            }
        } catch (_: Exception) {}
        assertEquals("[Test2]", s.read<String>("SELECT name FROM tx_test ORDER BY id").toString())

        // Commit
        s.transaction {
            for (id in 3..5) s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test$id")
        }
        assertEquals("[Test2, Test3, Test4, Test5]", s.read<String>("SELECT name FROM tx_test ORDER BY id").toString())

        // Cleanup
        s.transaction { for (id in 3..5) s.executeUpdate("DELETE FROM tx_test WHERE id = ?", id) }

        // Nested with rollback
        s.transaction {
            for (id in 3..4) s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test$id")
            try {
                s.transaction {
                    for (id in 5..6) s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test$id")
                    throw Exception("Request Rollback")
                }
            } catch (_: Exception) {}
            assertEquals("[Test2, Test3, Test4]", s.read<String>("SELECT name FROM tx_test ORDER BY id").toString())

            s.transaction {
                for (id in 5..6) s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test$id")
                s.transaction {
                    for (id in 3..6) s.executeUpdate("DELETE FROM tx_test WHERE id = ?", id)
                    assertEquals("[Test2]", s.read<String>("SELECT name FROM tx_test ORDER BY id").toString())
                }
            }
        }
        assertEquals("[Test2]", s.read<String>("SELECT name FROM tx_test ORDER BY id").toString())
    }

    @Test
    fun testTransactionReturnsValue() = withDb("TX_RETURN") { s ->
        TestDDL.dropTable("tx_ret")
        s.executeUpdate(TestDDL.createTable("tx_ret",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate("INSERT INTO tx_ret (id, name) VALUES (?, ?)", 1, "Alice")
        s.executeUpdate("INSERT INTO tx_ret (id, name) VALUES (?, ?)", 2, "Bob")

        // Top-level transaction returns a value
        val names: List<String> = s.transaction {
            s.read<String>("SELECT name FROM tx_ret ORDER BY id")
        }
        assertEquals(listOf("Alice", "Bob"), names)

        // Nested transaction returns a value
        val count: Int = s.transaction {
            s.transaction {
                s.executeUpdate("INSERT INTO tx_ret (id, name) VALUES (?, ?)", 3, "Carol")
                s.readOne<Int>("SELECT COUNT(*) FROM tx_ret") ?: -1
            }
        }
        assertEquals(3, count)

        // Rollback still propagates, even with a return type
        try {
            s.transaction<Int> {
                s.executeUpdate("INSERT INTO tx_ret (id, name) VALUES (?, ?)", 99, "Ghost")
                throw RuntimeException("boom")
            }
            fail("expected exception")
        } catch (_: Exception) {}
        assertEquals(3, s.readOne<Int>("SELECT COUNT(*) FROM tx_ret") ?: -1)
    }
}
