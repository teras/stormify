package test

import onl.ycode.stormify.Stormify
import kotlin.test.*

class CrudTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    @Test
    fun testBasicCrud() = withDb("CRUD") { s ->
        TestDDL.dropTable("child")
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate(TestDDL.createTable("child",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, " +
                    "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "test", "id")}"))

        val tst = TestC(1, "Test1")
        s.create(tst)
        assertEquals("[TestC(id=1, name=Test1)]", s.findAll<TestC>().toString())

        tst.name = "Test2"
        s.update(tst)
        assertEquals("[TestC(id=1, name=Test2)]", s.findAll<TestC>().toString())

        s.create(TestC(2, "Test2"))
        assertEquals("[TestC(id=1, name=Test2), TestC(id=2, name=Test2)]",
            s.findAll<TestC>("ORDER BY id").toString())

        s.delete(tst)
        assertEquals("[TestC(id=2, name=Test2)]", s.findAll<TestC>().toString())

        // Populate
        for (id in 3..5) s.create(TestC(id, "Test$id"))
        tst.id = 2; tst.name = null
        val before = tst.toString()
        s.populate(tst)
        assertEquals("TestC(id=2, name=null)", before)
        assertEquals("TestC(id=2, name=Test2)", tst.toString())
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
    fun testAutoIncrement() = withDb("AUTO-INCREMENT") { s ->
        if (!TestDDL.supportsAutoIncrement())
            skipTest(SkipReason.DB_LIMITATION, "auto-increment not supported on this dialect")
        val autoPk = TestDDL.autoIncrementPrimaryKey("id")
            ?: skipTest(SkipReason.DB_LIMITATION, "no auto-increment PK syntax for this dialect")
        TestDDL.dropTable("auto_increment")
        s.executeUpdate(TestDDL.createTable("auto_increment", "$autoPk, name ${TestDDL.textType()}"))

        val a1 = AutoIncrementEntity("Test1")
        s.create(a1)
        val a2 = AutoIncrementEntity("Test2")
        s.create(a2)
        assertEquals(1, a1.id)
        assertEquals(2, a2.id)
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

        val parentEntity = AutoParentEntity().apply { id = 17; data = "I am a parent"; other = "other fields" }
        s.create(parentEntity)
        s.create(AutoChildEntity().apply { id = 23; data = "I am a child"; parent = parentEntity })

        val ch = s.findAll<AutoChildEntity>()[0]
        assertEquals("I am a child", ch.data)
        assertEquals("I am a parent", ch.parent?.data)

        s.create(AutoChildEntity().apply { id = 2; data = "child 2"; this.parent = parentEntity })
        s.create(AutoChildEntity().apply { id = 3; data = "child 3"; this.parent = parentEntity })

        val childIds = parentEntity.children.mapNotNull { it.id }.sorted()
        assertEquals(listOf(2, 3, 23), childIds)
    }

    @Test
    fun testBatchCreate() = withDb("BATCH") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        val items = (1..100).map { TestC(it, "Item$it") }
        s.create(items)
        assertEquals(100, s.readOne<Int>("SELECT COUNT(*) FROM test"))
        assertEquals("Item50", s.findById<TestC>(50)?.name)
    }

    @Test
    fun testDeleteNonExistent() = withDb("DELETE-GHOST") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.delete(TestC(999, "ghost"))
    }

    @Test
    fun testDuplicateKeyInsert() = withDb("DUP-KEY") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(TestC(1, "first"))
        assertFailsWith<onl.ycode.kdbc.SQLException> { s.create(TestC(1, "dup")) }
    }
}
