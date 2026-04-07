package test

import onl.ycode.stormify.Stormify
import kotlin.test.*

/**
 * Verifies autocommit behaviour across all native drivers.
 *
 * These tests exercise the interplay between autocommit (direct operations)
 * and manual transaction mode (transaction {} blocks), including the state
 * transitions after commit, rollback, and nested savepoints.
 */
class AutocommitTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    @Test
    fun directOperationAutoCommits() = withDb("AC_DIRECT") { s ->
        TestDDL.dropTable("ac_test")
        s.executeUpdate(TestDDL.createTable("ac_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        // Direct INSERT — should be visible immediately without explicit commit
        s.executeUpdate("INSERT INTO ac_test (id, name) VALUES (?, ?)", 1, "auto")
        val result = s.readOne<String>("SELECT name FROM ac_test WHERE id = ?", 1)
        assertEquals("auto", result, "Direct INSERT should auto-commit")

        TestDDL.dropTable("ac_test")
    }

    @Test
    fun directOperationAfterCommittedTransaction() = withDb("AC_AFTER_COMMIT") { s ->
        TestDDL.dropTable("ac_test")
        s.executeUpdate(TestDDL.createTable("ac_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        // Successful transaction
        s.transaction {
            executeUpdate("INSERT INTO ac_test (id, name) VALUES (?, ?)", 1, "txn")
        }

        // Direct INSERT after transaction — autocommit must be restored
        s.executeUpdate("INSERT INTO ac_test (id, name) VALUES (?, ?)", 2, "after-commit")
        val names = s.read<String>("SELECT name FROM ac_test ORDER BY id")
        assertEquals(listOf("txn", "after-commit"), names)

        TestDDL.dropTable("ac_test")
    }

    @Test
    fun directOperationAfterRolledBackTransaction() = withDb("AC_AFTER_ROLLBACK") { s ->
        TestDDL.dropTable("ac_test")
        s.executeUpdate(TestDDL.createTable("ac_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        // Failed transaction — rollback
        try {
            s.transaction {
                executeUpdate("INSERT INTO ac_test (id, name) VALUES (?, ?)", 1, "should-vanish")
                throw RuntimeException("force rollback")
            }
        } catch (_: Exception) {}

        // Direct INSERT after rollback — autocommit must be restored
        s.executeUpdate("INSERT INTO ac_test (id, name) VALUES (?, ?)", 2, "after-rollback")
        val names = s.read<String>("SELECT name FROM ac_test ORDER BY id")
        assertEquals(listOf("after-rollback"), names, "Row 1 should have been rolled back")

        TestDDL.dropTable("ac_test")
    }

    @Test
    fun transactionAfterTransaction() = withDb("AC_TXN_AFTER_TXN") { s ->
        TestDDL.dropTable("ac_test")
        s.executeUpdate(TestDDL.createTable("ac_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        // First transaction — commit
        s.transaction {
            executeUpdate("INSERT INTO ac_test (id, name) VALUES (?, ?)", 1, "first")
        }

        // Second transaction — commit
        s.transaction {
            executeUpdate("INSERT INTO ac_test (id, name) VALUES (?, ?)", 2, "second")
        }

        val names = s.read<String>("SELECT name FROM ac_test ORDER BY id")
        assertEquals(listOf("first", "second"), names)

        TestDDL.dropTable("ac_test")
    }

    @Test
    fun transactionAfterFailedTransaction() = withDb("AC_TXN_AFTER_FAIL") { s ->
        TestDDL.dropTable("ac_test")
        s.executeUpdate(TestDDL.createTable("ac_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        // First transaction — rollback
        try {
            s.transaction {
                executeUpdate("INSERT INTO ac_test (id, name) VALUES (?, ?)", 1, "doomed")
                throw RuntimeException("boom")
            }
        } catch (_: Exception) {}

        // Second transaction — must succeed
        s.transaction {
            executeUpdate("INSERT INTO ac_test (id, name) VALUES (?, ?)", 2, "recovered")
        }

        val names = s.read<String>("SELECT name FROM ac_test ORDER BY id")
        assertEquals(listOf("recovered"), names)

        TestDDL.dropTable("ac_test")
    }

    @Test
    fun nestedRollbackDoesNotBreakAutocommit() = withDb("AC_NESTED_ROLLBACK") { s ->
        TestDDL.dropTable("ac_test")
        s.executeUpdate(TestDDL.createTable("ac_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        // Outer transaction with inner savepoint failure
        s.transaction {
            executeUpdate("INSERT INTO ac_test (id, name) VALUES (?, ?)", 1, "outer")
            try {
                transaction {
                    executeUpdate("INSERT INTO ac_test (id, name) VALUES (?, ?)", 2, "inner-fail")
                    throw RuntimeException("inner boom")
                }
            } catch (_: Exception) {}
            executeUpdate("INSERT INTO ac_test (id, name) VALUES (?, ?)", 3, "after-inner")
        }

        // Direct operation after nested rollback — autocommit must work
        s.executeUpdate("INSERT INTO ac_test (id, name) VALUES (?, ?)", 4, "direct-after")
        val names = s.read<String>("SELECT name FROM ac_test ORDER BY id")
        assertEquals(listOf("outer", "after-inner", "direct-after"), names)

        TestDDL.dropTable("ac_test")
    }
}
