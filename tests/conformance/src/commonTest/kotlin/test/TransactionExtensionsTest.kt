package test

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.create
import onl.ycode.stormify.delete
import onl.ycode.stormify.findById
import onl.ycode.stormify.read
import onl.ycode.stormify.readOne
import onl.ycode.stormify.executeUpdate
import onl.ycode.stormify.transaction
import onl.ycode.stormify.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Covers the ambient-tx behaviour: convenience APIs (top-level extensions,
 * default-instance calls, CRUDTable…) must transparently join an enclosing
 * `transaction { }` block and share its connection.
 */
open class TransactionExtensionsTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) { s ->
        // Top-level extensions (create, findById, readOne, transaction, …) go
        // through `Stormify.defaultInstance`; register this instance for the
        // duration of the test and restore afterwards.
        s.asDefault { test(s) }
    }

    @Test
    fun topLevelExtensionsInsideTransactionShareConnection() = withDb("TX-EXT") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        s.transaction {
            TestC(10, "A").create()
            TestC(11, "B").create()
            assertEquals(2, "SELECT COUNT(*) FROM test".readOne<Int>())
            val rows = "SELECT * FROM test ORDER BY id".read<TestC>()
            assertEquals(2, rows.size)
            val first = rows[0]
            first.name = "A2"
            first.update()
            assertEquals("A2", "SELECT name FROM test WHERE id=10".readOne<String>())
            first.delete()
            assertEquals(1, "SELECT COUNT(*) FROM test".readOne<Int>())
        }
    }

    @Test
    fun defaultInstanceCallJoinsActiveTransaction() = withDb("TX-EXT-AMBIENT") { s ->
        // Proves that a call going through the default instance (no receiver,
        // no stormify prefix) from inside an open transaction transparently
        // reuses the tx's connection. We insert, then read back via the
        // top-level `findById`, and assert we SEE the uncommitted row —
        // only possible if the read borrowed the tx connection. The rollback
        // check confirms the whole flow is one atomic tx.
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        var sawInsideTx: TestC? = null
        assertFailsWith<RuntimeException> {
            s.transaction {
                TestC(30, "pre-commit").create()
                sawInsideTx = findById<TestC>(30)
                throw RuntimeException("abort")
            }
        }
        assertNotNull(sawInsideTx, "default-instance call must join the active transaction and see its writes")
        assertEquals("pre-commit", sawInsideTx?.name)
        assertNull(findById<TestC>(30))
    }

    @Test
    fun defaultInstanceCallJoinsNestedSavepoint() = withDb("TX-EXT-NESTED") { s ->
        // Outer tx + nested savepoint. Default-instance reads inside both
        // layers must see the in-flight tx state. Rolling back the inner
        // savepoint undoes inner work but preserves outer; outer commit keeps
        // only outer. Proves the ambient tracks the whole tx stack, not just
        // the outermost frame.
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        s.transaction {
            TestC(100, "outer").create()
            assertNotNull(findById<TestC>(100))
            try {
                transaction {
                    TestC(101, "inner").create()
                    assertNotNull(findById<TestC>(101))
                    throw RuntimeException("inner-abort")
                }
            } catch (_: RuntimeException) {}
            assertNull(findById<TestC>(101))
            assertNotNull(findById<TestC>(100))
        }
        assertNotNull(findById<TestC>(100))
        assertNull(findById<TestC>(101))
    }

    @Test
    fun topLevelExtensionsRollbackWithTransaction() = withDb("TX-EXT-ROLLBACK") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        assertFailsWith<RuntimeException> {
            s.transaction {
                TestC(20, "X").create()
                assertNotNull("SELECT * FROM test WHERE id=20".readOne<TestC>())
                throw RuntimeException("boom")
            }
        }
        assertNull(findById<TestC>(20))
    }
}
