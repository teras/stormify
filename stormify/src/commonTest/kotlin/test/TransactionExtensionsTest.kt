package test

import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

open class TransactionExtensionsTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    @Test
    fun testReceiverStyleInsideTransaction() = withDb("TX-EXT") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        s.transaction {
            TestC(10, "A").create()
            TestC(11, "B").create()
            val count = "SELECT COUNT(*) FROM test".readOne<Int>()
            assertEquals(2, count)
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
    fun testExtensionIsInvisibleFromSecondConnection() = withDb("TX-EXT-ISOLATION") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        var fromOutside: TestC? = null
        s.transaction {
            TestC(30, "pre-commit").create()
            // Same Stormify instance, but this call runs outside the current tx
            // so it goes through the default (auto-commit) path and thus a
            // different connection from the data source. If our extension really
            // ran on the transaction's connection, the row must not be visible
            // here — it has not been committed yet.
            fromOutside = s.findById<TestC>(30)
        }
        assertNull(fromOutside, "row must be invisible to an independent connection while tx is open")
        // After commit it becomes visible.
        assertNotNull(s.findById<TestC>(30))
    }

    @Test
    fun testExtensionsRollbackWithTransaction() = withDb("TX-EXT-ROLLBACK") { s ->
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
        // Since the extension used the tx connection, the insert must have rolled back.
        assertNull(s.findById<TestC>(20))
    }
}
