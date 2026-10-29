package test

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DanglingReferences
import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import onl.ycode.stormify.db
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@DbTable(name = "zd_c") class ZdC : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var data: String? by db(null)
}
@DbTable(name = "zd_b") class ZdB : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var data: String? by db(null)
    var c: ZdC? by db(null)
}
@DbTable(name = "zd_a") class ZdA : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var data: String? by db(null)
    var b: ZdB? by db(null)
}

class DanglingReferencesTest {
    private fun setup(s: onl.ycode.stormify.Stormify) {
        s.danglingReferences = DanglingReferences.NULL
        for (t in listOf("zd_a", "zd_b", "zd_c")) TestDDL.dropTable(t)
        s.executeUpdate(TestDDL.createTable("zd_c", "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}"))
        s.executeUpdate(TestDDL.createTable("zd_b", "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, ${TestDDL.intColumn("c")}"))
        s.executeUpdate(TestDDL.createTable("zd_a", "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, ${TestDDL.intColumn("b")}"))
        s.executeUpdate("INSERT INTO zd_b (id, data, c) VALUES (1, 'b', 99)")
        s.executeUpdate("INSERT INTO zd_a (id, data, b) VALUES (1, 'a', 1)")
        s.executeUpdate("INSERT INTO zd_a (id, data, b) VALUES (2, 'a2', 98)")
    }

    @Test
    fun lazilyLoadedReferenceWithDanglingFkReadsNull() = TestHelper.withDb("DANGLING-LAZY") { s ->
        setup(s)
        val a = s.read<ZdA>("SELECT * FROM zd_a WHERE id = 1").single()
        val b = a.b!!
        assertEquals("b", b.data)
        assertNull(b.c, "the dangling c of a lazily loaded b should read null")
    }

    @Test
    fun refreshWithDanglingFkReadsNull() = TestHelper.withDb("DANGLING-REFRESH") { s ->
        setup(s)
        val b = ZdB().apply { id = 1 }
        s.refresh(b)
        assertNull(b.c, "refresh: dangling c should read null")
    }

    @Test
    fun updateKeepsTheDanglingKeyDynamic() = TestHelper.withDb("DANGLING-UPD-DYN") { s ->
        setup(s); s.dynamicUpdate = true
        val a = s.read<ZdA>("SELECT * FROM zd_a WHERE id = 2").single()
        assertNull(a.b)
        a.data = "changed"
        s.update(a)
        assertEquals(98, s.readOne<Int>("SELECT COALESCE(b, -1) FROM zd_a WHERE id = 2"), "dynamicUpdate wrote the dangling FK as NULL")
    }

    @Test
    fun aReferenceSetLaterReplacesTheDanglingKey() = TestHelper.withDb("DANGLING-REPLACED") { s ->
        setup(s)
        val a = s.read<ZdA>("SELECT * FROM zd_a WHERE id = 2").single()
        assertNull(a.b)
        a.b = s.findById<ZdB>(1)
        s.update(a)
        assertEquals(1, s.readOne<Int>("SELECT b FROM zd_a WHERE id = 2"))
    }

    @Test
    fun updateKeepsTheDanglingKeyFull() = TestHelper.withDb("DANGLING-UPD-FULL") { s ->
        setup(s)
        val a = s.read<ZdA>("SELECT * FROM zd_a WHERE id = 2").single()
        a.data = "changed"
        s.update(a)
        assertEquals(98, s.readOne<Int>("SELECT COALESCE(b, -1) FROM zd_a WHERE id = 2"), "full update wrote the dangling FK as NULL")
    }

    @Test
    fun streamingReadHandsRowsOverWithDanglingReadAsNull() = TestHelper.withDb("DANGLING-CURSOR") { s ->
        setup(s)
        var seen = 0
        s.readCursor<ZdA>("SELECT * FROM zd_a WHERE id = 2") { a ->
            seen++
            assertNull(a.b, "the consumer of readCursor should see the dangling b as null")
        }
        assertEquals(1, seen)
        assertNull(s.readOne<ZdA>("SELECT * FROM zd_a WHERE id = 2")!!.b, "readOne: dangling b should read null")
    }

    @Test
    fun aReferenceSetByTheStreamingConsumerStays() = TestHelper.withDb("DANGLING-CURSOR-SET") { s ->
        setup(s)
        val b1 = s.findById<ZdB>(1)!!
        val rows = mutableListOf<ZdA>()
        s.readCursor<ZdA>("SELECT * FROM zd_a ORDER BY id") { a ->
            if (a.b == null) a.b = b1
            rows += a
        }
        assertEquals(2, rows.size)
        assertSame(b1, rows[1].b, "the reference the consumer set must not be undone")
        s.update(rows[1])
        assertEquals(1, s.readOne<Int>("SELECT b FROM zd_a WHERE id = 2"))
    }

    @Test
    fun settingNullClearsTheDanglingKey() = TestHelper.withDb("DANGLING-CLEAR") { s ->
        setup(s)
        val a = s.read<ZdA>("SELECT * FROM zd_a WHERE id = 2").single()
        assertNull(a.b)
        a.b = null
        s.update(a)
        assertEquals(-1, s.readOne<Int>("SELECT COALESCE(b, -1) FROM zd_a WHERE id = 2"),
            "a reference set to null should clear the dangling FK column")
    }
}
