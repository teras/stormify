package test

import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A column name with `$` and a Greek letter: the registrar annproc generates must carry
 * it as an escaped string literal (on native it is the metadata source), and the name
 * must work end to end in SQL.
 */
open class EscapedNameTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault()
        test(it)
    }

    @Test
    fun columnNameWithDollarAndGreekLetter() = withDb("ESCAPED-NAME") { s ->
        assertEquals("val\$ο", s.getTableInfo(EscapeEntity::class).getField("value")!!.dbName)
        TestDDL.dropTable("escape_test")
        s.executeUpdate(TestDDL.createTable("escape_test",
            "${TestDDL.intPrimaryKey("id")}, val\$ο ${TestDDL.textType()}"))
        s.create(EscapeEntity().apply { id = 1; value = "kept" })
        assertEquals("kept", s.findById<EscapeEntity>(1)!!.value)
    }
}
