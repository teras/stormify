package test

import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Verifies that [SQLException.sqlState] and [SQLException.errorCode] are populated
 * with the values reported by the underlying driver across the dialects we test.
 *
 * Each test triggers a deterministic vendor-side failure (constraint violation,
 * undefined table) and asserts that at least one of the two structured fields
 * was surfaced. The exact values vary per dialect, so the assertions are family-
 * scoped: PostgreSQL/MariaDB/MySQL/Oracle expose 5-character SQLSTATE; SQLite
 * surfaces only its extended numeric `errorCode`; SQL Server surfaces only the
 * numeric `errorCode` (db-lib does not propagate SQLSTATE).
 */
open class SQLExceptionFieldsTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    @Test
    fun uniqueViolationSurfacesStructuredFields() = withDb("SQLEX-UNIQUE") { s ->
        TestDDL.dropTable("sqlex_uq")
        s.executeUpdate(TestDDL.createTable("sqlex_uq",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        s.executeUpdate("INSERT INTO sqlex_uq (id, name) VALUES (?, ?)", 1, "first")

        val ex = catchSql {
            s.executeUpdate("INSERT INTO sqlex_uq (id, name) VALUES (?, ?)", 1, "duplicate")
        }
        assertHasStructuredFields(ex, expectSqlStatePrefix = uniqueViolationSqlStatePrefix())
    }

    @Test
    fun undefinedTableSurfacesStructuredFields() = withDb("SQLEX-NO-TABLE") { s ->
        // Reference a table that definitely does not exist.
        val ex = catchSql {
            s.executeUpdate("INSERT INTO this_table_does_not_exist_kdbc (id) VALUES (?)", 1)
        }
        assertHasStructuredFields(ex, expectSqlStatePrefix = undefinedTableSqlStatePrefix())
    }

    private fun catchSql(block: () -> Unit): SQLException {
        try {
            block()
        } catch (e: SQLException) {
            return e
        } catch (e: Throwable) {
            // Stormify wraps SQLException with another SQLException carrying the
            // original as cause. Walk the chain.
            var cur: Throwable? = e
            while (cur != null) {
                if (cur is SQLException) return cur
                val nxt = cur.cause
                cur = if (nxt === cur) null else nxt
            }
            throw e
        }
        fail("Expected SQLException but no exception was thrown")
    }

    /**
     * Asserts that at least one structured field is populated, and — when the
     * dialect is known to surface SQLSTATE — that it starts with [expectSqlStatePrefix].
     * Walks the cause chain because Stormify wraps the kdbc exception again.
     */
    private fun assertHasStructuredFields(ex: SQLException, expectSqlStatePrefix: String?) {
        val (state, code) = pickFields(ex)
        val hasAny = !state.isNullOrEmpty() || (code != null && code != 0)
        assertTrue(hasAny, "Expected sqlState or errorCode to be populated, got " +
                "sqlState=$state, errorCode=$code, message=${ex.message}")

        if (expectSqlStatePrefix != null) {
            assertNotNull(state, "Dialect ${TestDDL.dialect} should surface SQLSTATE; got null")
            assertTrue(state.startsWith(expectSqlStatePrefix),
                "Expected SQLSTATE starting with '$expectSqlStatePrefix', got '$state'")
        }
    }

    /** Walks the cause chain and returns the first (sqlState, errorCode) pair where either is set. */
    private fun pickFields(ex: SQLException): Pair<String?, Int?> {
        var cur: Throwable? = ex
        while (cur != null) {
            if (cur is SQLException) {
                val s = cur.sqlState
                val c = cur.errorCode
                if (!s.isNullOrEmpty() || (c != null && c != 0)) return s to c
            }
            val nxt = cur.cause
            cur = if (nxt === cur) null else nxt
        }
        return ex.sqlState to ex.errorCode
    }

    /**
     * Expected SQLSTATE class for unique-key violations on the running dialect.
     * Standard SQL-92 says `23xxx` (integrity constraint), surfaced as such by
     * PostgreSQL and MariaDB/MySQL. Oracle (via ODPI-C) reports `HY000` for
     * most ORA errors and carries the structured info in [SQLException.errorCode]
     * instead, so it is excluded from the SQLSTATE check. Returns `null` for
     * dialects that do not surface SQLSTATE at all (SQLite, MS SQL Server).
     */
    private fun uniqueViolationSqlStatePrefix(): String? = when (TestDDL.dialect) {
        SqlDialect.POSTGRESQL,
        SqlDialect.MYSQL_OLD, SqlDialect.MYSQL_NEW,
        SqlDialect.MARIA_DB_OLD, SqlDialect.MARIA_DB_NEW -> "23"
        else -> null
    }

    /**
     * Expected SQLSTATE class for "table does not exist". PostgreSQL: `42P01`;
     * MySQL/MariaDB: `42S02`. The shared prefix is `42` — syntax/access rule
     * violation. Oracle (via ODPI-C) reports `HY000` instead and carries the
     * structured info in [SQLException.errorCode]. Returns `null` where
     * SQLSTATE is not surfaced.
     */
    private fun undefinedTableSqlStatePrefix(): String? = when (TestDDL.dialect) {
        SqlDialect.POSTGRESQL,
        SqlDialect.MYSQL_OLD, SqlDialect.MYSQL_NEW,
        SqlDialect.MARIA_DB_OLD, SqlDialect.MARIA_DB_NEW -> "42"
        else -> null
    }
}
