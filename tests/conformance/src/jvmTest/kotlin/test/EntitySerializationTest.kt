// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.Stormify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

/**
 * Entities survive Java serialization, as when they are kept in an HTTP session:
 * the `db` and `lazyDetails` delegates are serialized with them, a shadow reference
 * that was never loaded loads its row after deserialization through the default
 * instance, and what was already loaded is kept without going back to the database.
 */
class EntitySerializationTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault(); test(it)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(value) } }
        return ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() } as T
    }

    private fun Stormify.setup() {
        TestDDL.dropTable("auto_child")
        TestDDL.dropTable("auto_parent")
        executeUpdate(TestDDL.createTable("auto_parent",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, other ${TestDDL.textType()}"))
        executeUpdate(TestDDL.createTable("auto_child",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, " +
                    "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "auto_parent", "id")}"))
        executeUpdate("INSERT INTO auto_parent (id, data, other) VALUES (1, 'P1', 'O1')")
        executeUpdate("INSERT INTO auto_child (id, data, parent) VALUES (10, 'C10', 1)")
        executeUpdate("INSERT INTO auto_child (id, data, parent) VALUES (11, 'C11', 1)")
    }

    // A fixed version, so a later release can still read what this one serialized.
    @Test
    fun serializableClassesHaveFixedVersion() {
        for (name in listOf("StormifyEntity", "AutoTable", "db", "LazyDetailsProperty")) {
            val cls = Class.forName("onl.ycode.stormify.$name")
            assertEquals(1L, java.io.ObjectStreamClass.lookup(cls).serialVersionUID, name)
        }
    }

    @Test
    fun unloadedShadowReferenceLoadsAfterDeserialization() = withDb("SER-STUB") { s ->
        s.setup()
        val child = s.findById<AutoChildEntity>(10)!!
        // Serialized before anything touches the parent: it is still an unloaded stub.
        val copy = roundTrip(child)
        assertNotSame(child, copy)
        assertEquals("C10", copy.data)
        val parent = copy.parent!!
        assertEquals(1, parent.id)
        assertEquals("P1", parent.data)
        assertEquals("O1", parent.other)
    }

    // A deserialized stub has no instance of its own; without a default one it says so
    // instead of staying an entity with empty fields.
    @Test
    fun unloadedShadowReferenceWithoutAnInstanceThrows() = withDb("SER-STUB-NO-INSTANCE") { s ->
        s.setup()
        val copy = roundTrip(s.findById<AutoChildEntity>(10)!!.parent!!)
        // The default instance has no public way to be unset; the internal one will do here.
        Stormify::class.java.declaredClasses.single { it.simpleName == "Companion" }
            .methods.single { it.name.startsWith("clearDefault") }.invoke(Stormify.Companion)
        try {
            val e = assertFailsWith<onl.ycode.kdbc.SQLException> { copy.data }
            assertEquals(true, e.message!!.contains("No Stormify instance"), e.message)
        } finally {
            s.asDefault()
        }
    }

    @Test
    fun loadedValuesAreKeptWithoutReloading() = withDb("SER-LOADED") { s ->
        s.setup()
        val parent = s.findById<AutoParentEntity>(1)!!
        assertEquals(listOf(10, 11), parent.children.mapNotNull { it.id }.sorted())

        val copy = roundTrip(parent)
        // Change the database: a copy that reloaded would see the new values.
        s.executeUpdate("UPDATE auto_parent SET data = 'changed' WHERE id = 1")
        s.executeUpdate("DELETE FROM auto_child WHERE id = 11")

        assertEquals("P1", copy.data)
        assertEquals("O1", copy.other)
        assertEquals(listOf("C10", "C11"), copy.children.mapNotNull { it.data }.sorted())
    }

    // The loaded state is serialized, so a deserialized entity is not reloaded on first
    // access: unsaved changes survive, and an entity not yet in the database stays usable.
    @Test
    fun unsavedChangesSurviveDeserialization() = withDb("SER-UNSAVED") { s ->
        s.setup()
        val parent = s.findById<AutoParentEntity>(1)!!
        parent.data = "edited"
        val copy = roundTrip(parent)
        assertEquals("edited", copy.data)
        assertEquals("O1", copy.other)
        assertEquals("P1", s.readOne<String>("SELECT data FROM auto_parent WHERE id = 1"))

        val fresh = roundTrip(AutoParentEntity().apply { id = 99; data = "new" })
        assertEquals("new", fresh.data)
        s.create(fresh)
        assertEquals("new", s.readOne<String>("SELECT data FROM auto_parent WHERE id = 99"))
    }

    @Test
    fun lazyDetailsNotYetLoadedLoadAfterDeserialization() = withDb("SER-LAZY-DETAILS") { s ->
        s.setup()
        val parent = s.findById<AutoParentEntity>(1)!!
        val copy = roundTrip(parent)
        s.executeUpdate("INSERT INTO auto_child (id, data, parent) VALUES (12, 'C12', 1)")
        assertEquals(listOf(10, 11, 12), copy.children.mapNotNull { it.id }.sorted())
    }
}
