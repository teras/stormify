package onl.ycode.stormify.schemasync.mockup

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.schemasync.db.Dialect
import java.io.File

/**
 * Orchestrates seeding: drops every prior mockup object, recreates the
 * full set of tables/views/synonyms, inserts [MockupSpec.rowsPerTable]
 * rows per table, and writes one Kotlin entity file per [EntitySpec]
 * to [entitiesDir].
 *
 * Idempotent: running twice produces the same database. Drops are
 * scoped to objects whose names match the mockup naming conventions
 * (`t_*`, `v_*`, `syn_*`) so the seeder leaves unrelated user tables
 * alone.
 */
class MockupSeeder(
    private val stormify: Stormify,
    private val dialect: Dialect,
    private val entitiesDir: File,
    private val log: (String) -> Unit = ::println,
) {

    fun seed(spec: MockupSpec) {
        val sql = MockupSql(dialect)
        log("seeding ${dialect.name.lowercase()}: ${spec.tables.size} tables / ${spec.views.size} views / ${spec.synonyms.size} synonyms / ${spec.entities.size} entities")
        dropEverything(spec, sql)
        createTables(spec, sql)
        insertRows(spec, sql)
        createViews(spec, sql)
        createSynonymsOrExtras(spec, sql)
        writeEntities(spec)
        log("seeding done")
    }

    private fun dropEverything(spec: MockupSpec, sql: MockupSql) {
        log("  dropping prior objects (best effort)")
        // Synonyms first (they may reference base tables), then views, then tables.
        for (s in spec.synonyms) {
            sql.dropSynonym(s.name)?.let { runIgnoring(it) }
            // Also drop the equivalent fallback table on dialects without synonyms.
            runIgnoring(sql.dropTable(s.name))
        }
        for (v in spec.views) runIgnoring(sql.dropView(v.name))
        for (t in spec.tables) runIgnoring(sql.dropTable(t.name))
    }

    private fun createTables(spec: MockupSpec, sql: MockupSql) {
        log("  creating ${spec.tables.size} tables")
        for ((i, t) in spec.tables.withIndex()) {
            stormify.executeUpdate(sql.createTable(t))
            if ((i + 1) % 100 == 0) log("    ${i + 1}/${spec.tables.size}")
        }
    }

    private fun insertRows(spec: MockupSpec, sql: MockupSql) {
        if (spec.rowsPerTable <= 0) return
        log("  inserting ${spec.rowsPerTable} rows × ${spec.tables.size} tables")
        for ((i, t) in spec.tables.withIndex()) {
            for (batch in sql.insertBatches(t, spec.rowsPerTable)) {
                stormify.executeUpdate(batch)
            }
            if ((i + 1) % 50 == 0) log("    ${i + 1}/${spec.tables.size}")
        }
    }

    private fun createViews(spec: MockupSpec, sql: MockupSql) {
        log("  creating ${spec.views.size} views")
        for (v in spec.views) stormify.executeUpdate(sql.createView(v))
    }

    private fun createSynonymsOrExtras(spec: MockupSpec, sql: MockupSql) {
        log("  creating ${spec.synonyms.size} synonyms (or duplicate tables for dialects without synonym support)")
        for (s in spec.synonyms) {
            val ddl = sql.createSynonym(s)
            if (ddl != null) {
                stormify.executeUpdate(ddl)
            } else {
                // Fallback: create an empty table that mirrors the base
                // table's schema, named after the synonym. Keeps the
                // mockup's "extra DB-only objects" budget consistent.
                val baseTable = spec.tables.first { it.name == s.baseTable }
                val cloneSpec = baseTable.copy(name = s.name)
                stormify.executeUpdate(sql.createTable(cloneSpec))
            }
        }
    }

    private fun writeEntities(spec: MockupSpec) {
        log("  writing ${spec.entities.size} entity files to ${entitiesDir.absolutePath}")
        entitiesDir.mkdirs()
        MockupEntitySource.writeAll(spec, entitiesDir)
    }

    private fun runIgnoring(sql: String) {
        runCatching { stormify.executeUpdate(sql) }
    }
}
