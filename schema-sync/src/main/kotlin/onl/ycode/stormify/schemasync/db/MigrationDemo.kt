package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.schemasync.config.ConfigStore
import onl.ycode.stormify.schemasync.entity.DiffEngine
import onl.ycode.stormify.schemasync.entity.EntityLoader
import onl.ycode.stormify.schemasync.entity.source.EntityScanner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Non-interactive end-to-end smoke test:
 *   gradle :schema-sync:migrationDemo --args='<jdbc-url> <entities.json|src-dir> [output.sql]'
 *
 * If the second argument resolves to a directory, the PSI scanner is used.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: migrationDemo <jdbc-url> <entities.json|src-dir> [output.sql]")
        return
    }
    val url = args[0]
    val entitiesArg = Path.of(args[1])
    val output = Path.of(args.getOrElse(2) { "migration.sql" })
    val forcedDialect = args.firstOrNull { it.startsWith("--dialect=") }
        ?.substringAfter("=")?.let(Dialect::fromKey)

    val configArg = args.firstOrNull { it.startsWith("--config=") }?.substringAfter("=")
    val configPath = configArg?.let(Path::of)
        ?: Path.of(".schema-sync.toml").toAbsolutePath().takeIf { Files.exists(it) }
        ?: Files.createTempFile("schema-sync-demo-config", ".toml").also { Files.delete(it) }
    val ephemeral = configArg == null && !Files.exists(configPath)
    val store = ConfigStore(configPath)
    val (config, _, _) = store.load()

    val entities = if (entitiesArg.isDirectory()) {
        println("Scanning sources at $entitiesArg")
        EntityScanner.scan(listOf(entitiesArg)).entities
    } else {
        EntityLoader.load(entitiesArg)
    }
    println("Found ${entities.size} entities")
    DbIntrospector.connect(url, null, null).use { conn ->
        val dialect = forcedDialect ?: Dialect.detect(conn)
        println("Dialect: ${dialect.tomlKey}${if (forcedDialect != null) " (forced)" else ""}")
        val intro = DbIntrospector(conn)
        val cols = intro.listColumns()
        val byTable = cols.groupBy { listOfNotNull(it.schema, it.table).joinToString(".") }
        val tableKeys = intro.listTables().map { it.key }
        val diffs = DiffEngine.diff(entities, byTable, tableKeys)
        val stats = MigrationGenerator.generate(
            diffs = diffs,
            profile = config.slots,
            defaults = config.defaults,
            assignments = config.assignments,
            output = output,
            dialect = dialect,
        )
        println("Wrote $output")
        println("  ${stats.addedColumns} ALTER TABLE ADD COLUMN")
        println("  ${stats.createdTables} CREATE TABLE")
        println("  ${stats.unclassifiedFields} unclassified")
        println("  ${stats.orphanColumns} orphan DB columns")
    }
    if (ephemeral) Files.deleteIfExists(configPath)
}
