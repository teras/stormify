package test

import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A computed `val` and a delegate marked `@delegate:Transient` are not columns, on both
 * discovery paths: annproc on native, reflection on the JVM.
 */
open class ReadOnlyPropertyTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault()
        test(it)
    }

    @Test
    fun computedAndTransientDelegateAreNotColumns() = withDb("READONLY-PROPS") { s ->
        TestDDL.dropTable("computed_test")
        // Only id and amount exist: mapping doubled or label would break the INSERT and the load.
        s.executeUpdate(TestDDL.createTable("computed_test",
            "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("amount")}, note ${TestDDL.textType()}"))

        s.create(ComputedEntity().apply { id = 1; amount = 21; note = "first" })
        assertEquals("first", s.readOne<String>("SELECT note FROM computed_test WHERE id = ?", 1))
        s.executeUpdate("UPDATE computed_test SET amount = 5, note = 'second' WHERE id = 1")
        val loaded = s.findById<ComputedEntity>(1)!!
        assertEquals(5, loaded.amount)
        assertEquals("second", loaded.note)
        assertEquals(10, loaded.doubled)
        assertEquals("L1", loaded.label)
        s.update(loaded.apply { amount = 6 })
        assertEquals(6, s.readOne<Int>("SELECT amount FROM computed_test WHERE id = ?", 1))
    }
}
