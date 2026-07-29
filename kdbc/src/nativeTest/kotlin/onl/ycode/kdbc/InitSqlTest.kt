// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `initSql` accepts more than one statement, separated by `;`, and runs them in order. Both
 * assertions below double as the regression guard: the second pragma only takes effect if
 * every statement is executed, not just the first.
 */
class InitSqlTest {

    private fun Connection.pragmaInt(name: String): Int =
        initStatement("PRAGMA $name", false, null).use { s ->
            s.executeQuery().use { rs ->
                assertTrue(rs.next(), "PRAGMA $name returned no row")
                rs.getObject(1, Int::class) as Int
            }
        }

    @Test
    fun runsEveryStatementInOrder() {
        val ds = KdbcDataSource(
            "jdbc:sqlite::memory:",
            initSql = "PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 4200",
        )
        ds.getConnection().use { conn ->
            // Both must have taken effect on the very connection handed back. busy_timeout
            // defaults to 0, so 4200 can only be there if the second statement ran.
            assertEquals(1, conn.pragmaInt("foreign_keys"))
            assertEquals(4200, conn.pragmaInt("busy_timeout"))
        }
    }

    @Test
    fun skipsBlankEntries() {
        // Empty segments around the `;` must not produce an empty prepare.
        val ds = KdbcDataSource(
            "jdbc:sqlite::memory:",
            initSql = ";PRAGMA busy_timeout = 1500;   ;",
        )
        ds.getConnection().use { conn ->
            assertEquals(1500, conn.pragmaInt("busy_timeout"))
        }
    }
}
