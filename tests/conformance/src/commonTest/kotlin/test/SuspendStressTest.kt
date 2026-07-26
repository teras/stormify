// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import onl.ycode.stormify.coroutines.PoolConfig
import onl.ycode.stormify.coroutines.SuspendStormify
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Suspend-API counterpart to [test.StressTest]. Drives the same workload (1000 simple
 * selects, 1000 ORM selects, 1000 inserts) through [onl.ycode.stormify.coroutines.SuspendStormify]
 * and its built-in connection pool, so the same invariants are exercised on every target —
 * JVM, Android, Linux/Windows/macOS native, iOS — wherever a high-concurrency database is
 * available. SQLite is skipped via [TestDDL.supportsHighConcurrency], same as the blocking variant.
 */
@OptIn(ExperimentalAtomicApi::class)
class SuspendStressTest {
    @Test
    fun testSuspendStress() = TestHelper.withDb("SUSPEND-STRESS") { s ->
        if (!TestDDL.supportsHighConcurrency()) { println("Skipping"); return@withDb }

        TestDDL.dropTable("stress_table")
        s.executeUpdate(
            TestDDL.createTable(
                "stress_table",
                "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}"
            )
        )

        // Seed id is intentionally placed outside the 1..1000 range used by the insert
        // loop below, so the loop's primary keys never collide with the seed.
        val model = StressTable(10001, "42")
        s.create(model)

        val selectExpr = TestDDL.selectExpr("( 1 + 2 ) * 3")
        repeat(1000) { assertEquals(9, s.readOne<Int>(selectExpr)) }

        val countSimple = AtomicInt(0)
        val countSelect = AtomicInt(0)
        val countInserts = AtomicInt(0)

        // Pool size matches the testing Hikari config (maximumPoolSize=16) on the JVM
        // target — the suspend pool wraps the same underlying JDBC pool, so we must not
        // ask for more connections than the upstream provider hands out.
        val async = SuspendStormify(s, PoolConfig(minConnections = 0, maxConnections = 16))
        try {
            runBlocking {
                coroutineScope {
                    repeat(1000) {
                        launch(Dispatchers.Default) {
                            async.transaction {
                                assertEquals(9, s.readOne<Int>(selectExpr))
                                countSimple.fetchAndAdd(1)
                            }
                        }
                    }
                    repeat(1000) {
                        launch(Dispatchers.Default) {
                            async.transaction {
                                val found = s.readOne<StressTable>(
                                    "SELECT * FROM stress_table WHERE id = ?", model.id
                                )!!
                                assertEquals(model.data, found.data)
                                countSelect.fetchAndAdd(1)
                            }
                        }
                    }
                    repeat(1000) {
                        launch(Dispatchers.Default) {
                            val nextId = countInserts.fetchAndAdd(1) + 1
                            async.transaction {
                                s.create(StressTable(nextId, "Index #$nextId"))
                            }
                        }
                    }
                }
            }
        } finally {
            runBlocking { async.close() }
        }

        // 1 seed row + 1000 inserted rows
        assertEquals(1001, s.readOne<Int>("SELECT COUNT(*) FROM stress_table"))
        assertEquals(1000, countSimple.load(), "Simple queries")
        assertEquals(1000, countSelect.load(), "ORM queries")
        assertEquals(1000, countInserts.load(), "Insert queries")
    }
}
