package onl.ycode.stormify.schemasync.mockup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-spec tests: build the FULL-shape universe (no DB) and assert the
 * cardinality budgets are honoured exactly. Cheap (only allocates ~1000
 * specs) so it ships in the default test set.
 */
class MockupBuilderTest {

    @Test
    fun fullShapeHonoursAllBudgets() {
        val spec = MockupBuilder.build(shape = MockupShape.FULL, rowsPerTable = 0, seed = 42L)
        val s = spec.shape

        assertEquals(s.baseTables, spec.tables.size, "base tables")
        assertEquals(s.views, spec.views.size, "views")
        assertEquals(s.synonyms, spec.synonyms.size, "synonyms")
        assertEquals(s.entities, spec.entities.size, "entities")

        // Target-kind budgets.
        val byKind = spec.entities.groupBy { it.targetKind }.mapValues { it.value.size }
        assertEquals(s.phantomEntities, byKind[TargetKind.PHANTOM] ?: 0, "phantom entities")
        assertEquals(s.viewMappedEntities, byKind[TargetKind.VIEW] ?: 0, "view-mapped entities")
        assertEquals(s.synonymMappedEntities, byKind[TargetKind.SYNONYM] ?: 0, "synonym-mapped entities")
        assertEquals(
            s.sharedTables * 2 + s.soloBaseEntities,
            byKind[TargetKind.TABLE] ?: 0,
            "table-mapped entities (paired + solo)",
        )

        // Divergence-flag budgets among non-phantom entities.
        val nonPhantom = spec.entities.filter { it.targetKind != TargetKind.PHANTOM }
        assertEquals(s.nonPhantomTotal, nonPhantom.size)
        val byIssueCount = nonPhantom.groupBy { it.flags.issueCount }.mapValues { it.value.size }
        assertEquals(s.syncedEntities, byIssueCount[0] ?: 0, "synced entities")
        assertEquals(s.singleIssueEntities, byIssueCount[1] ?: 0, "single-issue entities")
        assertEquals(s.doubleIssueEntities, byIssueCount[2] ?: 0, "double-issue entities")
        assertEquals(s.tripleIssueEntities, byIssueCount[3] ?: 0, "triple-issue entities")
        assertEquals(s.quadIssueEntities, byIssueCount[4] ?: 0, "quad-issue entities")

        // Each individual axis has at least one carrier in every issue-count tier ≥ 1.
        for (axis in AXES) {
            assertTrue(nonPhantom.any { axis(it.flags) }, "no entity carries ${axis.name}")
        }

        // Reproducibility: same seed → identical entity ordering.
        val again = MockupBuilder.build(shape = MockupShape.FULL, rowsPerTable = 0, seed = 42L)
        assertEquals(spec.entities.map { it.className to it.tableName },
            again.entities.map { it.className to it.tableName })

        // Pairing: every shared table has exactly 2 entities; every other
        // table-targeting entity points to a distinct table.
        val sharedHits = nonPhantom.filter { it.targetKind == TargetKind.TABLE }
            .groupBy { it.tableName }.mapValues { it.value.size }
        val pairs = sharedHits.values.count { it == 2 }
        val solos = sharedHits.values.count { it == 1 }
        assertEquals(s.sharedTables, pairs, "expected $pairs paired tables; got $pairs")
        assertEquals(s.soloBaseEntities, solos, "expected ${s.soloBaseEntities} solo tables; got $solos")
    }

    private data class FlagAxis(val name: String, val get: (DivergenceFlags) -> Boolean) {
        operator fun invoke(f: DivergenceFlags) = get(f)
    }

    private val AXES = listOf(
        FlagAxis("missingDbField") { it.missingDbField },
        FlagAxis("missingKotlinField") { it.missingKotlinField },
        FlagAxis("typeConflict") { it.typeConflict },
        FlagAxis("defaultConflict") { it.defaultConflict },
    )
}
