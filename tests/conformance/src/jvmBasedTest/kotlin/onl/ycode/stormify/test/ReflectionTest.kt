package onl.ycode.stormify.test

import onl.ycode.stormify.*
import test.TestDDL
import test.TestHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for JVM reflection-based entity discovery, covering:
 * - Kotlin classes with delegated properties (by db())
 * - Entity references with AutoTable and lazy loading
 * - Update preserving FK references via sqlData()
 * - Inheritance with and without AutoTable
 */
open class ReflectionTest {

    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    private fun Stormify.createRefSchema() {
        TestDDL.dropTable("ref_child")
        TestDDL.dropTable("ref_parent")
        executeUpdate(TestDDL.createTable("ref_parent",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        executeUpdate(TestDDL.createTable("ref_child",
            "${TestDDL.intPrimaryKey("id")}, title ${TestDDL.textType()}, " +
                    "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "ref_parent", "id")}"))
    }

    private fun Stormify.seedRefData() {
        val p1 = RefParent().apply { id = 1; name = "Alice" }
        val p2 = RefParent().apply { id = 2; name = "Bob" }
        create(p1); create(p2)
        create(RefChild().apply { id = 10; title = "Task1"; this.parent = p1 })
        create(RefChild().apply { id = 20; title = "Task2"; this.parent = p1 })
        create(RefChild().apply { id = 30; title = "Task3"; this.parent = p2 })
    }

    @Test
    fun autoTableDelegatesAndRefs() = withDb("REF-DELEGATES") { s ->
        s.createRefSchema()
        s.seedRefData()

        val child = s.findById<RefChild>(10)
        assertNotNull(child)
        assertEquals("Task1", child.title)
        assertNotNull(child.parent)
        assertEquals("Alice", child.parent?.name)
    }

    @Test
    fun updatePreservesReferences() = withDb("REF-UPDATE") { s ->
        s.createRefSchema()
        s.seedRefData()

        val child = s.findById<RefChild>(10)!!
        child.title = "updated"
        s.update(child)

        val updated = s.findById<RefChild>(10)!!
        assertEquals("updated", updated.title)
        assertEquals("Alice", updated.parent?.name)
    }

    @Test
    fun findAllWithReferences() = withDb("REF-FINDALL") { s ->
        s.createRefSchema()
        s.seedRefData()

        val children = s.findAll<RefChild>("ORDER BY id")
        assertEquals(3, children.size)
        assertEquals("Alice", children[0].parent?.name)
        assertEquals("Alice", children[1].parent?.name)
        assertEquals("Bob", children[2].parent?.name)
    }

    @Test
    fun deleteWithReference() = withDb("REF-DELETE") { s ->
        s.createRefSchema()
        s.seedRefData()

        s.delete(s.findById<RefChild>(10)!!)
        assertEquals(2, s.findAll<RefChild>().size)
    }

    // --- Inheritance tests: plain Kotlin vs AutoTable ---
    // Each tests: inherited PK, String fields, Boolean fields, entity references, full CRUD

    private fun Stormify.createInheritSchema() {
        TestDDL.dropTable("plain_child")
        TestDDL.dropTable("at_child")
        TestDDL.dropTable("ref_parent")
        executeUpdate(TestDDL.createTable("ref_parent",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        executeUpdate(TestDDL.createTable("plain_child",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, is_active ${TestDDL.booleanType()}, " +
                    "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "ref_parent", "id")}"))
        executeUpdate(TestDDL.createTable("at_child",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, is_active ${TestDDL.booleanType()}, " +
                    "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "ref_parent", "id")}"))
    }

    private fun Stormify.seedParents() {
        create(RefParent().apply { id = 100; name = "ParentA" })
        create(RefParent().apply { id = 200; name = "ParentB" })
    }

    @Test
    fun plainInheritanceCrud() = withDb("PLAIN-INHERIT") { s ->
        s.createInheritSchema()
        s.seedParents()

        val parentA = s.findById<RefParent>(100)!!
        val parentB = s.findById<RefParent>(200)!!

        s.create(PlainChild().apply { id = 1; name = "Alice"; isActive = true; parent = parentA })
        s.create(PlainChild().apply { id = 2; name = "Bob"; isActive = false; parent = parentB })

        // Verify all fields after create
        val found = s.findById<PlainChild>(1)!!
        assertEquals("Alice", found.name)
        assertEquals(true, found.isActive)
        assertEquals(100, found.parent?.id)
        val bob = s.findById<PlainChild>(2)!!
        assertEquals("Bob", bob.name)
        assertEquals(false, bob.isActive)
        assertEquals(200, bob.parent?.id)

        // Update all fields and verify
        found.name = "Alice2"
        found.isActive = false
        found.parent = parentB
        s.update(found)
        val updated = s.findById<PlainChild>(1)!!
        assertEquals("Alice2", updated.name)
        assertEquals(false, updated.isActive)
        assertEquals("ParentB", updated.parent?.name)

        // Delete and verify remaining
        s.delete(updated)
        val remaining = s.findAll<PlainChild>()
        assertEquals(1, remaining.size)
        assertEquals("Bob", remaining[0].name)
    }

    @Test
    fun autoTableInheritanceCrud() = withDb("AT-INHERIT") { s ->
        s.createInheritSchema()
        s.seedParents()

        val parentA = s.findById<RefParent>(100)!!
        val parentB = s.findById<RefParent>(200)!!

        s.create(AtChild().apply { id = 1; name = "Alice"; isActive = true; parent = parentA })
        s.create(AtChild().apply { id = 2; name = "Bob"; isActive = false; parent = parentB })

        // Verify all fields after create
        val found = s.findById<AtChild>(1)!!
        assertEquals("Alice", found.name)
        assertEquals(true, found.isActive)
        assertEquals(100, found.parent?.id)
        val bob = s.findById<AtChild>(2)!!
        assertEquals("Bob", bob.name)
        assertEquals(false, bob.isActive)
        assertEquals(200, bob.parent?.id)

        // Update all fields and verify
        found.name = "Alice2"
        found.isActive = false
        found.parent = parentB
        s.update(found)
        val updated = s.findById<AtChild>(1)!!
        assertEquals("Alice2", updated.name)
        assertEquals(false, updated.isActive)
        assertEquals("ParentB", updated.parent?.name)

        // Delete and verify remaining
        s.delete(updated)
        val remaining = s.findAll<AtChild>()
        assertEquals(1, remaining.size)
        assertEquals("Bob", remaining[0].name)
    }

    // --- Enum via reflection (no annproc) ---

    @Test
    fun enumViaReflection() = withDb("REFL-ENUM") { s ->
        TestDDL.dropTable("refl_enum_test")
        s.executeUpdate(TestDDL.createTable("refl_enum_test",
            "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("plain_status")}, ${TestDDL.intColumn("custom_status")}"))

        // Create
        s.create(ReflEnumEntity().apply { id = 1; plainStatus = ReflPlainStatus.BANNED; customStatus = ReflCustomStatus.INACTIVE })

        // Read back
        val found = s.findById<ReflEnumEntity>(1)
        assertNotNull(found)
        assertEquals(ReflPlainStatus.BANNED, found.plainStatus)
        assertEquals(ReflCustomStatus.INACTIVE, found.customStatus)

        // Verify stored values
        assertEquals(2, s.readOne<Int>("SELECT plain_status FROM refl_enum_test WHERE id = ?", 1))   // ordinal
        assertEquals(20, s.readOne<Int>("SELECT custom_status FROM refl_enum_test WHERE id = ?", 1))  // dbValue

        // Update
        found.plainStatus = ReflPlainStatus.ACTIVE
        found.customStatus = ReflCustomStatus.BANNED
        s.update(found)
        val updated = s.findById<ReflEnumEntity>(1)!!
        assertEquals(ReflPlainStatus.ACTIVE, updated.plainStatus)
        assertEquals(ReflCustomStatus.BANNED, updated.customStatus)

        // Query with enum param
        val results = s.read<ReflEnumEntity>("SELECT * FROM refl_enum_test WHERE plain_status = ?", ReflPlainStatus.ACTIVE)
        assertEquals(1, results.size)

        // Null enum
        s.create(ReflEnumEntity().apply { id = 2 })
        val nullEntity = s.findById<ReflEnumEntity>(2)!!
        assertNull(nullEntity.plainStatus)
        assertNull(nullEntity.customStatus)
    }

    @Test
    fun enumAsStringViaReflection() = withDb("REFL-ENUM-STRING") { s ->
        TestDDL.dropTable("refl_enum_string_test")
        s.executeUpdate(TestDDL.createTable("refl_enum_string_test",
            "${TestDDL.intPrimaryKey("id")}, status ${TestDDL.textType()}, ${TestDDL.intColumn("priority")}"))

        s.create(ReflEnumStringEntity().apply { id = 1; status = ReflPlainStatus.BANNED; priority = ReflPlainStatus.BANNED })

        // Verify string vs ordinal storage
        assertEquals("BANNED", s.readOne<String>("SELECT status FROM refl_enum_string_test WHERE id = ?", 1))
        assertEquals(2, s.readOne<Int>("SELECT priority FROM refl_enum_string_test WHERE id = ?", 1))

        // Read back
        val found = s.findById<ReflEnumStringEntity>(1)!!
        assertEquals(ReflPlainStatus.BANNED, found.status)
        assertEquals(ReflPlainStatus.BANNED, found.priority)

        // Update
        found.status = ReflPlainStatus.ACTIVE
        s.update(found)
        assertEquals("ACTIVE", s.readOne<String>("SELECT status FROM refl_enum_string_test WHERE id = ?", 1))
    }
}

// --- Enum test entities (reflection-only, no annproc) ---

enum class ReflPlainStatus { ACTIVE, INACTIVE, BANNED }

enum class ReflCustomStatus(override val dbValue: Int) : DbValue {
    ACTIVE(10), INACTIVE(20), BANNED(99)
}

@DbTable(name = "refl_enum_test")
class ReflEnumEntity {
    @DbField(primaryKey = true) var id: Int = 0
    var plainStatus: ReflPlainStatus? = null
    var customStatus: ReflCustomStatus? = null
}

@DbTable(name = "refl_enum_string_test")
class ReflEnumStringEntity {
    @DbField(primaryKey = true) var id: Int = 0
    @DbField(enumAsString = true) var status: ReflPlainStatus? = null
    var priority: ReflPlainStatus? = null  // ordinal for comparison
}

// --- Test entity classes ---

@DbTable
class RefParent : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var name: String? by db(null)
    override fun toString() = "RefParent(id=$id, name=$name)"
}

@DbTable
class RefChild : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var title: String? by db(null)
    var parent: RefParent? by db(null)
    override fun toString() = "RefChild(id=$id, title=$title)"
}

// Plain Kotlin inheritance — no AutoTable, no delegates
open class PlainBase {
    @DbField(primaryKey = true) var id: Int = 0
    var name: String = ""
    var isActive: Boolean = false
}

@DbTable
class PlainChild : PlainBase() {
    var parent: RefParent? = null
}

// AutoTable inheritance — delegates in both base and subclass
open class AtBase : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var name: String? by db(null)
    var isActive: Boolean? by db(null)
}

@DbTable
class AtChild : AtBase() {
    var parent: RefParent? by db(null)
}
