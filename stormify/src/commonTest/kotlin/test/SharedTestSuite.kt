// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.Stormify
import kotlin.test.*

class SharedTestSuite {

    private fun withDb(testName: String, test: (Stormify) -> Unit) {
        val databases = createTestDatabases()
        if (databases.isEmpty()) {
            println("Skipping test: No test databases configured")
            return
        }
        databases.forEach { testDb ->
            println("[$testName] Running on: ${testDb.name}")
            try {
                val s = Stormify(testDb.dataSource)
                s.isStrictMode = false
                s.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
                TestDDL.init(s)
                test(s)
                println("[$testName] PASSED on ${testDb.name}")
            } catch (e: Throwable) {
                println("[$testName] FAILED on ${testDb.name}: ${e.message}")
                throw AssertionError("Test failed on ${testDb.name}: ${e.message}", e)
            }
        }
    }

    @Test
    fun testCrud() = withDb("CRUD") { s ->
        // Setup tables
        TestDDL.dropTable("child")
        TestDDL.dropTable("test")
        TestDDL.dropTable("dual_key")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate(TestDDL.createTable("child",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, " +
                    "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "test", "id")}"))
        s.executeUpdate(TestDDL.createTable("dual_key",
            "${TestDDL.intNotNull("id1")}, ${TestDDL.intNotNull("id2")}, data ${TestDDL.textType()}, PRIMARY KEY (id1, id2)"))

        // Basic CRUD
        val tst = TestC(1, "Test1")
        s.create(tst)
        assertEquals("[TestC(id=1, name=Test1)]", s.findAll<TestC>().toString())

        tst.name = "Test2"
        s.update(tst)
        assertEquals("[TestC(id=1, name=Test2)]", s.findAll<TestC>().toString())

        val tst2 = TestC(2, "Test2")
        s.create(tst2)
        assertEquals("[TestC(id=1, name=Test2), TestC(id=2, name=Test2)]",
            s.findAll<TestC>("ORDER BY id").toString())

        s.delete(tst)
        assertEquals("[TestC(id=2, name=Test2)]", s.findAll<TestC>().toString())

        // Populate
        for (id in 3..5) s.create(TestC(id, "Test$id"))
        assertEquals(
            "[TestC(id=3, name=Test3), TestC(id=4, name=Test4), TestC(id=5, name=Test5)]",
            s.read<TestC>("SELECT * FROM test WHERE id>=? ORDER BY id", 3).toString()
        )

        tst.id = 2; tst.name = null
        val before = tst.toString()
        s.populate(tst)
        assertEquals("TestC(id=2, name=null)", before, "before")
        assertEquals("TestC(id=2, name=Test2)", tst.toString(), "after")

        // Read single
        assertEquals("TestC(id=4, name=Test4)",
            s.readOne<TestC>("SELECT * FROM test WHERE id = ?", 4).toString())

    }

    @Test
    fun testDualKey() = withDb("DUAL-KEY") { s ->
        TestDDL.dropTable("dual_key")
        s.executeUpdate(TestDDL.createTable("dual_key",
            "${TestDDL.intNotNull("id1")}, ${TestDDL.intNotNull("id2")}, data ${TestDDL.textType()}, PRIMARY KEY (id1, id2)"))

        val dk1 = DualKey(1, 2, "Data1")
        s.create(dk1)
        val dk2 = DualKey(3, 42, "Data2")
        s.create(dk2)
        assertEquals("[DualKey(id1=1, id2=2, data=Data1), DualKey(id1=3, id2=42, data=Data2)]",
            s.findAll<DualKey>("ORDER BY id1").toString())
        dk1.data = "Data3"
        s.update(dk1)
        s.delete(dk2)
        assertEquals("[DualKey(id1=1, id2=2, data=Data3)]", s.findAll<DualKey>().toString())
        val dk1P = DualKey(1, 2)
        s.populate(dk1P)
        assertEquals(dk1, dk1P)
    }

    @Test
    fun testOnTheFlyFields() = withDb("ON-THE-FLY") { s ->
        TestDDL.dropTable("fly_test")
        s.executeUpdate(TestDDL.createTable("fly_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate("INSERT INTO fly_test (id, name) VALUES (?, ?)", 1, "Alice")
        s.executeUpdate("INSERT INTO fly_test (id, name) VALUES (?, ?)", 2, "Bob")

        // Alias fills entity field
        val withLabel = s.readOne<FlyView>("SELECT id, name, name as label FROM fly_test WHERE id = ?", 1)!!
        assertEquals(1, withLabel.id)
        assertEquals("Alice", withLabel.name)
        assertEquals("Alice", withLabel.label)

        // Fewer columns — label stays null
        val partial = s.readOne<FlyView>("SELECT id, name FROM fly_test WHERE id = ?", 2)!!
        assertEquals("Bob", partial.name)
        assertNull(partial.label)

        // Only alias, no name
        val onlyLabel = s.readOne<FlyView>("SELECT id, name as label FROM fly_test WHERE id = ?", 1)!!
        assertNull(onlyLabel.name)
        assertEquals("Alice", onlyLabel.label)

        // Extra column not in entity — ignored
        val withBonus = s.readOne<FlyView>("SELECT id, name, name as label, id as bonus FROM fly_test WHERE id = ?", 1)!!
        assertEquals("Alice", withBonus.name)
        assertEquals("Alice", withBonus.label)

        // Mixed case aliases
        val mixedCase = s.readOne<FlyView>("SELECT id as Id, name as NaMe, name as LaBeL FROM fly_test WHERE id = ?", 1)!!
        assertEquals("Alice", mixedCase.name)
        assertEquals("Alice", mixedCase.label)

        // Purely computed
        val computed = s.readOne<FlyView>(TestDDL.selectExpr("42 as id, 'hello' as name, 'world' as label"))!!
        assertEquals(42, computed.id)
        assertEquals("hello", computed.name)
        assertEquals("world", computed.label)
    }

    @Test
    fun testAutoTable() = withDb("AUTO-TABLE") { s ->
        TestDDL.dropTable("auto_child")
        TestDDL.dropTable("auto_parent")
        s.executeUpdate(TestDDL.createTable("auto_parent",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, other ${TestDDL.textType()}"))
        s.executeUpdate(TestDDL.createTable("auto_child",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, " +
                    "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "auto_parent", "id")}"))

        // Create parent first (must be committed before child can lazy-load it)
        val parentEntity = AutoParentEntity().apply { id = 17; data = "I am a parent"; other = "other fields" }
        s.create(parentEntity)

        // Create children (parent must be visible in DB for lazy loading)
        s.create(AutoChildEntity().apply { id = 23; data = "I am a child"; parent = parentEntity })
        s.create(AutoChildEntity().apply { id = 2; data = "child 2"; this.parent = parentEntity })
        s.create(AutoChildEntity().apply { id = 3; data = "child 3"; this.parent = parentEntity })

        // Read child and verify lazy-loaded parent
        val ch = s.findAll<AutoChildEntity>()[0]
        assertEquals("I am a parent", ch.parent?.data)

        // Check children via lazy details (order may vary)
        val childIds = parentEntity.children.mapNotNull { it.id }.sorted()
        assertEquals(listOf(2, 3, 23), childIds)
    }

    @Test
    fun testAutoIncrement() = withDb("AUTO-INCREMENT") { s ->
        if (!TestDDL.supportsAutoIncrement()) {
            println("Skipping: ${s.sqlDialect} does not support auto-increment")
            return@withDb
        }
        val autoPk = TestDDL.autoIncrementPrimaryKey("id") ?: return@withDb
        TestDDL.dropTable("auto_increment")
        s.executeUpdate(TestDDL.createTable("auto_increment", "$autoPk, name ${TestDDL.textType()}"))

        val a1 = AutoIncrementEntity("Test1")
        s.create(a1)
        val a2 = AutoIncrementEntity("Test2")
        s.create(a2)
        assertEquals(1, a1.id)
        assertEquals(2, a2.id)
        assertEquals(2, s.readOne<Int>("SELECT COUNT(*) FROM auto_increment"))
    }

    @Test
    fun testTransactions() = withDb("TRANSACTIONS") { s ->
        TestDDL.dropTable("tx_test")
        s.executeUpdate(TestDDL.createTable("tx_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", 2, "Test2")

        // Rollback
        try {
            s.transaction {
                for (id in 3..5)
                    executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test$id")
                throw Exception("Request Rollback")
            }
        } catch (_: Exception) {}
        assertEquals("[Test2]", s.read<String>("SELECT name FROM tx_test ORDER BY id").toString())

        // Commit
        s.transaction {
            for (id in 3..5) executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test$id")
        }
        assertEquals("[Test2, Test3, Test4, Test5]",
            s.read<String>("SELECT name FROM tx_test ORDER BY id").toString())

        // Cleanup
        s.transaction { for (id in 3..5) executeUpdate("DELETE FROM tx_test WHERE id = ?", id) }

        // Nested with rollback
        s.transaction {
            for (id in 3..4) executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test$id")
            assertEquals("[Test2, Test3, Test4]",
                read<String>("SELECT name FROM tx_test ORDER BY id").toString())

            try {
                transaction {
                    for (id in 5..6) executeUpdate("INSERT INTO tx_test (id, name) VALUES (?, ?)", id, "Test$id")
                    throw Exception("Request Rollback")
                }
            } catch (_: Exception) {}
            assertEquals("[Test2, Test3, Test4]",
                read<String>("SELECT name FROM tx_test ORDER BY id").toString())

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

    @Test
    fun testDoubleDbNames() = withDb("DOUBLE-DB-NAMES") { s ->
        TestDDL.dropTable("double_db_name")
        s.executeUpdate(TestDDL.createTable("double_db_name",
            "${TestDDL.intColumn("id")}, name ${TestDDL.textType()}"))

        val ddn = DoubleDbName(id = 1, name1 = "Name1", name2 = "Name2")
        s.create(ddn)
        val test1 = s.findById<DoubleDbName>(1)!!
        assertEquals("Name1", test1.name1)
        assertEquals("Name1", test1.name2)

        ddn.name2 = "Name2"
        s.update(ddn)
        val test2 = s.findById<DoubleDbName>(1)!!
        assertEquals("Name2", test2.name1)
        assertEquals("Name2", test2.name2)
    }

    @Test
    fun testStress() = withDb("STRESS") { s ->
        if (!TestDDL.supportsHighConcurrency()) {
            println("Skipping: ${s.sqlDialect} does not support high concurrency")
            return@withDb
        }

        TestDDL.dropTable("stress_table")
        s.executeUpdate(TestDDL.createTable("stress_table",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}"))
        val model = StressTable(17, "42")
        s.create(model)

        val selectExpr = TestDDL.selectExpr("( 1 + 2 ) * 3")
        repeat(1000) { assertEquals(9, s.readOne<Int>(selectExpr)) }

        val countSimple = java.util.concurrent.atomic.AtomicInteger(0)
        val countSelect = java.util.concurrent.atomic.AtomicInteger(0)
        val countInserts = java.util.concurrent.atomic.AtomicInteger(0)

        val pool = java.util.concurrent.Executors.newFixedThreadPool(100)
        repeat(1000) {
            pool.submit {
                assertEquals(9, s.readOne<Int>(selectExpr))
                countSimple.incrementAndGet()
            }
        }
        repeat(1000) {
            pool.submit {
                val found = s.readOne<StressTable>("SELECT * FROM stress_table WHERE id = ?", model.id)!!
                assertEquals(model.data, found.data)
                countSelect.incrementAndGet()
            }
        }
        repeat(1000) {
            pool.submit {
                val nextId = countInserts.incrementAndGet()
                s.create(StressTable(nextId, "Index #$nextId"))
            }
        }
        pool.shutdown()
        pool.awaitTermination(100, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(1000, s.readOne<Int>("SELECT COUNT(*) FROM stress_table"))
        assertEquals(1000, countSimple.get(), "Simple queries")
        assertEquals(1000, countSelect.get(), "ORM queries")
        assertEquals(1000, countInserts.get(), "Insert queries")
    }
}
