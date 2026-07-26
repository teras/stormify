package test

import onl.ycode.stormify.Stormify
import kotlin.test.*

/**
 * Edge cases of the cursor/streaming path that the higher-level
 * `forEachStreaming` tests don't reach: direct [Stormify.readCursor] usage,
 * autocommit invariants around the PG cursor toggle, streaming inside an
 * outer transaction, exception cleanup, and state retention across
 * back-to-back streaming calls.
 */
open class CursorStreamingTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    private fun seed(s: Stormify, count: Int) {
        TestDDL.dropTable("cs_test")
        s.executeUpdate(
            TestDDL.createTable(
                "cs_test",
                "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"
            )
        )
        for (i in 1..count) {
            s.executeUpdate("INSERT INTO cs_test (id, name) VALUES (?, ?)", i, "row$i")
        }
    }

    @Test
    fun readCursorEmitsAllRowsInOrder() = withDb("CS_BASIC") { s ->
        seed(s, 50)
        val seen = mutableListOf<Int>()
        s.readCursor<Map<String, Any>>("SELECT id FROM cs_test ORDER BY id") { row ->
            seen += (row["id"] as Number).toInt()
        }
        assertEquals((1..50).toList(), seen)
    }

    @Test
    fun readCursorWithExplicitFetchSizeEmitsAllRows() = withDb("CS_FETCHSIZE") { s ->
        seed(s, 50)
        val seen = mutableListOf<Int>()
        s.readCursor<Map<String, Any>>("SELECT id FROM cs_test ORDER BY id", fetchSize = 5) { row ->
            seen += (row["id"] as Number).toInt()
        }
        assertEquals((1..50).toList(), seen)
    }

    @Test
    fun readCursorOnEmptyTableInvokesConsumerZeroTimes() = withDb("CS_EMPTY") { s ->
        seed(s, 0)
        var calls = 0
        s.readCursor<Map<String, Any>>("SELECT id FROM cs_test") { calls++ }
        assertEquals(0, calls)
    }

    /**
     * After a streaming read on PG (where stormify toggles autocommit on a
     * borrowed connection to activate the cursor), the next direct write must
     * still auto-commit. A leaked autocommit=false would make `INSERT` rows
     * invisible without an explicit `commit()`.
     */
    @Test
    fun directWriteAutoCommitsAfterStreamingRead() = withDb("CS_AC_AFTER_STREAM") { s ->
        seed(s, 5)
        s.readCursor<Map<String, Any>>("SELECT id FROM cs_test ORDER BY id") { /* drain */ }
        s.executeUpdate("INSERT INTO cs_test (id, name) VALUES (?, ?)", 999, "after-stream")
        // A fresh Stormify instance shares the underlying connection pool, so
        // any leaked autocommit=false would surface here too — but we only
        // need to prove the visible row count.
        val total = s.readOne<Long>("SELECT COUNT(*) FROM cs_test")
        assertEquals(6L, total)
    }

    /**
     * Streaming inside `transaction { }` must not flip the user's autocommit
     * state: the connection is owned by the transaction (`shouldClose=false`)
     * so the PG cursor activation already holds (autocommit is already off
     * inside a transaction). Writes in the same block must commit together.
     */
    @Test
    fun streamingInsideTransactionPreservesAtomicity() = withDb("CS_IN_TXN") { s ->
        seed(s, 10)
        val seen = mutableListOf<Int>()
        s.transaction {
            s.readCursor<Map<String, Any>>("SELECT id FROM cs_test ORDER BY id") { row ->
                seen += (row["id"] as Number).toInt()
            }
            s.executeUpdate("INSERT INTO cs_test (id, name) VALUES (?, ?)", 100, "in-txn")
        }
        assertEquals((1..10).toList(), seen)
        // After commit, the inserted row is visible AND the prior 10 are intact.
        val total = s.readOne<Long>("SELECT COUNT(*) FROM cs_test")
        assertEquals(11L, total)
    }

    /**
     * A user transaction wrapping a streaming read must roll back atomically.
     * If the streaming path leaked autocommit=true onto the user's connection
     * mid-transaction, a subsequent INSERT would commit independently and the
     * rollback would leave it behind.
     */
    @Test
    fun rollbackInsideTransactionWithStreamingRevertsAllWrites() = withDb("CS_TXN_ROLLBACK") { s ->
        seed(s, 5)
        try {
            s.transaction {
                s.readCursor<Map<String, Any>>("SELECT id FROM cs_test ORDER BY id") { /* drain */ }
                s.executeUpdate("INSERT INTO cs_test (id, name) VALUES (?, ?)", 200, "should-vanish")
                throw RuntimeException("force rollback")
            }
        } catch (_: Exception) {}
        val total = s.readOne<Long>("SELECT COUNT(*) FROM cs_test")
        assertEquals(5L, total, "row 200 must roll back with the transaction")
    }

    /**
     * Exception thrown by the consumer must not leave the connection in a
     * non-autocommitting state. The next direct write has to auto-commit.
     */
    @Test
    fun consumerExceptionRestoresAutoCommit() = withDb("CS_CONSUMER_THROWS") { s ->
        seed(s, 10)
        try {
            s.readCursor<Map<String, Any>>("SELECT id FROM cs_test ORDER BY id") { row ->
                if ((row["id"] as Number).toInt() == 3) throw RuntimeException("boom")
            }
            fail("readCursor did not surface the consumer exception")
        } catch (e: Exception) {
            // Expected — consumer threw. We don't care which wrapper layer caught it.
        }
        // Direct write after the failed cursor must still auto-commit.
        s.executeUpdate("INSERT INTO cs_test (id, name) VALUES (?, ?)", 999, "after-fail")
        val total = s.readOne<Long>("SELECT COUNT(*) FROM cs_test")
        assertEquals(11L, total)
    }

    /** Two streaming reads in a row must each see the full result set. */
    @Test
    fun backToBackStreamingReadsAreIndependent() = withDb("CS_BACK_TO_BACK") { s ->
        seed(s, 8)
        val first = mutableListOf<Int>()
        s.readCursor<Map<String, Any>>("SELECT id FROM cs_test ORDER BY id") { row ->
            first += (row["id"] as Number).toInt()
        }
        val second = mutableListOf<Int>()
        s.readCursor<Map<String, Any>>("SELECT id FROM cs_test ORDER BY id") { row ->
            second += (row["id"] as Number).toInt()
        }
        assertEquals((1..8).toList(), first)
        assertEquals((1..8).toList(), second)
    }

    /**
     * A non-streaming `read<T>` issued right after `readCursor` must work —
     * the autocommit-toggle path of the cursor must not bleed into the eager
     * read's connection state.
     */
    @Test
    fun eagerReadAfterStreamingReturnsAllRows() = withDb("CS_EAGER_AFTER_STREAM") { s ->
        seed(s, 12)
        s.readCursor<Map<String, Any>>("SELECT id FROM cs_test ORDER BY id") { /* drain */ }
        val ids = s.read<Int>("SELECT id FROM cs_test ORDER BY id")
        assertEquals((1..12).toList(), ids)
    }

    /**
     * Per-call `fetchSize=0` must NOT activate streaming: drivers fall back to
     * their eager default, which still has to return every row. This guards
     * against the gating regression where `fetchSize > 0` is the streaming
     * gate — a future change that flipped the comparison would break this.
     */
    @Test
    fun zeroFetchSizeStillReturnsEveryRow() = withDb("CS_FETCH_ZERO") { s ->
        seed(s, 17)
        val seen = mutableListOf<Int>()
        s.readCursor<Map<String, Any>>(
            "SELECT id FROM cs_test ORDER BY id",
            fetchSize = 0
        ) { row -> seen += (row["id"] as Number).toInt() }
        assertEquals((1..17).toList(), seen)
    }
}
