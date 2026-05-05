package onl.ycode.stormify.schemasync.mockup

import kotlin.test.Test

/**
 * Prints the actual divergence breakdown at FULL scale so a human
 * can eyeball that every TUI filter has a non-trivial population.
 * Always passes — the budget invariants are checked separately by
 * [MockupBuilderTest]; this test exists for visibility only.
 */
class MockupBreakdownTest {

    @Test
    fun printFullShapeBreakdown() {
        val spec = MockupBuilder.build(shape = MockupShape.FULL, rowsPerTable = 0, seed = 42L)
        println()
        println("=== Mockup FULL breakdown (${spec.entities.size} entities) ===")
        println()

        // By target kind.
        val byKind = spec.entities.groupingBy { it.targetKind }.eachCount()
        println("Target kind:")
        for (k in TargetKind.values()) println("  ${k.name.padEnd(8)} ${byKind[k] ?: 0}")
        println()

        // By issue count among non-phantom.
        val nonPhantom = spec.entities.filter { it.targetKind != TargetKind.PHANTOM }
        val byCount = nonPhantom.groupingBy { it.flags.issueCount }.eachCount()
        println("Issue count (non-phantom):")
        for (n in 0..4) println("  $n issue(s)  ${byCount[n] ?: 0}")
        println()

        // Per-axis count.
        println("Per-axis carriers (any combo):")
        println("  missingDbField:     ${nonPhantom.count { it.flags.missingDbField }}")
        println("  missingKotlinField: ${nonPhantom.count { it.flags.missingKotlinField }}")
        println("  typeConflict:       ${nonPhantom.count { it.flags.typeConflict }}")
        println("  defaultConflict:    ${nonPhantom.count { it.flags.defaultConflict }}")
        println()

        // Specific combos that the TUI surfaces.
        println("Notable combos:")
        println("  exactly missingDb only:            ${countWith(nonPhantom, missingDb = true)}")
        println("  exactly missingKotlin only:        ${countWith(nonPhantom, missingKotlin = true)}")
        println("  exactly typeConflict only:         ${countWith(nonPhantom, typeC = true)}")
        println("  exactly defaultConflict only:      ${countWith(nonPhantom, defaultC = true)}")
        println("  type + default (2 issues):         ${nonPhantom.count { it.flags.typeConflict && it.flags.defaultConflict && it.flags.issueCount == 2 }}")
        println("  missingDb + type (2 issues):       ${nonPhantom.count { it.flags.missingDbField && it.flags.typeConflict && it.flags.issueCount == 2 }}")
        println("  missingDb + missingKotlin + type:  ${nonPhantom.count { it.flags.missingDbField && it.flags.missingKotlinField && it.flags.typeConflict && it.flags.issueCount == 3 }}")
        println("  all four (quad):                   ${nonPhantom.count { it.flags.issueCount == 4 }}")
        println()

        // Object-kind targeting.
        println("Targeting:")
        println("  base tables (1-1 + paired): ${nonPhantom.count { it.targetKind == TargetKind.TABLE }}")
        println("  views:                      ${nonPhantom.count { it.targetKind == TargetKind.VIEW }}")
        println("  synonyms:                   ${nonPhantom.count { it.targetKind == TargetKind.SYNONYM }}")
        println()
    }

    private fun countWith(
        list: List<EntitySpec>,
        missingDb: Boolean = false,
        missingKotlin: Boolean = false,
        typeC: Boolean = false,
        defaultC: Boolean = false,
    ): Int = list.count {
        it.flags == DivergenceFlags(
            missingDbField = missingDb,
            missingKotlinField = missingKotlin,
            typeConflict = typeC,
            defaultConflict = defaultC,
        )
    }
}
