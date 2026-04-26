// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.coroutines.PoolConfig
import onl.ycode.stormify.coroutines.SuspendStormify
import onl.ycode.stormify.coroutines.suspending
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stress-tests ambient-tx propagation for suspend transactions on the JVM.
 *
 * Every multi-hop test records the set of thread names where the sync
 * convenience calls actually executed. If the set has size 1 we cannot
 * distinguish a working implementation from a lucky one — the test fails
 * explicitly with a "did not stress thread migration" error instead of
 * silently passing. This guarantees the suite is meaningful every run.
 *
 * JVM-only on purpose: thread-name inspection is not multiplatform, and the
 * coroutine runtime only migrates threads aggressively on the JVM IO/Default
 * dispatchers.
 */
open class SuspendAmbientTxTest {

    private lateinit var stormify: Stormify
    private lateinit var runner: SuspendStormify
    private var testDbCloseHook: (() -> Unit)? = null
    private val table = "sa_tx"

    @BeforeTest
    fun setup() {
        val databases = createTestDatabases()
        if (databases.isEmpty()) return
        val testDb = databases.first()
        testDbCloseHook = testDb.close
        stormify = Stormify(testDb.dataSource)
        stormify.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
        TestDDL.init(stormify)
        TestDDL.dropTable(table)
        stormify.executeUpdate(
            TestDDL.createTable(table,
                "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}")
        )
        runner = stormify.suspending(PoolConfig(minConnections = 0, maxConnections = 4))
    }

    @AfterTest
    fun teardown() {
        if (::runner.isInitialized) runBlocking { runner.close() }
        if (::stormify.isInitialized) TestDDL.dropTable(table)
        testDbCloseHook?.invoke()
        testDbCloseHook = null
    }

    private fun requireMigration(threads: Set<String>, tag: String) {
        assertTrue(
            threads.size >= 2,
            "$tag did not stress thread migration — every call ran on a single " +
                    "thread ($threads). The ambient propagation path was not exercised. " +
                    "Increase the hop count or add dispatcher variety."
        )
    }

    /**
     * Sync call after a plain `delay` — the simplest resumption path. On the
     * IO dispatcher the continuation may land on a different pool thread
     * (thread-safety of ambient is the concern). This test stays permissive
     * about migration because single-thread continuations are a valid path,
     * but still verifies rollback covers both writes.
     */
    @Test
    fun syncCallAfterDelayStillJoinsTransaction() = runBlocking {
        if (!::runner.isInitialized) return@runBlocking

        assertFailsWith<RuntimeException> {
            runner.transaction {
                stormify.executeUpdate("INSERT INTO $table (id, name) VALUES (?, ?)", 500, "pre-delay")
                delay(50)
                stormify.executeUpdate("INSERT INTO $table (id, name) VALUES (?, ?)", 501, "post-delay")
                throw RuntimeException("abort")
            }
        }
        assertNull(stormify.readOne<String>("SELECT name FROM $table WHERE id = 500"))
        assertNull(stormify.readOne<String>("SELECT name FROM $table WHERE id = 501"))
    }

    /**
     * Explicit dispatcher switch through a *dedicated* single-thread context —
     * guarantees a different OS thread regardless of whether `Dispatchers.IO`
     * and `Dispatchers.Default` happen to share workers (they do, since
     * kotlinx-coroutines 1.6). Using `Dispatchers.Default` directly would make
     * the thread-migration observation non-deterministic under load.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun syncCallAfterWithContextStillJoinsTransaction() = runBlocking {
        if (!::runner.isInitialized) return@runBlocking

        val forcedOther = newSingleThreadContext("stormify-test-forced-other")
        try {
            val threads = ConcurrentHashMap.newKeySet<String>()
            assertFailsWith<RuntimeException> {
                runner.transaction {
                    threads += Thread.currentThread().name
                    stormify.executeUpdate("INSERT INTO $table (id, name) VALUES (?, ?)", 510, "pre-switch")
                    withContext(forcedOther) {
                        threads += Thread.currentThread().name
                        stormify.executeUpdate("INSERT INTO $table (id, name) VALUES (?, ?)", 511, "in-switch")
                    }
                    threads += Thread.currentThread().name
                    stormify.executeUpdate("INSERT INTO $table (id, name) VALUES (?, ?)", 512, "post-switch")
                    throw RuntimeException("abort")
                }
            }
            requireMigration(threads, "syncCallAfterWithContextStillJoinsTransaction")
            assertNull(stormify.readOne<String>("SELECT name FROM $table WHERE id = 510"))
            assertNull(stormify.readOne<String>("SELECT name FROM $table WHERE id = 511"))
            assertNull(stormify.readOne<String>("SELECT name FROM $table WHERE id = 512"))
        } finally {
            forcedOther.close()
        }
    }

    /**
     * 20 alternating hops between two dedicated single-thread contexts —
     * guarantees every hop lands on a different OS thread from its neighbour.
     * Every hop records its thread; at the end we assert we saw >= 2 distinct
     * ones and every write rolled back.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun manyDispatcherHopsAllJoinSameTransaction() = runBlocking {
        if (!::runner.isInitialized) return@runBlocking

        val ctxA = newSingleThreadContext("stormify-test-hop-a")
        val ctxB = newSingleThreadContext("stormify-test-hop-b")
        try {
            val hopCount = 20
            val threads = ConcurrentHashMap.newKeySet<String>()
            assertFailsWith<RuntimeException> {
                runner.transaction {
                    for (i in 0 until hopCount) {
                        val ctx = if (i % 2 == 0) ctxA else ctxB
                        withContext(ctx) {
                            threads += Thread.currentThread().name
                            stormify.executeUpdate(
                                "INSERT INTO $table (id, name) VALUES (?, ?)",
                                600 + i, "hop-$i"
                            )
                        }
                    }
                    throw RuntimeException("abort")
                }
            }
            requireMigration(threads, "manyDispatcherHopsAllJoinSameTransaction")
            val survivors = stormify.read<String>(
                "SELECT name FROM $table WHERE id BETWEEN 600 AND ${600 + hopCount} ORDER BY id"
            )
            assertEquals(
                emptyList(),
                survivors,
                "All $hopCount hopped writes must roll back; any survivor proves a hop escaped the tx."
            )
        } finally {
            ctxA.close()
            ctxB.close()
        }
    }

    /**
     * Writes interleaved with reads through the default-instance path. After
     * each write we read back on a Default-dispatcher thread and expect to
     * see the in-flight row. If ambient had gaps on a post-delay thread,
     * the read would miss the row (separate auto-commit connection).
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun defaultInstanceReadsSeeInFlightWritesAcrossHops() = runBlocking {
        if (!::runner.isInitialized) return@runBlocking

        val forcedOther = newSingleThreadContext("stormify-test-read-other")
        try {
            val threads = ConcurrentHashMap.newKeySet<String>()
            assertFailsWith<RuntimeException> {
                runner.transaction {
                    for (i in 0 until 10) {
                        threads += Thread.currentThread().name
                        stormify.executeUpdate(
                            "INSERT INTO $table (id, name) VALUES (?, ?)",
                            700 + i, "inflight-$i"
                        )
                        val sameThread = stormify.readOne<String>(
                            "SELECT name FROM $table WHERE id = ?", 700 + i
                        )
                        assertNotNull(sameThread, "Baseline: ambient read on same thread must see pre-commit row (hop $i)")

                        delay(5)
                        withContext(forcedOther) {
                            threads += Thread.currentThread().name
                            val seen = stormify.readOne<String>(
                                "SELECT name FROM $table WHERE id = ?", 700 + i
                            )
                            assertNotNull(seen, "Ambient read on hop $i failed to see the pre-commit row")
                            assertEquals("inflight-$i", seen)
                        }
                    }
                    throw RuntimeException("abort")
                }
            }
            requireMigration(threads, "defaultInstanceReadsSeeInFlightWritesAcrossHops")
        } finally {
            forcedOther.close()
        }
    }

    /**
     * Parallel `async` coroutines each running their own transaction. Each
     * gets a pool connection; each block's ambient must stay isolated.
     * With a 4-connection pool and 4 parallel coroutines, the runtime will
     * naturally engage multiple threads — the strongest migration stressor.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun parallelTransactionsHaveIsolatedAmbients() = runBlocking {
        if (!::runner.isInitialized) return@runBlocking

        stormify.executeUpdate("INSERT INTO $table (id, name) VALUES (?, ?)", 800, "baseline")

        val dedicated = List(4) { newSingleThreadContext("stormify-test-parallel-$it") }
        try {
            val threads = ConcurrentHashMap.newKeySet<String>()
            val observed = coroutineScope {
                (0 until 4).map { i ->
                    async(Dispatchers.IO) {
                        runner.transaction {
                            delay((5 - i).toLong())
                            withContext(dedicated[i]) {
                                threads += Thread.currentThread().name
                                stormify.readOne<String>("SELECT name FROM $table WHERE id = 800")
                            }
                        }
                    }
                }.awaitAll()
            }

            requireMigration(threads, "parallelTransactionsHaveIsolatedAmbients")
            assertEquals(List(4) { "baseline" }, observed)
        } finally {
            dedicated.forEach { it.close() }
        }
    }

    /**
     * Back-to-back transactions with dispatcher hops; after each one the
     * ambient must be fully cleared. Guards against leaked stack entries on
     * a thread that could bleed into subsequent work.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun ambientClearedAfterTransactionReturns() = runBlocking {
        if (!::runner.isInitialized) return@runBlocking

        val forcedOther = newSingleThreadContext("stormify-test-cleared-other")
        try {
            val threads = ConcurrentHashMap.newKeySet<String>()
            repeat(5) { round ->
                runner.transaction {
                    threads += Thread.currentThread().name
                    stormify.executeUpdate(
                        "INSERT INTO $table (id, name) VALUES (?, ?)",
                        900 + round, "tx-$round"
                    )
                    withContext(forcedOther) {
                        threads += Thread.currentThread().name
                        delay(2)
                    }
                }
                // After transaction returns, this direct write is outside any tx.
                stormify.executeUpdate(
                    "INSERT INTO $table (id, name) VALUES (?, ?)",
                    950 + round, "auto-$round"
                )
            }

            requireMigration(threads, "ambientClearedAfterTransactionReturns")
            val rows = stormify.read<String>(
                "SELECT name FROM $table WHERE id BETWEEN 900 AND 960 ORDER BY id"
            )
            assertEquals(10, rows.size)
        } finally {
            forcedOther.close()
        }
    }
}
