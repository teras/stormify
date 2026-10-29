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

    /** The block's own exception reaches the caller as it is, from a transaction and a savepoint. */
    @Test
    fun theBlockExceptionPropagatesUnchanged() = withDb("TX-EXCEPTION-UNCHANGED") { s ->
        TestDDL.dropTable("tx_exc")
        s.executeUpdate(TestDDL.createTable("tx_exc", "${TestDDL.intPrimaryKey("id")}"))
        try {
            val outer = IllegalStateException("outer")
            assertSame(outer, assertFailsWith<IllegalStateException> {
                s.transaction {
                    s.executeUpdate("INSERT INTO tx_exc (id) VALUES (?)", 1)
                    throw outer
                }
            })
            val inner = IllegalArgumentException("inner")
            s.transaction {
                s.executeUpdate("INSERT INTO tx_exc (id) VALUES (?)", 2)
                assertSame(inner, assertFailsWith<IllegalArgumentException> {
                    s.transaction {
                        s.executeUpdate("INSERT INTO tx_exc (id) VALUES (?)", 3)
                        throw inner
                    }
                })
            }
            assertEquals(listOf(2), s.read<Int>("SELECT id FROM tx_exc ORDER BY id"))
            // A database error stays an SQLException
            assertFailsWith<onl.ycode.kdbc.SQLException> {
                s.transaction { s.executeUpdate("INSERT INTO tx_exc_missing (id) VALUES (1)") }
            }
        } finally {
            TestDDL.dropTable("tx_exc")
        }
    }
}
