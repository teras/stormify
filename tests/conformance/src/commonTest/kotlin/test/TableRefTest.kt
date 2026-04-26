package test

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.Facet
import onl.ycode.stormify.biglist.PageSpec
import onl.ycode.stormify.biglist.PagedList
import onl.ycode.stormify.biglist.PagedQuery
import onl.ycode.stormify.biglist.execute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TableRefTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault(test)
    }

    private fun setupParentChild(s: Stormify) {
        TestDDL.dropTable("child")
        TestDDL.dropTable("test")
        s.executeUpdate(
            TestDDL.createTable("test",
                "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}")
        )
        s.executeUpdate(
            TestDDL.createTable("child",
                "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, " +
                        "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "test", "id")}")
        )
        val p1 = TestC(1, "ParentA")
        val p2 = TestC(2, "ParentB")
        s.create(listOf(p1, p2))
        s.create(listOf(
            Child(1, "ChildA1", p1),
            Child(2, "ChildA2", p1),
            Child(3, "ChildB1", p2),
        ))
    }

    @Test
    fun rootRefGivesEntityTableName() = withDb("TREF-ROOT") { s ->
        setupParentChild(s)
        val query = PagedQuery<TestC>()
        val root = query.addTableRef()
        assertEquals("test", root.alias)
        assertEquals("test", root.toString())  // string interpolation uses toString
    }

    @Test
    fun fkRefGetsEngineAlias() = withDb("TREF-FK") { s ->
        setupParentChild(s)
        val query = PagedQuery<Child>()
        val parent = query.addTableRef("parent")
        // Alias is engine-assigned (e.g. "t1"), not the table name.
        assertNotEquals("test", parent.alias)
        assertTrue(parent.alias.startsWith("t"))
    }

    @Test
    fun duplicatePathReturnsSameAlias() = withDb("TREF-DUPE") { s ->
        setupParentChild(s)
        val query = PagedQuery<Child>()
        val a = query.addTableRef("parent")
        val b = query.addTableRef("parent")
        // Two separate TableRef objects but pointing to the same underlying node.
        assertEquals(a.alias, b.alias)
    }

    @Test
    fun rootRefInSqlFacet() = withDb("TREF-ROOT-SQL") { s ->
        setupParentChild(s)
        val query = PagedQuery<TestC>().apply {
            val root = addTableRef()
            addFacet("name", "name")
            addSqlFacet("childCount",
                "(SELECT COUNT(*) FROM child WHERE child.parent = $root.id)",
                Facet.NUMERIC)
        }
        // Filter by child count threshold
        val page = query.execute(PageSpec(filters = mapOf("childCount" to ">= 2")))
        assertEquals(1L, page.total)
        assertEquals("ParentA", page.rows.first().name)
    }

    @Test
    fun fkRefInConstraints() = withDb("TREF-FK-CONSTR") { s ->
        setupParentChild(s)
        val query = PagedQuery<Child>().apply {
            val parent = addTableRef("parent")
            addFacet("childName", "name")
            setConstraints("$parent.name = ?", "ParentA")
        }
        val page = query.execute(PageSpec())
        assertEquals(2L, page.total)
    }

    @Test
    fun pathToScalarIsRejected() = withDb("TREF-SCALAR") { s ->
        setupParentChild(s)
        val query = PagedQuery<Child>()
        // "name" is a scalar field, not a reference — must throw at registration.
        assertFails { query.addTableRef("name") }
    }

    @Test
    fun inactiveRefDropsItsJoin() = withDb("TREF-INACTIVE") { s ->
        setupParentChild(s)
        val query = PagedQuery<Child>().apply {
            val parent = addTableRef("parent")
            parent.isActive = false    // suppressed from every subsequent build
            addFacet("childName", "name")
        }
        // Query runs fine without the JOIN being emitted.
        val page = query.execute(PageSpec())
        assertEquals(3L, page.total)
    }

    @Test
    fun tableRefInteropWithPagedList() = withDb("TREF-PAGEDLIST") { s ->
        setupParentChild(s)
        val list = PagedList<Child>()
        val parent = list.addTableRef("parent")
        list.addFacet("name")
        list.setConstraints("$parent.name = ?", "ParentB")
        assertEquals(1, list.size)
        assertEquals("ChildB1", list[0].name)
    }
}
