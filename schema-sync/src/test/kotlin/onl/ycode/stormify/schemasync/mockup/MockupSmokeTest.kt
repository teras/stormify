package onl.ycode.stormify.schemasync.mockup

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.schemasync.db.Dialect
import onl.ycode.stormify.schemasync.db.DriverManagerDataSource
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the mockup pipeline end-to-end against in-process SQLite
 * with the [MockupShape.SMOKE] cardinalities so the test runs in well
 * under a second. Asserts:
 *  - Every base table, view, and synonym (extra-table fallback) lands.
 *  - Row counts match `rowsPerTable`.
 *  - One Kotlin source file per [EntitySpec] is written.
 *  - Each [DivergenceFlags] axis has at least one entity carrying it.
 */
class MockupSmokeTest {

    @Test
    fun seedsSqliteWithSmokeShape() {
        Class.forName("org.sqlite.JDBC")
        val dbFile = Files.createTempFile("mockup-smoke-", ".db").toFile().apply { deleteOnExit() }
        val entitiesDir = Files.createTempDirectory("mockup-entities-").toFile().apply { deleteOnExit() }
        val stormify = Stormify(DriverManagerDataSource("jdbc:sqlite:${dbFile.absolutePath}", null, null))
        val dialect = Dialect.detect(stormify)
        assertEquals(Dialect.SQLITE, dialect)

        val spec = MockupBuilder.build(shape = MockupShape.SMOKE, rowsPerTable = 5, seed = 42L)
        MockupSeeder(stormify, dialect, entitiesDir, log = {}).seed(spec)

        val tables = stormify.read<String>(
            "SELECT name FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%'",
        ).toSet()
        val expected = (spec.tables.map { it.name } + spec.views.map { it.name } + spec.synonyms.map { it.name }).toSet()
        assertEquals(expected, tables, "every mockup object should be present in sqlite_master")

        val sample = spec.tables.first().name
        val count = stormify.readOne<Long>("SELECT COUNT(*) FROM $sample") ?: 0L
        assertEquals(spec.rowsPerTable.toLong(), count, "row count for $sample")

        val emittedFiles = File(entitiesDir, MockupEntitySource.PACKAGE.replace('.', '/'))
            .listFiles()?.toList().orEmpty()
        assertEquals(spec.entities.size, emittedFiles.size)
        for (f in emittedFiles) assertTrue(f.readText().contains("@DbTable"), "${f.name} missing @DbTable")

        // Each divergence axis carries at least one entity even at SMOKE
        // scale (the ledger guarantees one entity per quad combination).
        val nonPhantom = spec.entities.filter { it.targetKind != TargetKind.PHANTOM }
        assertTrue(nonPhantom.any { it.flags.missingDbField }, "no missingDbField at SMOKE scale")
        assertTrue(nonPhantom.any { it.flags.missingKotlinField }, "no missingKotlinField at SMOKE scale")
        assertTrue(nonPhantom.any { it.flags.typeConflict }, "no typeConflict at SMOKE scale")
        assertTrue(nonPhantom.any { it.flags.defaultConflict }, "no defaultConflict at SMOKE scale")
    }
}
