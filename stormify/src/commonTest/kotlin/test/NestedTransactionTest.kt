// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Deep nesting / savepoint stress tests. The existing TransactionTest covers the
 * basic "outer commits, inner rolls back" case; this file pushes the ambient
 * tracker (`ActiveTxRegistry`) and the savepoint implementation through every
 * edge case we could think of:
 *
 *  - 5-level deep nesting with mixed commit/rollback at each level.
 *  - Rollback at an intermediate level propagates only to its savepoint.
 *  - Nested call to a helper function that opens its own `transaction { }`.
 *  - Rolling back the outer from inside a nested block.
 *  - Many sequential nested transactions inside a single outer one (stack
 *    growth/shrink).
 *  - Value-returning nested transactions.
 */
open class NestedTransactionTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    private fun seed(s: Stormify) {
        TestDDL.dropTable("nt_test")
        s.executeUpdate(TestDDL.createTable("nt_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
    }

    private fun names(s: Stormify): List<String> =
        s.read<String>("SELECT name FROM nt_test ORDER BY id")

    private fun Stormify.insert(id: Int, name: String) =
        executeUpdate("INSERT INTO nt_test (id, name) VALUES (?, ?)", id, name)

    @Test
    fun fiveLevelDeepAllCommit() = withDb("NT-5L-COMMIT") { s ->
        seed(s)
        s.transaction {
            s.insert(1, "L1")
            s.transaction {
                s.insert(2, "L2")
                s.transaction {
                    s.insert(3, "L3")
                    s.transaction {
                        s.insert(4, "L4")
                        s.transaction {
                            s.insert(5, "L5")
                        }
                    }
                }
            }
        }
        assertEquals(listOf("L1", "L2", "L3", "L4", "L5"), names(s))
    }

    @Test
    fun fiveLevelDeepInnermostRollback() = withDb("NT-5L-L5-ROLLBACK") { s ->
        seed(s)
        s.transaction {
            s.insert(1, "L1")
            s.transaction {
                s.insert(2, "L2")
                s.transaction {
                    s.insert(3, "L3")
                    s.transaction {
                        s.insert(4, "L4")
                        try {
                            s.transaction {
                                s.insert(5, "L5-ghost")
                                throw RuntimeException("abort-L5")
                            }
                        } catch (_: RuntimeException) {}
                        // L4 still in play after L5 rolled back.
                        s.insert(6, "L4-after")
                    }
                }
            }
        }
        assertEquals(listOf("L1", "L2", "L3", "L4", "L4-after"), names(s))
    }

    @Test
    fun middleLevelRollbackKeepsOuterIntact() = withDb("NT-MID-ROLLBACK") { s ->
        seed(s)
        s.transaction {
            s.insert(1, "outer-pre")
            try {
                s.transaction {
                    s.insert(2, "mid-pre")
                    s.transaction {
                        s.insert(3, "inner")
                    }
                    s.insert(4, "mid-post")
                    throw RuntimeException("abort-mid")
                }
            } catch (_: RuntimeException) {}
            s.insert(5, "outer-post")
        }
        // The mid savepoint rolled back, removing ids 2, 3, 4.
        // Outer kept 1 and 5.
        assertEquals(listOf("outer-pre", "outer-post"), names(s))
    }

    @Test
    fun outerRollbackFromWithinNested() = withDb("NT-OUTER-ROLLBACK") { s ->
        seed(s)
        assertFailsWith<RuntimeException> {
            s.transaction {
                s.insert(1, "outer")
                s.transaction {
                    s.insert(2, "inner")
                    // Intentionally not caught — propagates through the nested
                    // savepoint into the outer, which rolls back wholesale.
                    throw RuntimeException("outer-boom")
                }
            }
        }
        // Both rows gone — the outer tx rolled back in full.
        assertEquals(emptyList(), names(s))
    }

    @Test
    fun helperFunctionOpensNestedTransaction() = withDb("NT-HELPER") { s ->
        seed(s)

        // A "helper" that opens its own transaction. When called from inside
        // an active outer tx, it must produce a savepoint; when called stand-alone,
        // a top-level tx.
        fun auditAndInsert(id: Int, name: String): Unit = s.transaction {
            s.insert(id, name)
            s.insert(id + 100, "audit-$name")
        }

        // Stand-alone call — top-level tx.
        auditAndInsert(1, "alone")
        assertEquals(listOf("alone", "audit-alone"), names(s))

        // Nested call — savepoint inside an outer tx that rolls back.
        assertFailsWith<RuntimeException> {
            s.transaction {
                s.insert(10, "outer")
                auditAndInsert(11, "helper")   // becomes savepoint
                throw RuntimeException("abort-outer")
            }
        }
        // Outer rolled back — helper's savepoint work gone too.
        assertEquals(listOf("alone", "audit-alone"), names(s))

        // Nested call where only the helper fails — outer survives.
        s.transaction {
            s.insert(20, "keeps-going")
            try {
                s.transaction {
                    auditAndInsert(21, "helper-fails")  // innermost savepoint
                    throw RuntimeException("abort-helper")
                }
            } catch (_: RuntimeException) {}
            s.insert(22, "after-helper")
        }
        // Order by id: 1 (alone), 20 (keeps-going), 22 (after-helper), 101 (audit-alone).
        assertEquals(
            listOf("alone", "keeps-going", "after-helper", "audit-alone"),
            names(s)
        )
    }

    @Test
    fun manySequentialSavepointsInsideOuter() = withDb("NT-SEQ") { s ->
        seed(s)
        s.transaction {
            s.insert(1, "outer")
            // 50 sequential savepoint open/close pairs — pushes the ambient
            // stack through a lot of churn in a single thread-local entry.
            repeat(50) { i ->
                s.transaction {
                    s.insert(100 + i, "sp-$i")
                }
            }
            // And 50 that roll back individually — outer still commits.
            repeat(50) { i ->
                try {
                    s.transaction {
                        s.insert(500 + i, "sp-fail-$i")
                        throw RuntimeException("reject-$i")
                    }
                } catch (_: RuntimeException) {}
            }
        }
        // Outer + 50 successful sp writes; 50 rejected ones rolled back.
        val surviving = names(s)
        assertEquals(51, surviving.size)
        assertEquals("outer", surviving.first())
    }

    @Test
    fun nestedTransactionReturnsValueThroughOuter() = withDb("NT-RETURN") { s ->
        seed(s)
        s.insert(1, "one")
        s.insert(2, "two")

        val total: Int = s.transaction {
            s.insert(3, "three")
            val inner: Int = s.transaction {
                s.insert(4, "four")
                s.readOne<Int>("SELECT COUNT(*) FROM nt_test") ?: -1
            }
            assertEquals(4, inner)
            // Value propagates out of the savepoint, outer commits it.
            inner + 1
        }
        assertEquals(5, total)
        assertEquals(listOf("one", "two", "three", "four"), names(s))
    }

    @Test
    fun readsSeeInFlightWritesAcrossNestingLevels() = withDb("NT-VISIBILITY") { s ->
        seed(s)
        s.transaction {
            s.insert(1, "outer")
            assertEquals(1, s.readOne<Int>("SELECT COUNT(*) FROM nt_test"))
            s.transaction {
                s.insert(2, "inner")
                // Ambient read inside inner must see both rows (same connection).
                assertEquals(2, s.readOne<Int>("SELECT COUNT(*) FROM nt_test"))
                s.transaction {
                    s.insert(3, "inner2")
                    assertEquals(3, s.readOne<Int>("SELECT COUNT(*) FROM nt_test"))
                }
                // After inner2 savepoint released, its write still visible
                // to the outer's connection.
                assertEquals(3, s.readOne<Int>("SELECT COUNT(*) FROM nt_test"))
            }
            assertEquals(3, s.readOne<Int>("SELECT COUNT(*) FROM nt_test"))
        }
        assertEquals(listOf("outer", "inner", "inner2"), names(s))
    }

    @Test
    fun nestedAfterRollbackContinues() = withDb("NT-CONTINUE-AFTER-FAIL") { s ->
        seed(s)
        s.transaction {
            s.insert(1, "a")
            try {
                s.transaction {
                    s.insert(2, "b-ghost")
                    throw RuntimeException("drop-b")
                }
            } catch (_: RuntimeException) {}
            assertNull(s.readOne<String>("SELECT name FROM nt_test WHERE id = 2"))
            // Outer must still be usable — and a *new* savepoint right after
            // the failed one must succeed independently.
            s.transaction {
                s.insert(3, "c")
            }
            s.insert(4, "d")
        }
        assertEquals(listOf("a", "c", "d"), names(s))
    }
}
