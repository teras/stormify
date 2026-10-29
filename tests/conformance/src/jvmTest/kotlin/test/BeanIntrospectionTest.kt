package test

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import onl.ycode.stormify.db
import java.beans.Introspector
import java.io.StringWriter
import javax.xml.bind.JAXBContext
import javax.xml.bind.annotation.XmlRootElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@XmlRootElement
@DbTable(name = "bean_row")
class BeanRow : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int? = null
    var name: String? by db(null)
}

/**
 * The library's own bookkeeping on [AutoTable] / `StormifyEntity` (the owning
 * Stormify instance, the details group, the sibling group) must not surface as
 * JavaBean properties: bean-driven frameworks (JAXB, Jackson, bean copiers)
 * would otherwise try to read, write or serialize them.
 */
class BeanIntrospectionTest {
    @Test
    fun internalStateIsNotABeanProperty() {
        val names = Introspector.getBeanInfo(BeanRow::class.java).propertyDescriptors.map { it.name }.toSet()
        assertEquals(setOf("class", "id", "name"), names)
    }

    @Test
    fun internalStateIsNotPublicJavaApi() {
        for (cls in listOf(onl.ycode.stormify.StormifyEntity::class.java, AutoTable::class.java)) {
            assertEquals(emptyList(), cls.declaredFields.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                .map { it.name }, "public fields of ${cls.simpleName}")
            val visible = cls.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .map { it.name }.toSet()
            assertTrue(visible.all { it in setOf("markHydrated", "attachTo") },
                "public methods of ${cls.simpleName}: $visible")
        }
    }

    @Test
    fun jaxbMarshalsOnlyTheEntityFields() = TestHelper.withDb("BEAN-JAXB") { s ->
        // Attached, so the bookkeeping holds a value JAXB would try to write.
        val row = BeanRow().apply { id = 7; name = "seven" }
        s.attach(row)
        val out = StringWriter()
        JAXBContext.newInstance(BeanRow::class.java).createMarshaller().marshal(row, out)
        val xml = out.toString()
        assertTrue(xml.contains("<id>7</id>") && xml.contains("<name>seven</name>"), xml)
        assertTrue(!xml.contains("stormify") && !xml.contains("Group"), xml)
    }
}
