package test

import onl.ycode.kdbc.JdbcDataSource
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wrap a JDBC object so every JDBC interface it hands out is wrapped as well, and every
 * typed `getObject(int, Class)` throws, the way a strict driver refuses a conversion it
 * does not support.
 */
private fun <T : Any> strict(real: T, type: Class<T>): T {
    val handler = InvocationHandler { _, method, args ->
        if (method.name == "getObject" && method.parameterTypes.contentEquals(arrayOf(Int::class.java, Class::class.java)))
            throw java.sql.SQLFeatureNotSupportedException("typed getObject refused")
        val result = try {
            method.invoke(real, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
        val returned = method.returnType
        @Suppress("UNCHECKED_CAST")
        if (result != null && returned.isInterface && returned.name.startsWith("java.sql."))
            strict(result, returned as Class<Any>)
        else result
    }
    return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler))
}

/**
 * When the driver refuses the typed read, kdbc reads the value untyped; it must still
 * hand back the type the caller asked for, as it does when the typed read succeeds.
 */
class StrictDriverFallbackTest {
    @Test
    fun untypedFallbackIsCoercedToTheRequestedType() = TestHelper.withDb("STRICT-DRIVER-FALLBACK") {
        val jdbc = object : javax.sql.DataSource by testJdbcDataSource {
            override fun getConnection(): java.sql.Connection =
                strict(testJdbcDataSource.connection, java.sql.Connection::class.java)
        }
        val conn = JdbcDataSource(jdbc).getConnection()
        try {
            val rs = conn.initStatement(TestDDL.selectExpr("'42', 7"), false, null).executeQuery()
            rs.next()
            assertEquals(42, rs.getObject(1, Int::class))
            assertEquals("7", rs.getObject(2, String::class))
        } finally {
            conn.close()
        }
    }
}
