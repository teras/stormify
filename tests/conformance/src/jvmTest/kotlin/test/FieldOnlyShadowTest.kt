package test

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.db
import kotlin.test.Test
import kotlin.test.assertEquals

@DbTable(name = "zro_parent")
class ZroParent : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var code: String? = null
        get() { hydrate(); return field }
        private set
    var name: String? = null
        get() { hydrate(); return field }
        set(v) { hydrate(); field = v }
}

@DbTable(name = "zro_child")
class ZroChild : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var parent: ZroParent? by db(null)
}

class FieldOnlyShadowTest {
    @Test
    fun updateOfUnloadedShadowKeepsFieldOnlyColumn() = TestHelper.withDb("FIELD-ONLY-SHADOW") { s ->
        s.asDefault()
        TestDDL.dropTable("zro_child"); TestDDL.dropTable("zro_parent")
        s.executeUpdate(TestDDL.createTable("zro_parent", "${TestDDL.intPrimaryKey("id")}, code ${TestDDL.textType()}, name ${TestDDL.textType()}"))
        s.executeUpdate(TestDDL.createTable("zro_child", "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("parent")}"))
        s.executeUpdate("INSERT INTO zro_parent (id, code, name) VALUES (1, 'C1', 'N1')")
        s.executeUpdate("INSERT INTO zro_child (id, parent) VALUES (1, 1)")
        val p = s.read<ZroChild>("SELECT * FROM zro_child").single().parent!!
        s.update(p)
        assertEquals("C1", s.readOne<String>("SELECT code FROM zro_parent WHERE id = 1"))
    }
}
