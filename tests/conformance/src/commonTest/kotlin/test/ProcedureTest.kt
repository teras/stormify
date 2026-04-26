// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.Sp
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.spIn
import onl.ycode.stormify.spInOut
import onl.ycode.stormify.spOut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests the `stormify.procedure(...)` API on a simple test procedure
 * `(IN x INT, IN y VARCHAR, OUT z INT, INOUT w INT)` that computes
 * `z := x * 2; w := w + x;`.
 *
 * Each test exercises a different way to pass IN parameters so we verify
 * the four call-site patterns all work:
 *
 *  1. **Auto-wrap** — raw values passed to `procedure(...)` become [Sp.In]
 *     automatically (the common case, least boilerplate).
 *  2. **Explicit [spIn]** — caller wraps the value intentionally, useful when
 *     a raw value is semantically ambiguous or for consistency with
 *     [spOut] / [spInOut].
 *  3. **[spOut]** — reified Kotlin helper, read back via `out.value` (typed).
 *  4. **[spInOut]** — initial value + post-execute value, both typed.
 *
 * Skipped on dialects without stored-procedure support (SQLite).
 */
open class ProcedureTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    /** Skip with the correct category depending on the DB. */
    private fun skipStoredProcedures(s: Stormify): Nothing = when (s.sqlDialect) {
        onl.ycode.stormify.SqlDialect.SQLITE ->
            skipTest(SkipReason.DB_LIMITATION, "SQLite has no stored procedure support")
        onl.ycode.stormify.SqlDialect.POSTGRESQL ->
            skipTest(SkipReason.VERSION_LIMIT, "PostgreSQL < 11 has no CREATE PROCEDURE")
        else ->
            error("supportsStoredProcedures() returned false for ${s.sqlDialect} " +
                    "but no skip category is defined — add one explicitly")
    }

    private val procName = "kdbc_sp_test"

    private fun setupProcedure(s: Stormify) {
        TestDDL.dropProcedure(procName)
        s.executeUpdate(TestDDL.createTestProcedure(procName))
    }

    private fun cleanupProcedure() {
        TestDDL.dropProcedure(procName)
    }

    /** All four call-site patterns in one test: auto-wrap, spIn, spOut, spInOut. */
    @Test
    fun testProcedureAllPatterns() {
        withDb("PROCEDURE-ALL") { s ->
            if (!TestDDL.supportsStoredProcedures()) skipStoredProcedures(s)
            setupProcedure(s)
            try {
                val z = spOut<Int>()           // OUT reference
                val w = spInOut(10)            // INOUT starting at 10

                s.procedure(procName,
                    5,                          // (1) raw IN — auto-wrapped
                    spIn("hello"),              // (2) explicit IN wrapper
                    z,                          // (3) OUT
                    w                           // (4) INOUT
                )

                // Procedure computed: z = 5 * 2 = 10; w = 10 + 5 = 15
                assertEquals(10, z.value, "OUT z should be 5 * 2")
                assertEquals(15, w.value, "INOUT w should be 10 + 5")
                assertEquals(10, z.required, "required should match value when populated")
                assertEquals(15, w.required)
            } finally {
                cleanupProcedure()
            }
        }
    }

    /** Auto-wrap only — no explicit wrappers on IN parameters. */
    @Test
    fun testProcedureAutoWrapIn() {
        withDb("PROCEDURE-AUTO") { s ->
            if (!TestDDL.supportsStoredProcedures()) skipStoredProcedures(s)
            setupProcedure(s)
            try {
                val z = spOut<Int>()
                val w = spInOut(7)

                // Both IN parameters are passed as raw values, no wrapping
                s.procedure(procName, 3, "world", z, w)

                assertEquals(6, z.value)       // 3 * 2
                assertEquals(10, w.value)      // 7 + 3
            } finally {
                cleanupProcedure()
            }
        }
    }

    /** Explicit spIn() wrappers — same semantics as auto-wrap, verifies the API surface. */
    @Test
    fun testProcedureExplicitSpIn() {
        withDb("PROCEDURE-EXPLICIT-IN") { s ->
            if (!TestDDL.supportsStoredProcedures()) skipStoredProcedures(s)
            setupProcedure(s)
            try {
                val z = spOut<Int>()
                val w = spInOut(20)

                s.procedure(procName,
                    spIn(8),                    // explicit IN
                    spIn("goodbye"),            // explicit IN
                    z,
                    w
                )

                assertEquals(16, z.value)      // 8 * 2
                assertEquals(28, w.value)      // 20 + 8
            } finally {
                cleanupProcedure()
            }
        }
    }

    /** Verifies that spOut references are genuinely typed (compile-time check). */
    @Test
    fun testProcedureOutIsTyped() {
        withDb("PROCEDURE-TYPED-OUT") { s ->
            if (!TestDDL.supportsStoredProcedures()) skipStoredProcedures(s)
            setupProcedure(s)
            try {
                val z = spOut<Int>()           // Sp.Out<Int>
                val w = spInOut(0)             // Sp.InOut<Int>

                s.procedure(procName, 11, "hi", z, w)

                // These assignments compile because .value / .required are typed
                // as Int? / Int. If the type were Any? we'd need a cast.
                val zVal: Int? = z.value
                val zReq: Int = z.required
                val wVal: Int? = w.value
                val wReq: Int = w.required

                assertEquals(22, zVal)
                assertEquals(22, zReq)
                assertEquals(11, wVal)   // 0 + 11
                assertEquals(11, wReq)
                assertNotNull(zVal)
            } finally {
                cleanupProcedure()
            }
        }
    }
}
