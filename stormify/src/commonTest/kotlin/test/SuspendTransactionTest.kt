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
import kotlinx.coroutines.withContext
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.coroutines.DefaultSuspendConnectionPool
import onl.ycode.stormify.coroutines.PoolConfig
import onl.ycode.stormify.coroutines.SuspendStormify
import onl.ycode.stormify.coroutines.suspending
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Smoke tests for the stormify.coroutines suspend API.
 *
 * The goal here is to exercise the end-to-end pipeline on a real SQLite database:
 *   - pool acquisition and release
 *   - suspend transaction commit path
 *   - suspend transaction rollback path
 *   - nested transaction with savepoint
 *   - parallel coroutines each getting their own pool connection
 *
 * These are not exhaustive concurrency tests — they are sanity checks that confirm the
 * plumbing works. Stress testing belongs in a separate performance suite.
 */
class SuspendTransactionTest {

    private lateinit var stormify: Stormify
    private lateinit var pool: DefaultSuspendConnectionPool
    private lateinit var runner: SuspendStormify
    private val dbNameForTest = "suspend_tx"

    @BeforeTest
    fun setup() {
        val databases = createTestDatabases()
        if (databases.isEmpty()) {
            return
        }
        val testDb = databases.first()
        stormify = Stormify(testDb.dataSource)
        stormify.isStrictMode = false
        stormify.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
        TestDDL.init(stormify)

        TestDDL.dropTable(dbNameForTest)
        stormify.executeUpdate(
            TestDDL.createTable(
                dbNameForTest,
                "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"
            )
        )

        pool = DefaultSuspendConnectionPool(
            testDb.dataSource,
            PoolConfig(minConnections = 0, maxConnections = 4),
        )
        runner = stormify.suspending(pool)
    }

    @AfterTest
    fun teardown() {
        if (::pool.isInitialized) {
            // runBlocking bridges the suspend close() to the blocking JUnit teardown.
            // kotlinx.coroutines.runBlocking is multiplatform (JVM + Native + JS) since 1.7.
            runBlocking { pool.close() }
        }
        if (::stormify.isInitialized) {
            TestDDL.dropTable(dbNameForTest)
        }
    }

    @Test
    fun suspendTransactionCommits() = runTest {
        if (!::runner.isInitialized) return@runTest

        runner.transaction {
            executeUpdate("INSERT INTO $dbNameForTest (id, name) VALUES (?, ?)", 1, "alpha")
            executeUpdate("INSERT INTO $dbNameForTest (id, name) VALUES (?, ?)", 2, "beta")
        }

        val rows = stormify.read<String>("SELECT name FROM $dbNameForTest ORDER BY id")
        assertEquals(listOf("alpha", "beta"), rows)
    }

    @Test
    fun suspendTransactionRollsBackOnException() = runTest {
        if (!::runner.isInitialized) return@runTest

        stormify.executeUpdate("INSERT INTO $dbNameForTest (id, name) VALUES (?, ?)", 10, "seed")

        try {
            runner.transaction {
                executeUpdate("INSERT INTO $dbNameForTest (id, name) VALUES (?, ?)", 11, "should-roll-back")
                throw RuntimeException("boom")
            }
            fail("Expected exception was not thrown")
        } catch (e: Throwable) {
            // Expected — exception propagates out of suspend transaction
            assertTrue("boom" in (e.message ?: ""), "Unexpected exception: ${e.message}")
        }

        val names = stormify.read<String>("SELECT name FROM $dbNameForTest WHERE id IN (10, 11) ORDER BY id")
        assertEquals(listOf("seed"), names, "Row 11 should have been rolled back")
    }

    @Test
    fun nestedSuspendTransactionUsesSavepoint() = runTest {
        if (!::runner.isInitialized) return@runTest

        runner.transaction {
            executeUpdate("INSERT INTO $dbNameForTest (id, name) VALUES (?, ?)", 20, "outer")

            // Inner failure rolls back to savepoint, outer still commits its own row.
            try {
                runner.transaction {
                    executeUpdate("INSERT INTO $dbNameForTest (id, name) VALUES (?, ?)", 21, "inner-fail")
                    throw RuntimeException("inner-boom")
                }
            } catch (_: RuntimeException) {
                // expected
            }

            // Continue in outer transaction — savepoint rollback must not have aborted us.
            executeUpdate("INSERT INTO $dbNameForTest (id, name) VALUES (?, ?)", 22, "after-inner")
        }

        val names = stormify.read<String>("SELECT name FROM $dbNameForTest WHERE id IN (20, 21, 22) ORDER BY id")
        assertEquals(listOf("outer", "after-inner"), names)
    }

    @Test
    fun parallelTransactionsEachUseSeparateConnection() = runTest {
        if (!::runner.isInitialized) return@runTest

        // Seed a single row, then fire off 4 parallel READ transactions. Reads are used
        // instead of writes so the test works on SQLite (which serializes writers via
        // file locking). The point of the test is to verify that the pool hands out
        // multiple connections concurrently to parallel coroutines — all 4 should
        // succeed without waiting on each other.
        stormify.executeUpdate("INSERT INTO $dbNameForTest (id, name) VALUES (?, ?)", 200, "seed")

        val results = coroutineScope {
            (0 until 4).map {
                async {
                    runner.transaction {
                        readOne<String>("SELECT name FROM $dbNameForTest WHERE id = ?", 200)
                    }
                }
            }.awaitAll()
        }

        assertEquals(List(4) { "seed" }, results)

        // Sanity check on pool stats: all 4 acquires landed, no one timed out, no evictions.
        val s = pool.stats
        assertTrue(s.acquireCount >= 4, "Expected at least 4 acquires, got ${s.acquireCount}")
        assertEquals(0L, s.evictedCount, "No evictions expected on a clean pool")
    }

    @Test
    fun cancellationRollsBackTransaction() = runBlocking {
        if (!::runner.isInitialized) return@runBlocking

        // Pre-seed a row so we can prove rollback didn't touch it.
        stormify.executeUpdate("INSERT INTO $dbNameForTest (id, name) VALUES (?, ?)", 300, "seed")

        val job = launch(Dispatchers.Default) {
            runner.transaction {
                executeUpdate("INSERT INTO $dbNameForTest (id, name) VALUES (?, ?)", 301, "should-rollback")
                // Give the test body a real suspension point to observe cancellation on.
                // We are intentionally NOT testing sqlite3_interrupt here (timing-sensitive)
                // — the value tested is the coroutine cancellation unwinding the suspend
                // transaction: block throws CancellationException, rollback runs, connection
                // returns to the pool, and no partially-committed state is left behind.
                withContext(Dispatchers.Default) {
                    delay(10_000)
                }
            }
        }

        // Let the transaction start and reach its suspend point.
        delay(100)
        job.cancelAndJoin()

        // Row 301 must NOT exist.
        val cancelled = stormify.read<String>("SELECT name FROM $dbNameForTest WHERE id = 301")
        assertEquals(emptyList(), cancelled, "Row 301 should have been rolled back on cancellation")

        // Row 300 (pre-existing) unaffected.
        val preExisting = stormify.readOne<String>("SELECT name FROM $dbNameForTest WHERE id = 300")
        assertEquals("seed", preExisting)

        // The pool must still be usable — cancellation should have released the connection
        // cleanly so a fresh transaction can acquire from it.
        runner.transaction {
            executeUpdate("INSERT INTO $dbNameForTest (id, name) VALUES (?, ?)", 302, "after-cancel")
        }
        val recovered = stormify.readOne<String>("SELECT name FROM $dbNameForTest WHERE id = 302")
        assertEquals("after-cancel", recovered)
    }
}
