package test

import db.stormify.Paths.TestC_
import db.stormify.Paths.CamelEntity_
import db.stormify.Paths.AutoChildEntity_
import db.stormify.Paths.TreeNode_
import db.stormify.Paths.Person_
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.Facet
import onl.ycode.stormify.biglist.PagedList
import kotlin.test.*

class PagedListPathTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault()
        test(it)
    }

    @Test
    fun testTypeSafeScalarPath() = withDb("PATH-SCALAR") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alice"), TestC(2, "Bob"), TestC(3, "Alicia")))

        val list = PagedList<TestC>()
        val col = list.addFacet(TestC_.name)
        col.filter = "Ali"

        assertEquals(2, list.size)
    }

    @Test
    fun testTypeSafeMultipleScalarPaths() = withDb("PATH-MULTI") { s ->
        TestDDL.dropTable("camel_entity")
        s.executeUpdate(TestDDL.createTable("camel_entity",
            "${TestDDL.intPrimaryKey("id")}, first_name ${TestDDL.textType()}, last_name ${TestDDL.textType()}"))
        s.create(listOf(
            CamelEntity(1, "Alice", "Smith"),
            CamelEntity(2, "Bob", "Alison"),
            CamelEntity(3, "Charlie", "Brown")
        ))

        val list = PagedList<CamelEntity>()
        val col = list.addFacet(CamelEntity_.firstName, CamelEntity_.lastName)
        col.filter = "Ali"

        assertEquals(2, list.size)
    }

    @Test
    fun testTypeSafeFkPath() = withDb("PATH-FK") { s ->
        TestDDL.dropTable("auto_child")
        TestDDL.dropTable("auto_parent")
        s.executeUpdate(TestDDL.createTable("auto_parent",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, other ${TestDDL.textType()}"))
        s.executeUpdate(TestDDL.createTable("auto_child",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, " +
                    "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "auto_parent", "id")}"))
        s.executeUpdate("INSERT INTO auto_parent (id, data, other) VALUES (?, ?, ?)", 1, "Parent1", "x")
        s.executeUpdate("INSERT INTO auto_parent (id, data, other) VALUES (?, ?, ?)", 2, "Parent2", "y")
        s.executeUpdate("INSERT INTO auto_child (id, data, parent) VALUES (?, ?, ?)", 1, "Child1", 1)
        s.executeUpdate("INSERT INTO auto_child (id, data, parent) VALUES (?, ?, ?)", 2, "Child2", 1)
        s.executeUpdate("INSERT INTO auto_child (id, data, parent) VALUES (?, ?, ?)", 3, "Child3", 2)

        val list = PagedList<AutoChildEntity>()
        // Type-safe FK path: AutoChildEntity_.parent.data → "parent.data"
        val col = list.addFacet(AutoChildEntity_.parent.data)
        col.filter = "Parent1"

        assertEquals(2, list.size)
    }

    @Test
    fun testTypeSafeSorting() = withDb("PATH-SORT") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Charlie"), TestC(2, "Alice"), TestC(3, "Bob")))

        val list = PagedList<TestC>()
        val col = list.addFacet(TestC_.name)
        col.sort = Facet.ASCENDING

        assertEquals("Alice", list[0].name)
        assertEquals("Bob", list[1].name)
        assertEquals("Charlie", list[2].name)
    }

    @Test
    fun testPathEquivalence() {
        // Verify that type-safe paths produce the same string as dot notation
        assertEquals("name", TestC_.name.toPath())
        assertEquals("firstName", CamelEntity_.firstName.toPath())
        assertEquals("parent.data", AutoChildEntity_.parent.data.toPath())
    }

    @Test
    fun testSelfReferentialPath() {
        // TreeNode.parent is self-referential — lazy refs allow infinite depth
        assertEquals("parent.name", TreeNode_.parent.name.toPath())
        assertEquals("parent.parent.name", TreeNode_.parent.parent.name.toPath())
        assertEquals("parent.parent.parent.name", TreeNode_.parent.parent.parent.name.toPath())
    }

    @Test
    fun testDeepFkChain() {
        // Person → City → Country — linear chain, no limit
        assertEquals("city.name", Person_.city.name.toPath())
        assertEquals("city.country.name", Person_.city.country.name.toPath())
    }

    @Test
    fun testSelfReferentialPathWithDb() = withDb("PATH-SELF-REF-DB") { s ->
        TestDDL.dropTable("tree_node")
        s.executeUpdate(TestDDL.createTable("tree_node",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, ${TestDDL.intColumn("parent")}, " +
                    "${TestDDL.foreignKey("parent", "tree_node", "id")}"))
        // Build a chain: root(1) ← child(2) ← grandchild(3)
        s.executeUpdate("INSERT INTO tree_node (id, name, parent) VALUES (?, ?, ?)", 1, "root", null)
        s.executeUpdate("INSERT INTO tree_node (id, name, parent) VALUES (?, ?, ?)", 2, "child", 1)
        s.executeUpdate("INSERT INTO tree_node (id, name, parent) VALUES (?, ?, ?)", 3, "grandchild", 2)

        val list = PagedList<TreeNode>()
        // Filter: grandchild → parent(child) → parent(root).name = "root"
        val col = list.addFacet(TreeNode_.parent.parent.name)
        col.filter = "root"

        assertEquals(1, list.size)
        assertEquals("grandchild", list[0].name)
    }
}
