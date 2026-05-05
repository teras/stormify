package onl.ycode.stormify.schemasync.mockup

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.schemasync.db.DbIntrospector
import onl.ycode.stormify.schemasync.db.Dialect
import onl.ycode.stormify.schemasync.db.DriverManagerDataSource
import onl.ycode.stormify.schemasync.entity.DiffEngine
import onl.ycode.stormify.schemasync.entity.source.EntityScanner
import onl.ycode.stormify.schemasync.entity.ColumnDelta
import onl.ycode.stormify.schemasync.model.TableStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end coverage check: seed SMOKE shape into SQLite, scan the
 * emitted entity files, run [DiffEngine], and assert every TUI filter
 * bucket has at least one entry. Catches regressions where a change to
 * the mockup or to the schema-sync diff pipeline silently drops a
 * category from view (the bug the user just hit, where `In sync` had
 * zero entries because non-null primitives without DB defaults always
 * tripped a default mismatch).
 */
class MockupDiffCoverageTest {

    @Test
    fun smokeShapeCoversEveryFilterBucket() {
        Class.forName("org.sqlite.JDBC")
        val dbFile = Files.createTempFile("mockup-coverage-", ".db").toFile().apply { deleteOnExit() }
        val sourcesDir = Files.createTempDirectory("mockup-coverage-sources-").toFile().apply { deleteOnExit() }
        val stormify = Stormify(DriverManagerDataSource("jdbc:sqlite:${dbFile.absolutePath}", null, null))
        val dialect = Dialect.detect(stormify)

        val spec = MockupBuilder.build(shape = MockupShape.SMOKE, rowsPerTable = 0, seed = 42L)
        MockupSeeder(stormify, dialect, sourcesDir, log = {}).seed(spec)

        // Re-introspect the DB through the same code path schema-sync uses.
        val intro = DbIntrospector(stormify)
        val tables = intro.listTables()
        val cols = intro.listColumns()
        val dbByTable = cols.groupBy { listOfNotNull(it.schema, it.table).joinToString(".") }

        val entities = EntityScanner.scan(listOf(sourcesDir.toPath()))
        val diffs = DiffEngine.diff(entities, dbByTable, tables.map { it.key })

        val statuses = diffs.groupingBy { it.status }.eachCount()
        println("Diff statuses: $statuses")
        assertTrue(statuses[TableStatus.SYNCED]?.let { it > 0 } == true, "no SYNCED tables")
        assertTrue(statuses[TableStatus.DIFF]?.let { it > 0 } == true, "no DIFF tables")
        assertTrue(statuses[TableStatus.ENTITY_ONLY]?.let { it > 0 } == true, "no ENTITY_ONLY tables")
        assertTrue(statuses[TableStatus.DB_ONLY]?.let { it > 0 } == true, "no DB_ONLY tables")

        val deltaKinds = diffs.flatMap { it.columnDeltas }.groupingBy { it.kind }.eachCount()
        val defaultMismatches = diffs.flatMap { it.columnDeltas }.count { it.defaultMismatch != null }
        println("Column delta kinds: $deltaKinds; defaultMismatches: $defaultMismatches")
        assertTrue(deltaKinds[ColumnDelta.Kind.SYNCED]?.let { it > 0 } == true, "no SYNCED columns")
        assertTrue(deltaKinds[ColumnDelta.Kind.ENTITY_ONLY]?.let { it > 0 } == true, "no ENTITY_ONLY columns (Missing DB fields)")
        assertTrue(deltaKinds[ColumnDelta.Kind.DB_ONLY]?.let { it > 0 } == true, "no DB_ONLY columns (Missing Kotlin fields)")
        assertTrue(deltaKinds[ColumnDelta.Kind.TYPE_MISMATCH]?.let { it > 0 } == true, "no TYPE_MISMATCH columns")
        assertTrue(defaultMismatches > 0, "no defaultMismatch columns")
    }
}
