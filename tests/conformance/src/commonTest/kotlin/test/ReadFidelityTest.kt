// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import com.ionspin.kotlin.bignum.decimal.BigDecimal as IonBigDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Values written by the database itself (as literals) read back as they are stored,
 * whatever the zone of the client: the cases [TypeProbeTest] found wrong.
 */
open class ReadFidelityTest {
    private val oracle get() = TestDDL.dialect == SqlDialect.ORACLE_NEW || TestDDL.dialect == SqlDialect.ORACLE_OLD

    private fun withRow(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) { s ->
        val d = TestDDL
        val ts = if (d.isMysqlFamily) "DATETIME(3)" else d.timestampType()
        TestDDL.dropTable("read_fidelity")
        s.executeUpdate(d.createTable("read_fidelity",
            "c_dbl ${d.doubleType()}, c_date DATE, c_ts $ts" + if (oracle) "" else ", c_time TIME"))
        val date = if (oracle) "DATE '2024-03-15'" else "'2024-03-15'"
        val stamp = if (oracle) "TIMESTAMP '2024-03-15 13:45:30.123'" else "'2024-03-15 13:45:30.123'"
        s.executeUpdate(
            if (oracle) "INSERT INTO read_fidelity (c_dbl, c_date, c_ts) VALUES (0.1, $date, $stamp)"
            else "INSERT INTO read_fidelity (c_dbl, c_date, c_ts, c_time) VALUES (0.1, $date, $stamp, '13:45:30')"
        )
        try {
            test(s)
        } finally {
            TestDDL.dropTable("read_fidelity")
        }
    }

    @Test
    fun wallClockTypesReadTheStoredWallTime() = withRow("READ-WALL-CLOCK") { s ->
        assertEquals(LocalDate(2024, 3, 15), s.readOne<LocalDate>("SELECT c_date FROM read_fidelity"))
        assertEquals(LocalDateTime(2024, 3, 15, 0, 0), s.readOne<LocalDateTime>("SELECT c_date FROM read_fidelity"))
        assertEquals(LocalDateTime(2024, 3, 15, 13, 45, 30, 123_000_000), s.readOne<LocalDateTime>("SELECT c_ts FROM read_fidelity"))
        assertEquals(LocalDate(2024, 3, 15), s.readOne<LocalDate>("SELECT c_ts FROM read_fidelity"))
        assertEquals(LocalTime(13, 45, 30, 123_000_000), s.readOne<LocalTime>("SELECT c_ts FROM read_fidelity"))
        if (!oracle) assertEquals(LocalTime(13, 45, 30), s.readOne<LocalTime>("SELECT c_time FROM read_fidelity"))
    }

    @Test
    fun aWallClockTypeIsWrittenAsItsWallTime() = withRow("WRITE-WALL-CLOCK") { s ->
        if (TestDDL.isSqlite) skipTest(SkipReason.DIALECT_QUIRK, "SQLite compares the stored text or milliseconds, not a time")
        val stamp = LocalDateTime(2024, 3, 15, 13, 45, 30, 123_000_000)
        assertEquals(1, s.readOne<Int>("SELECT COUNT(*) FROM read_fidelity WHERE c_ts = ?", stamp))
        assertEquals(1, s.readOne<Int>("SELECT COUNT(*) FROM read_fidelity WHERE c_date = ?", LocalDate(2024, 3, 15)))
    }

    @Test
    fun aDoubleReadsAsItsShortestDecimal() = withRow("READ-DOUBLE-DECIMAL") { s ->
        assertEquals(IonBigDecimal.parseString("0.1"), s.readOne<IonBigDecimal>("SELECT c_dbl FROM read_fidelity"))
    }

    /** A query's parameters keep their type: as text, SQLite would compare `'10' < 100` as false. */
    @Test
    fun queryParametersAreBoundWithTheirType() = TestHelper.withDb("QUERY-PARAMETER-TYPES") { s ->
        assertEquals(1, s.readOne<Int>(TestDDL.selectExpr("CASE WHEN ? < 100 THEN 1 ELSE 0 END"), 10))
        // A NULL stays NULL (only SQLite takes a parameter of no known type here)
        if (TestDDL.isSqlite)
            assertEquals(1, s.readOne<Int>(TestDDL.selectExpr("CASE WHEN ? IS NULL THEN 1 ELSE 0 END"), null))
    }

    @Test
    fun aTemporalColumnReadsAsText() = withRow("READ-TEMPORAL-TEXT") { s ->
        for (column in listOf("c_date", "c_ts")) {
            val text = s.readOne<String>("SELECT $column FROM read_fidelity")
            assertTrue(text?.startsWith("2024-03-15") == true, "$column as text: $text")
        }
    }
}
