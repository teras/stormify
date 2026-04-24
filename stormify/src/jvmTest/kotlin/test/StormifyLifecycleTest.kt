// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.DataSource
import onl.ycode.stormify.EntityRegistrar
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.StormifyLifecycle
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that [Stormify]'s construction dedupes registrar invocation across
 * multiple sequential instances, and that [StormifyLifecycle.clear] resets the
 * dedupe so the next construction re-runs `register()` (webapp redeploy case).
 */
class StormifyLifecycleTest {

    /** No-op DataSource — the Stormify constructor does not touch it; only lazy paths do. */
    private val stubDs = object : DataSource {
        override fun getConnection(): Connection = error("not used in lifecycle tests")
    }

    @BeforeTest
    fun reset() = StormifyLifecycle.clear()

    @AfterTest
    fun cleanup() = StormifyLifecycle.clear()

    @Test
    fun registrar_runs_once_across_multiple_stormify_instances() {
        var count = 0
        val reg = EntityRegistrar { count++ }

        Stormify(stubDs, reg)
        Stormify(stubDs, reg)
        Stormify(stubDs, reg)

        assertEquals(1, count, "register() must run exactly once per registrar identity")
    }

    @Test
    fun clear_makes_next_construction_re_register() {
        var count = 0
        val reg = EntityRegistrar { count++ }

        Stormify(stubDs, reg)
        assertEquals(1, count)

        StormifyLifecycle.clear()

        Stormify(stubDs, reg)
        assertEquals(2, count, "register() must re-run after StormifyLifecycle.clear()")
    }

    @Test
    fun distinct_registrars_each_run_once() {
        var a = 0
        var b = 0
        val regA = EntityRegistrar { a++ }
        val regB = EntityRegistrar { b++ }

        Stormify(stubDs, regA, regB)
        Stormify(stubDs, regA, regB)

        assertEquals(1, a)
        assertEquals(1, b)
    }
}
