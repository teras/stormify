package test

import onl.ycode.logger.LogLevel
import onl.ycode.logger.WatchLogger
import kotlinx.coroutines.runBlocking
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.coroutines.PoolConfig
import onl.ycode.stormify.coroutines.SuspendStormify
import kotlin.test.*

open class DynamicUpdateTest {
    // dyn_entity references test: dropped afterwards, so later tests can drop and recreate test
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        try {
            it.asDefault(); test(it)
        } finally {
            TestDDL.dropTable("dyn_entity")
        }
    }

    private fun setup(s: Stormify) {
        TestDDL.dropTable("dyn_entity")
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate(
            TestDDL.createTable(
                "dyn_entity",
                "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, ${TestDDL.intColumn("amount")}, " +
                        "payload ${TestDDL.blobType()}, ${TestDDL.intColumn("parent")}, " +
                        TestDDL.foreignKey("parent", "test", "id")
            )
        )
        s.create(listOf(TestC(1, "P1"), TestC(2, "P2")))
        for (i in 1..3)
            s.executeUpdate(
                "INSERT INTO dyn_entity (id, name, amount, payload, parent) VALUES (?, ?, ?, ?, ?)",
                i, "N$i", i * 10, byteArrayOf(i.toByte(), 0), 1
            )
    }

    /** Runs [block] and returns the UPDATE statements logged meanwhile. */
    private fun Stormify.captureUpdates(block: () -> Unit): List<String> {
        val captured = mutableListOf<String>()
        val previous = logger
        logger = WatchLogger(previous) { _, message, _ ->
            if (message.trimStart().startsWith("UPDATE", ignoreCase = true)) captured += message
        }.also { it.level = LogLevel.DEBUG }
        try {
            block()
        } finally {
            logger = previous
        }
        return captured
    }

    private fun Stormify.load(id: Int) = findById<DynEntity>(id)!!

    @Test
    fun testOffByDefaultWritesEveryColumn() = withDb("DYN-OFF") { s ->
        setup(s)
        assertFalse(s.dynamicUpdate)
        val e = s.load(1)
        e.name = "changed"
        val sql = s.captureUpdates { s.update(e) }
        assertEquals(1, sql.size)
        assertTrue(sql[0].contains("amount = ?") && sql[0].contains("payload = ?"), sql[0])
    }

    @Test
    fun testOneChangedColumn() = withDb("DYN-ONE") { s ->
        setup(s)
        s.dynamicUpdate = true
        val e = s.load(1)
        e.amount = 99
        val sql = s.captureUpdates { s.update(e) }
        assertEquals(1, sql.size)
        assertTrue(sql[0].startsWith("UPDATE dyn_entity SET amount = ? WHERE id = ?"), sql[0])
        assertEquals(99, s.load(1).amount)
        assertEquals("N1", s.load(1).name)
    }

    @Test
    fun testNoChangeNoStatement() = withDb("DYN-NONE") { s ->
        setup(s)
        s.dynamicUpdate = true
        val e = s.load(2)
        e.name = "N2"   // same value as loaded
        assertEquals(emptyList(), s.captureUpdates { s.update(e) })
    }

    @Test
    fun testOtherColumnsAreNotOverwritten() = withDb("DYN-CONCURRENT") { s ->
        setup(s)
        s.dynamicUpdate = true
        val e = s.load(1)
        // Someone else changes the name after the load
        s.executeUpdate("UPDATE dyn_entity SET name = ? WHERE id = ?", "other", 1)
        e.amount = 5
        s.update(e)
        val reloaded = s.load(1)
        assertEquals("other", reloaded.name)
        assertEquals(5, reloaded.amount)
    }

    @Test
    fun testInPlaceBlobChangeAndReference() = withDb("DYN-INPLACE") { s ->
        setup(s)
        s.dynamicUpdate = true
        val e = s.load(1)
        e.payload!![1] = 7
        val sql = s.captureUpdates { s.update(e) }
        assertEquals(1, sql.size)
        assertTrue(sql[0].startsWith("UPDATE dyn_entity SET payload = ? WHERE id = ?"), sql[0])
        assertContentEquals(byteArrayOf(1, 7), s.load(1).payload)

        e.parent = TestC(2, "P2")
        val sql2 = s.captureUpdates { s.update(e) }
        assertTrue(sql2.single().startsWith("UPDATE dyn_entity SET parent = ? WHERE id = ?"), sql2.toString())
        assertEquals(2, s.load(1).parent?.id)
    }

    @Test
    fun testUpdatedValuesBecomeTheReference() = withDb("DYN-REFERENCE") { s ->
        setup(s)
        s.dynamicUpdate = true
        val e = s.load(1)
        e.amount = 1
        s.update(e)
        e.amount = 10  // back to the loaded value, but no longer the stored one
        val sql = s.captureUpdates { s.update(e) }
        assertEquals(1, sql.size)
        assertEquals(10, s.load(1).amount)
    }

    @Test
    fun testBatchGroupsByChangedColumns() = withDb("DYN-BATCH") { s ->
        setup(s)
        s.dynamicUpdate = true
        val e1 = s.load(1).apply { name = "a" }
        val e2 = s.load(2).apply { amount = 0 }
        val e3 = s.load(3).apply { name = "c" }
        // never loaded: full update; an empty payload, since SQL Server's JDBC driver refuses a NULL into VARBINARY
        val fresh = DynEntity().apply { id = 4; payload = ByteArray(0) }
        s.create(fresh)
        fresh.name = "d"
        val sql = s.captureUpdates { s.update(listOf(e1, e2, e3, fresh)) }
        assertEquals(4, sql.size, sql.toString())
        assertEquals(2, sql.count { it.startsWith("UPDATE dyn_entity SET name = ? WHERE") })
        assertEquals(1, sql.count { it.startsWith("UPDATE dyn_entity SET amount = ? WHERE") })
        assertEquals(1, sql.count { it.contains("payload = ?") })
        assertEquals(listOf("a", "N2", "c", "d"), (1..4).map { s.load(it).name })
        assertEquals(0, s.load(2).amount)
    }

    @Test
    fun testNonStormifyEntityGetsFullUpdate() = withDb("DYN-PLAIN") { s ->
        setup(s)
        s.dynamicUpdate = true
        val t = s.findById<TestC>(1)!!
        t.name = "x"
        val sql = s.captureUpdates { s.update(t) }
        assertTrue(sql.single().startsWith("UPDATE test SET name = ? WHERE id = ?"), sql.toString())
    }

    @Test
    fun testAutoTableWithoutHydratingTheReference() = withDb("DYN-AUTOTABLE") { s ->
        TestDDL.dropTable("auto_child")
        TestDDL.dropTable("auto_parent")
        s.executeUpdate(TestDDL.createTable("auto_parent",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, other ${TestDDL.textType()}"))
        s.executeUpdate(TestDDL.createTable("auto_child",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, " +
                    "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "auto_parent", "id")}"))
        s.executeUpdate("INSERT INTO auto_parent (id, data, other) VALUES (?, ?, ?)", 1, "P", "x")
        s.executeUpdate("INSERT INTO auto_child (id, data, parent) VALUES (?, ?, ?)", 1, "C", 1)
        s.dynamicUpdate = true

        val statements = mutableListOf<String>()
        val previous = s.logger
        s.logger = WatchLogger(previous) { _, message, _ -> statements += message }.also { it.level = LogLevel.DEBUG }
        try {
            val child = s.findById<AutoChildEntity>(1)!!
            child.data = "C2"
            s.update(child)
        } finally {
            s.logger = previous
        }
        val sql = statements.filter { it.startsWith("SELECT") || it.startsWith("UPDATE") }
        assertEquals(2, sql.size, sql.toString())   // the load and the update, no parent load
        assertTrue(sql[1].startsWith("UPDATE auto_child SET data = ? WHERE id = ?"), sql[1])
    }

    @Test
    fun testRollbackDropsTheSnapshot() = withDb("DYN-ROLLBACK") { s ->
        setup(s)
        s.dynamicUpdate = true
        val e = s.load(1)
        assertFails {
            s.transaction {
                e.amount = 1
                s.update(e)
                s.create(TestC(1, "duplicate key"))   // fails: the transaction rolls back
            }
        }
        assertEquals(10, s.load(1).amount)
        val sql = s.captureUpdates { s.update(e) }   // the rolled back value must be written again
        assertTrue(sql.single().contains("payload = ?"), sql.toString())
        assertEquals(1, s.load(1).amount)
    }

    @Test
    fun testSwitchedOnInsideTheTransactionStillRollsBack() = withDb("DYN-TOGGLE") { s ->
        setup(s)
        var e: DynEntity? = null
        assertFails {
            s.transaction {
                s.dynamicUpdate = true
                val x = s.load(1)
                e = x
                x.amount = 1
                s.update(x)
                s.create(TestC(1, "duplicate key"))   // fails: the transaction rolls back
            }
        }
        assertEquals(10, s.load(1).amount)
        s.update(e!!)   // its snapshot must be gone, so the rolled back value is written again
        assertEquals(1, s.load(1).amount)
    }

    @Test
    fun testCommitKeepsTheSnapshot() = withDb("DYN-COMMIT") { s ->
        setup(s)
        s.dynamicUpdate = true
        val e = s.load(1)
        s.transaction {
            e.amount = 1
            s.update(e)
        }
        assertEquals(emptyList(), s.captureUpdates { s.update(e) })
        e.name = "x"
        assertTrue(s.captureUpdates { s.update(e) }.single().startsWith("UPDATE dyn_entity SET name = ? WHERE"))
    }

    @Test
    fun testSavepointRollbackDropsOnlyItsSnapshots() = withDb("DYN-SAVEPOINT") { s ->
        setup(s)
        s.dynamicUpdate = true
        val outer = s.load(1)
        val inner = s.load(2)
        s.transaction {
            outer.amount = 1
            s.update(outer)
            assertFails {
                s.transaction {
                    inner.amount = 2
                    s.update(inner)
                    throw IllegalStateException("inner fails")
                }
            }
        }
        assertEquals(1, s.load(1).amount)
        assertEquals(20, s.load(2).amount)
        assertEquals(emptyList(), s.captureUpdates { s.update(outer) })
        assertTrue(s.captureUpdates { s.update(inner) }.single().contains("payload = ?"))
        assertEquals(2, s.load(2).amount)
    }

    @Test
    fun testSuspendRollbackDropsTheSnapshot() = withDb("DYN-SUSPEND-ROLLBACK") { s ->
        setup(s)
        s.dynamicUpdate = true
        val e = s.load(1)
        val runner = SuspendStormify(s, PoolConfig(minConnections = 0, maxConnections = 1))
        try {
            runBlocking {
                assertFails {
                    runner.transaction {
                        e.amount = 1
                        s.update(e)
                        throw IllegalStateException("rollback")
                    }
                }
            }
        } finally {
            runBlocking { runner.close() }
        }
        assertEquals(10, s.load(1).amount)
        assertTrue(s.captureUpdates { s.update(e) }.single().contains("payload = ?"))
        assertEquals(1, s.load(1).amount)
    }

    @Test
    fun testChangedPrimaryKeyGetsFullUpdate() = withDb("DYN-PK") { s ->
        setup(s)
        s.dynamicUpdate = true
        val e = s.load(1)
        e.id = 3
        val sql = s.captureUpdates { s.update(e) }
        assertTrue(sql.single().contains("payload = ?"), sql.toString())
        assertEquals("N1", s.load(3).name)
    }
}
