// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.kdbc.JdbcDataSource
import onl.ycode.stormify.SqlDialect
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The common reads of a type other than the column's own are never refused by the
 * driver: a refusal is recovered from, but the driver or the pool wrapping it may log
 * it first (Payara logs every one as SEVERE), and each costs a thrown exception.
 */
class DriverRejectionTest {
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun readsOfAnotherTypeAreNotRefused() = TestHelper.withDb("DRIVER-REJECTIONS") { s ->
        val d = TestDDL
        val oracle = d.dialect == SqlDialect.ORACLE_NEW || d.dialect == SqlDialect.ORACLE_OLD
        val varchar = if (oracle) "VARCHAR2" else "VARCHAR"
        val ts = if (d.isMysqlFamily) "DATETIME(3)" else d.timestampType()
        TestDDL.dropTable("driver_rejections")
        s.executeUpdate(d.createTable("driver_rejections",
            "c_int ${d.intType()}, c_big ${d.bigIntType()}, c_dec ${d.decimalType(12, 2)}, c_dbl ${d.doubleType()}, " +
                    "c_ch CHAR(1), c_uuid $varchar(36), c_date DATE, c_ts $ts"))
        val stamp = if (oracle) "TIMESTAMP '2024-03-15 13:45:30.123'" else "'2024-03-15 13:45:30.123'"
        val date = if (oracle) "DATE '2024-03-15'" else "'2024-03-15'"
        s.executeUpdate("INSERT INTO driver_rejections VALUES (42, 1234567890123, 42.50, 0.1, 'y', '$uuid', $date, $stamp)")

        val rejections = mutableListOf<String>()
        val conn = JdbcDataSource(recordingRejections(testJdbcDataSource) { rejections += it }).getConnection()
        try {
            fun read(column: String, type: KClass<*>): Any? {
                val rs = conn.initStatement("SELECT $column FROM driver_rejections", false, null).executeQuery()
                rs.next()
                return rs.getObject(1, type).also { rs.close() }
            }
            assertEquals(42L, read("c_int", Long::class))
            assertEquals(42.toShort(), read("c_int", Short::class))
            assertEquals(java.math.BigInteger.valueOf(42), read("c_int", java.math.BigInteger::class))
            assertEquals(java.math.BigInteger.valueOf(1234567890123), read("c_big", java.math.BigInteger::class))
            assertEquals(0, java.math.BigDecimal("42").compareTo(read("c_int", java.math.BigDecimal::class) as java.math.BigDecimal))
            assertEquals(42.5, read("c_dec", Double::class))
            // The shortest decimal of the double, not its binary expansion 0.1000000000000000055...
            assertEquals(java.math.BigDecimal("0.1"), read("c_dbl", java.math.BigDecimal::class))
            assertEquals("42", read("c_int", String::class))
            assertEquals('y', read("c_ch", Char::class))
            assertEquals(java.util.UUID.fromString(uuid), read("c_uuid", java.util.UUID::class))
            assertEquals(Uuid.parse(uuid), read("c_uuid", Uuid::class))
            assertEquals(java.time.LocalDate.of(2024, 3, 15), read("c_ts", java.time.LocalDate::class))
            assertEquals(java.time.LocalTime.of(13, 45, 30, 123_000_000), read("c_ts", java.time.LocalTime::class))
            assertEquals(java.time.LocalDateTime.of(2024, 3, 15, 0, 0), read("c_date", java.time.LocalDateTime::class))
            assertTrue(rejections.isEmpty(), "refused by the driver: $rejections")
        } finally {
            conn.close()
            TestDDL.dropTable("driver_rejections")
        }
    }

    /** Oracle's DATE holds a time of day too, which the date-time types must keep. */
    @Test
    fun anOracleDateKeepsItsTime() = TestHelper.withDb("ORACLE-DATE-TIME") { s ->
        if (TestDDL.dialect != SqlDialect.ORACLE_NEW && TestDDL.dialect != SqlDialect.ORACLE_OLD)
            skipTest(SkipReason.DB_LIMITATION, "only Oracle's DATE holds a time")
        TestDDL.dropTable("oracle_date_time")
        s.executeUpdate("CREATE TABLE oracle_date_time (c_date DATE)")
        s.executeUpdate("INSERT INTO oracle_date_time VALUES (TO_DATE('2024-03-15 13:45:30', 'YYYY-MM-DD HH24:MI:SS'))")
        try {
            val expected = java.time.LocalDateTime.of(2024, 3, 15, 13, 45, 30)
            val query = "SELECT c_date FROM oracle_date_time"
            assertEquals(expected, s.readOne<java.time.LocalDateTime>(query))
            assertEquals(expected, s.readOne<java.sql.Timestamp>(query)!!.toLocalDateTime())
            assertEquals(expected, java.time.LocalDateTime.ofInstant(
                s.readOne<java.util.Date>(query)!!.toInstant(), java.time.ZoneId.systemDefault()))
            assertEquals(kotlinx.datetime.LocalDateTime(2024, 3, 15, 13, 45, 30), s.readOne<kotlinx.datetime.LocalDateTime>(query))
        } finally {
            TestDDL.dropTable("oracle_date_time")
        }
    }

    @Test
    fun aJavaUtilDateIsHandedOverAsOne() = TestHelper.withDb("UTIL-DATE-FROM-DATE") { s ->
        val oracle = TestDDL.dialect == SqlDialect.ORACLE_NEW || TestDDL.dialect == SqlDialect.ORACLE_OLD
        TestDDL.dropTable("util_date_test")
        s.executeUpdate(TestDDL.createTable("util_date_test", "c_date DATE"))
        s.executeUpdate("INSERT INTO util_date_test VALUES (${if (oracle) "DATE '2024-03-15'" else "'2024-03-15'"})")
        try {
            val value = s.readOne<java.util.Date>("SELECT c_date FROM util_date_test")!!
            // A java.sql.Date would throw here
            assertEquals(java.time.LocalDate.of(2024, 3, 15),
                value.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate())
        } finally {
            TestDDL.dropTable("util_date_test")
        }
    }
}
