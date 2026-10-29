package test.jpa

import onl.ycode.kdbc.JdbcDataSource
import onl.ycode.stormify.FieldInfo
import onl.ycode.stormify.EnumRegistry
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.TableInfo
import org.sqlite.SQLiteDataSource
import test.jpa.generated.GeneratedEntities
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnprocJpaTest {
    private fun newStormify() = Stormify(JdbcDataSource(SQLiteDataSource().apply { url = "jdbc:sqlite::memory:" }))

    private val entities: List<KClass<*>> = listOf(JpaOrder::class, JpaCustomer::class, EnumStatus::class)

    private fun TableInfo<*>.byName(): Map<String, FieldInfo> = fieldInfos.associateBy { it.name }

    /**
     * One test on purpose: reflection must be resolved before the generated registrar is
     * registered, since the registry is global and wins over reflection once filled.
     */
    @Test
    fun annprocMatchesReflectionForJpaAnnotations() {
        val reflected = newStormify().let { s -> entities.associateWith { s.getTableInfo(it) } }
        GeneratedEntities.register()
        val generated = newStormify().let { s -> entities.associateWith { s.getTableInfo(it) } }

        for (cls in entities) {
            val r = reflected.getValue(cls)
            val g = generated.getValue(cls)
            assertEquals(r.tableName, g.tableName, "table name of ${cls.simpleName}")
            assertEquals(r.byName(), g.byName(), "fields of ${cls.simpleName}")
        }

        // And both are right, not merely equal.
        val order = generated.getValue(JpaOrder::class)
        assertEquals("jpa_orders", order.tableName)
        val f = order.byName()
        assertEquals(setOf("id", "code", "createdBy", "customer", "status", "price"), f.keys)
        assertEquals("price\$ε", f.getValue("price").dbName)
        f.getValue("id").let {
            assertEquals("order_id", it.dbName)
            assertTrue(it.isPrimaryKey)
            assertEquals("jpa_order_seq", it.sequence)
            assertTrue(!it.isAutoIncrement)
        }
        f.getValue("code").let {
            assertEquals("order_code", it.dbName)
            assertTrue(!it.isInsertable && it.isUpdatable)
        }
        f.getValue("createdBy").let { assertTrue(it.isInsertable && !it.isUpdatable) }
        f.getValue("customer").let {
            assertEquals("customer_id", it.dbName)
            assertTrue(it.isReference && !it.isInsertable && !it.isUpdatable)
        }
        assertTrue(f.getValue("status").enumAsString)

        // An enum and an entity whose registration functions once shared a name.
        assertTrue(EnumRegistry.isRegistered(Status::class))
        assertEquals("enum_status", generated.getValue(EnumStatus::class).tableName)

        val customer = generated.getValue(JpaCustomer::class)
        assertEquals("jpa_customer_by_entity", customer.tableName)
        customer.byName().getValue("id").let {
            assertTrue(it.isPrimaryKey && it.isAutoIncrement)
            assertNull(it.sequence)
        }
    }
}
