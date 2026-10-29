package test.large

import onl.ycode.stormify.EntityMeta
import onl.ycode.stormify.EnumRegistry
import test.large.generated.GeneratedEntities
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The registrar of 400 entities compiles (the point of this module) and its generated code
 * registers every entity and enum. Checked on the registries themselves, which only the
 * registrar fills: `getTableInfo` would fall back to reflection on the JVM.
 */
class LargeRegistrarTest {
    private fun cls(name: String): KClass<*> = Class.forName("test.large.entities.$name").kotlin

    @Test
    fun registrarOfManyEntitiesRegistersThemAll() {
        val entities = (1..400).map { cls("Entity$it") }
        val enums = (1..40).map { cls("Status$it") }
        assertNull(EntityMeta.find(entities.first()), "registry filled before register()")

        GeneratedEntities.register()

        for ((i, entity) in entities.withIndex()) {
            val meta = assertNotNull(EntityMeta.find(entity), "Entity${i + 1} not registered")
            assertEquals("large_entity_${i + 1}", meta.tableNameOverride)
            assertEquals(if (i == 0) 16 else 17, meta.properties.size, "properties of Entity${i + 1}")
            assertEquals("code_${i + 1}", meta.properties.single { it.name == "code" }.dbNameOverride)
        }
        for (e in enums) assertTrue(EnumRegistry.isRegistered(e), "${e.simpleName} not registered")
    }
}
