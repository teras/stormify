package test

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import onl.ycode.stormify.db
import kotlin.test.Test
import kotlin.test.assertEquals

@DbTable(name = "zc") class ZC : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
        get() { hydrate(); return field }
    var data: String? by db(null)
}
@DbTable(name = "zb") class ZB : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
        get() { hydrate(); return field }
    var data: String? by db(null)
    var c: ZC? by db(null)
}
@DbTable(name = "za") class ZA : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var b: ZB? by db(null)
}

class SnapshotCascadeTest {
    private fun loaded(e: AutoTable): Boolean =
        AutoTable::class.java.getDeclaredField("_isHydrated").apply { isAccessible = true }.getBoolean(e)

    private fun run(dynamic: Boolean): Pair<Boolean, Boolean> {
        var r = false to false
        TestHelper.withDb("SNAPSHOT-CASCADE-$dynamic") { s ->
            s.dynamicUpdate = dynamic
            for (t in listOf("za", "zb", "zc")) TestDDL.dropTable(t)
            s.executeUpdate(TestDDL.createTable("zc", "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}"))
            s.executeUpdate(TestDDL.createTable("zb", "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, ${TestDDL.intColumn("c")}"))
            s.executeUpdate(TestDDL.createTable("za", "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("b")}"))
            s.executeUpdate("INSERT INTO zc (id, data) VALUES (1, 'c')")
            s.executeUpdate("INSERT INTO zb (id, data, c) VALUES (1, 'b', 1)")
            s.executeUpdate("INSERT INTO za (id, b) VALUES (1, 1)")
            val a = s.read<ZA>("SELECT * FROM za").single()
            val b = AutoTable::class.java.let { _ -> ZA::class.java.getDeclaredField("b\$delegate").apply { isAccessible = true }.get(a) }
            val bEntity = b.javaClass.getDeclaredField("prop").apply { isAccessible = true }.get(b) as ZB
            r = loaded(bEntity) to false
        }
        return r
    }

    @Test
    fun readingAParentDoesNotLoadItsReferences() {
        assertEquals(false, run(false).first, "without dynamicUpdate")
        assertEquals(false, run(true).first, "with dynamicUpdate the read loaded the referenced row")
    }
}
