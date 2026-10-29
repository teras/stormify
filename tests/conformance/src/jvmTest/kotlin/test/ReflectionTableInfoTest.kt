package test

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import onl.ycode.stormify.db
import kotlin.test.Test
import kotlin.test.assertEquals

@DbTable(name = "two_ctor")
class TwoCtorEntity(@DbField(primaryKey = true) var code: Int?, var name: String?) {
    constructor(code: Int?) : this(code, null)
}

@DbTable(name = "refl_row")
class ReflRow : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var name: String? by db(null)
    var other: ReflRow? by db(null)
}

/** Metadata built by reflection, for entities the annotation processor never sees. */
class ReflectionTableInfoTest {
    @Test
    fun bookkeepingIsNotAColumn() = TestHelper.withDb("REFL-BOOKKEEPING") { s ->
        val columns = s.getTableInfo(ReflRow::class).fieldInfos.map { it.dbName }.sorted()
        assertEquals(listOf("id", "name", "other"), columns)
    }

    @Test
    fun primaryConstructorAnnotationsWinOverASecondaryConstructor() = TestHelper.withDb("REFL-TWO-CTOR") { s ->
        val info = s.getTableInfo(TwoCtorEntity::class)
        assertEquals(listOf("code"), info.primaryKeys.map { it.dbName },
            "the @DbField of the primary constructor parameter must be found")
    }
}
