// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.coroutines.PoolConfig
import onl.ycode.stormify.coroutines.SuspendStormify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Conformance tests for [SuspendStormify.withConnection] and the `stormify.suspending`
 * shared-pool property:
 *
 *  - bare borrow runs reads on the pooled connection
 *  - the full 2x2 nesting matrix (withConnection/transaction in either order)
 *  - application exceptions propagate unwrapped and do NOT evict the connection
 *  - cancellation propagates unwrapped and DOES evict the connection
 *  - `stormify.suspending` is a single lazily-created pool, safe under concurrent
 *    first access, and `closeSuspending()` is a no-op when the pool was never used
 */
open class SuspendWithConnectionTest {

    private lateinit var stormify: Stormify
    private lateinit var runner: SuspendStormify
    private var testDbCloseHook: (() -> Unit)? = null
    private val dbNameForTest = "suspend_wc"

    @BeforeTest
    fun setup() {
        val databases = createTestDatabases()
        if (databases.isEmpty()) {
            return
        }
        val testDb = databases.first()
        testDbCloseHook = testDb.close
        stormify = Stormify(testDb.dataSource)
        stormify.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
        TestDDL.init(stormify)

        TestDDL.dropTable(dbNameForTest)
        stormify.executeUpdate(
            TestDDL.createTable(
                dbNameForTest,
                "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"
            )
        )

        runner = SuspendStormify(stormify, PoolConfig(minConnections = 0, maxConnections = 4))
    }

    @AfterTest
    fun teardown() {
        if (::runner.isInitialized) {
            runBlocking { runner.close() }
        }
        if (::stormify.isInitialized) {
            TestDDL.dropTable(dbNameForTest)
        }
        testDbCloseHook?.invoke()
        testDbCloseHook = null
    }

    private fun insert(id: Int, name: String) =
        stormify.executeUpdate("INSERT INTO $dbNameForTest (id, name) VALUES (?, ?)", id, name)

    private fun names(): List<String> =
        stormify.read("SELECT name FROM $dbNameForTest ORDER BY id")

    @Test
    fun withConnectionRunsReadsOnBorrowedConnection() = runTest {
        if (!::runner.isInitialized) return@runTest
        insert(1, "alpha")

        val found = runner.withConnection {
            stormify.read<String>("SELECT name FROM $dbNameForTest ORDER BY id")
        }
        assertEquals(listOf("alpha"), found)
    }

    @Test
    fun withConnectionInsideWithConnectionReusesConnection() = runTest {
        if (!::runner.isInitialized) return@runTest
        insert(1, "alpha")

        val found = runner.withConnection {
            runner.withConnection {
                stormify.readOne<Int>("SELECT COUNT(*) FROM $dbNameForTest")
            }
        }
        assertEquals(1, found)
    }

    @Test
    fun withConnectionInsideTransactionSeesUncommittedRows() = runTest {
        if (!::runner.isInitialized) return@runTest

        runner.transaction {
            insert(10, "uncommitted")
            // Reuse of the ambient connection is the only way this read can see the
            // not-yet-committed row — a second connection would see nothing (or block
            // on SQLite's write lock).
            val seen = runner.withConnection {
                stormify.readOne<Int>("SELECT COUNT(*) FROM $dbNameForTest WHERE id = 10")
            }
            assertEquals(1, seen)
        }
        assertEquals(listOf("uncommitted"), names())
    }

    @Test
    fun transactionInsideWithConnectionCommitsIndependently() = runTest {
        if (!::runner.isInitialized) return@runTest

        val boom = assertFailsWith<RuntimeException> {
            runner.withConnection {
                runner.transaction { insert(20, "inner-committed") }
                throw RuntimeException("outer-boom")
            }
        }
        assertEquals("outer-boom", boom.message)
        // The inner transaction ran a full BEGIN/COMMIT cycle on the borrowed
        // connection — its row survives the outer (non-transactional) failure.
        assertEquals(listOf("inner-committed"), names())
    }

    @Test
    fun transactionInsideWithConnectionRollsBackAndKeepsConnectionUsable() = runTest {
        if (!::runner.isInitialized) return@runTest

        runner.withConnection {
            val wrapped = assertFailsWith<SQLException> {
                runner.transaction {
                    insert(30, "inner-rolled-back")
                    throw RuntimeException("inner-boom")
                }
            }
            assertEquals("inner-boom", wrapped.cause?.message)
            // Auto-commit must be restored and the connection healthy after the rollback.
            insert(31, "after-rollback")
        }
        assertEquals(listOf("after-rollback"), names())
    }

    @Test
    fun applicationExceptionPassesThroughUnwrappedAndDoesNotEvict() = runTest {
        if (!::runner.isInitialized) return@runTest
        val evictedBefore = runner.stats.evictedCount

        val boom = assertFailsWith<IllegalStateException> {
            runner.withConnection {
                stormify.readOne<Int>("SELECT COUNT(*) FROM $dbNameForTest")
                throw IllegalStateException("app-level-failure")
            }
        }
        assertEquals("app-level-failure", boom.message)
        assertEquals(evictedBefore, runner.stats.evictedCount, "application exceptions must not evict connections")

        // The pool is still fully functional afterwards.
        val count = runner.withConnection { stormify.readOne<Int>("SELECT COUNT(*) FROM $dbNameForTest") }
        assertEquals(0, count)
    }

    @Test
    fun cancellationPropagatesAndEvicts() {
        if (!::runner.isInitialized) return
        // Real time, not runTest: the test dispatcher would skip the delay and the
        // block would complete before the cancel lands.
        runBlocking {
            val evictedBefore = runner.stats.evictedCount
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            var observed: Throwable? = null
            val job = launch {
                try {
                    runner.withConnection {
                        started.complete(Unit)
                        delay(60_000)
                    }
                } catch (e: Throwable) {
                    observed = e
                    throw e
                }
            }
            started.await()
            job.cancelAndJoin()

            assertTrue(observed is kotlinx.coroutines.CancellationException,
                "cancellation must propagate unwrapped, got $observed")
            assertEquals(evictedBefore + 1, runner.stats.evictedCount,
                "a cancelled borrow must evict its connection")
        }
    }

    @Test
    fun suspendingPropertyIsSinglePoolAndSafeUnderConcurrentFirstAccess() = runTest {
        if (!::stormify.isInitialized) return@runTest

        val first = stormify.suspending
        assertSame(first, stormify.suspending, "every access must return the same pool")

        val winners = coroutineScope {
            (1..16).map { async(Dispatchers.Default) { stormify.suspending } }.awaitAll()
        }
        assertTrue(winners.all { it === first }, "concurrent first access must create exactly one pool")

        stormify.closeSuspending()
    }

    @Test
    fun closeSuspendingWithoutUseDoesNotCreatePool() = runTest {
        if (!::stormify.isInitialized) return@runTest

        val fresh = Stormify(stormify.dataSource)
        // No pool exists yet — this must be a no-op, not a pool create-then-close.
        fresh.closeSuspending()

        val count = fresh.suspending.withConnection {
            fresh.readOne<Int>("SELECT COUNT(*) FROM $dbNameForTest")
        }
        assertEquals(0, count)
        fresh.closeSuspending()
    }

    @Test
    fun transactionInsideTransactionStillUsesSavepoint() = runTest {
        if (!::runner.isInitialized) return@runTest
        // Regression guard for the nesting branch on ConnectionElement.inTransaction:
        // transaction inside transaction must keep the pre-existing savepoint behavior.
        runner.transaction {
            insert(40, "outer")
            try {
                runner.transaction {
                    insert(41, "inner-fail")
                    throw RuntimeException("inner-boom")
                }
            } catch (_: SQLException) {
                // expected
            }
            insert(42, "after-inner")
        }
        assertEquals(listOf("outer", "after-inner"), names())
    }
}
