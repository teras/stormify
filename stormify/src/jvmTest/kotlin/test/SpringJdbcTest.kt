package test

import onl.ycode.kdbc.JdbcDataSource
import onl.ycode.stormify.Stormify
import kotlin.test.*

class SpringJdbcTest {

    @Test
    fun testCrudViaSpringDataSource() {
        // Use Spring's SimpleDriverDataSource wrapping SQLite
        val dsClass = try {
            Class.forName("org.springframework.jdbc.datasource.SimpleDriverDataSource")
        } catch (_: ClassNotFoundException) {
            println("Skipping: Spring JDBC not in classpath")
            return
        }
        val driverClass = Class.forName("org.sqlite.JDBC")
        val driver = driverClass.getDeclaredConstructor().newInstance()
        val springDs = dsClass.getDeclaredConstructor().newInstance()
        dsClass.getMethod("setDriver", java.sql.Driver::class.java).invoke(springDs, driver)

        val tmpFile = java.io.File.createTempFile("stormify-spring-test", ".db")
        tmpFile.deleteOnExit()
        dsClass.getMethod("setUrl", String::class.java).invoke(springDs, "jdbc:sqlite:${tmpFile.absolutePath}")

        val s = Stormify(JdbcDataSource(springDs as javax.sql.DataSource))
        s.isStrictMode = false
        s.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }

        s.executeUpdate("CREATE TABLE test (id INT PRIMARY KEY, name TEXT)")

        val item = TestC(1, "SpringTest")
        s.create(item)
        assertEquals("[TestC(id=1, name=SpringTest)]", s.findAll<TestC>().toString())

        item.name = "Updated"
        s.update(item)
        val found = s.findById<TestC>(1)
        assertEquals("Updated", found?.name)

        s.delete(item)
        assertTrue(s.findAll<TestC>().isEmpty())
    }
}
