// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Cancelling the coroutine of a suspend transaction stops the query it is running at
 * the database, instead of waiting for the query to finish.
 */
open class QueryCancelTest {
    /** Android SQLite has no primitive to interrupt a running statement. */
    protected open val cancelSupported = true

    /** A statement that runs for about 30 seconds unless it is cancelled. */
    private fun longStatement(s: Stormify): () -> Unit = when (TestDDL.dialect) {
        SqlDialect.POSTGRESQL -> { { s.read<String>("SELECT pg_sleep(30)") } }
        SqlDialect.SQL_SERVER_NEW, SqlDialect.SQL_SERVER_OLD -> { { s.executeUpdate("WAITFOR DELAY '00:00:30'") } }
        SqlDialect.ORACLE_NEW, SqlDialect.ORACLE_OLD -> { {
            s.read<String>("SELECT COUNT(*) FROM all_objects a, all_objects b, all_objects c")
        } }
        SqlDialect.SQLITE -> { {
            s.read<String>("WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 500000000) SELECT COUNT(*) FROM c")
        } }
        // MySQL and MariaDB
        else -> { { s.read<String>("SELECT SLEEP(30)") } }
    }

    @Test
    fun cancellingTheCoroutineStopsTheRunningQuery() = TestHelper.withDb("QUERY-CANCEL") { s ->
        if (!cancelSupported) skipTest(SkipReason.DB_LIMITATION, "no way to interrupt a running statement")
        val statement = longStatement(s)
        runBlocking {
            var early: Throwable? = null
            val job = launch(Dispatchers.Default) {
                s.suspending.transaction {
                    try {
                        statement()
                    } catch (e: Throwable) {
                        early = e
                        throw e
                    }
                }
            }
            delay(1000)
            assertTrue(job.isActive, "the statement ended before it was cancelled: $early")
            val start = TimeSource.Monotonic.markNow()
            job.cancelAndJoin()
            val took = start.elapsedNow()
            assertTrue(took < 10.seconds, "the cancelled query ran on for $took")
            // The pool is usable after the cancel
            try {
                assertTrue(s.suspending.withConnection { s.readOne<Int>(TestDDL.selectExpr("1")) } == 1)
            } finally {
                s.suspending.close()
            }
        }
    }
}
