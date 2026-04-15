package test

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.findById
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Exercises entity discovery + CRUD for classes nested inside a container
 * (one level deep) and doubly-nested (two levels deep). On JVM this runs
 * both the reflection and the annproc-generated registration paths; on
 * Native / Android it runs the annproc path only (reflection is absent).
 */
class NestedEntityTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault(test)
    }

    @Test
    fun singleLevelNested() = withDb("NEST-L1") { s ->
        TestDDL.dropTable("nested_level1")
        s.executeUpdate(TestDDL.createTable("nested_level1",
            "${TestDDL.intPrimaryKey("id")}, label ${TestDDL.textType()}"))

        val entity = NestingOuter.Level1(1, "alpha")
        s.create(entity)

        val loaded = s.findById<NestingOuter.Level1>(1)
        assertNotNull(loaded)
        assertEquals(1, loaded.id)
        assertEquals("alpha", loaded.label)
    }

    @Test
    fun doublyNested() = withDb("NEST-L2") { s ->
        TestDDL.dropTable("nested_level2")
        s.executeUpdate(TestDDL.createTable("nested_level2",
            "${TestDDL.intPrimaryKey("id")}, info ${TestDDL.textType()}"))

        val entity = NestingOuter.NestingInner.Level2(1, "beta")
        s.create(entity)

        val loaded = s.findById<NestingOuter.NestingInner.Level2>(1)
        assertNotNull(loaded)
        assertEquals(1, loaded.id)
        assertEquals("beta", loaded.info)
    }

    @Test
    fun triplyNested() = withDb("NEST-L3") { s ->
        TestDDL.dropTable("nested_level3")
        s.executeUpdate(TestDDL.createTable("nested_level3",
            "${TestDDL.intPrimaryKey("id")}, tag ${TestDDL.textType()}"))

        val entity = NestingOuter.NestingInner.NestingInnermost.Level3(1, "gamma")
        s.create(entity)

        val loaded = s.findById<NestingOuter.NestingInner.NestingInnermost.Level3>(1)
        assertNotNull(loaded)
        assertEquals(1, loaded.id)
        assertEquals("gamma", loaded.tag)
    }

    @Test
    fun nestedEntityRoundtrip() = withDb("NEST-CRUD") { s ->
        TestDDL.dropTable("nested_level1")
        s.executeUpdate(TestDDL.createTable("nested_level1",
            "${TestDDL.intPrimaryKey("id")}, label ${TestDDL.textType()}"))

        // Create, update, read, delete — full lifecycle.
        val e = NestingOuter.Level1(10, "created")
        s.create(e)

        e.label = "updated"
        s.update(e)

        val loaded = s.findById<NestingOuter.Level1>(10)
        assertNotNull(loaded)
        assertEquals("updated", loaded.label)

        s.delete(e)
        val gone = s.findById<NestingOuter.Level1>(10)
        assertEquals(null, gone)
    }
}
