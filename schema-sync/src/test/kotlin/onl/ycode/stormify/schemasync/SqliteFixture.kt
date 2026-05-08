package onl.ycode.stormify.schemasync

import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.config.ConfigStore
import onl.ycode.stormify.schemasync.config.ConnectionConfig
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Bundles an in-temp-dir SQLite DB + Kotlin source root + ConfigState, with
 * terse `runHeadless` wrappers so tests focus on assertions rather than
 * plumbing. [ddl] is split on `;` and executed; [entities] are written into
 * `src/main/kotlin/demo/` as `<key>.kt` files.
 */
internal class SqliteFixture(
    val tmp: Path,
    ddl: String,
    entities: Map<String, String> = emptyMap(),
) {
    val dbFile: Path = tmp.resolve("test.db")
    val srcRoot: Path = tmp.resolve("src/main/kotlin")
        .also { Files.createDirectories(it.resolve("demo")) }
    val state: ConfigState

    init {
        if (ddl.isNotBlank()) {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { c ->
                c.createStatement().use { s ->
                    for (stmt in ddl.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
                        s.execute(stmt)
                    }
                }
            }
        } else {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").close()
        }
        for ((className, body) in entities) {
            Files.writeString(srcRoot.resolve("demo/$className.kt"), body)
        }
        state = makeStateWithSqlite(tmp, dbFile)
    }

    fun run(
        sqlOut: Path? = null,
        ktOut: Path? = null,
        categories: String? = null,
        entityFilters: List<String> = emptyList(),
        propertyFilters: List<String> = emptyList(),
    ): Int {
        val args = buildList {
            sqlOut?.let { add("--export-sql"); add(it.toString()) }
            ktOut?.let { add("--export-kt"); add(it.toString()) }
            categories?.let { add("--categories"); add(it) }
            for (f in entityFilters) { add("--filter-entity"); add(f) }
            for (f in propertyFilters) { add("--filter-property"); add(f) }
            add("--sources"); add(srcRoot.toString())
        }
        return runHeadless(args.toTypedArray(), state)
    }

    fun exportSql(
        categories: String? = null,
        entityFilters: List<String> = emptyList(),
        propertyFilters: List<String> = emptyList(),
    ): String {
        val out = tmp.resolve("migration-${counter++}.sql")
        val rc = run(sqlOut = out, categories = categories,
            entityFilters = entityFilters, propertyFilters = propertyFilters)
        assertEquals(0, rc, "runHeadless --export-sql returned $rc")
        return Files.readString(out)
    }

    fun exportKt(
        categories: String? = null,
        entityFilters: List<String> = emptyList(),
        propertyFilters: List<String> = emptyList(),
    ): String {
        val out = tmp.resolve("schema-${counter++}.patch")
        val rc = run(ktOut = out, categories = categories,
            entityFilters = entityFilters, propertyFilters = propertyFilters)
        assertEquals(0, rc, "runHeadless --export-kt returned $rc")
        return Files.readString(out)
    }

    private var counter = 0

    companion object {
        fun empty(tmp: Path): SqliteFixture = SqliteFixture(tmp, ddl = "")
    }
}

internal fun makeStateWithSqlite(projectDir: Path, dbFile: Path): ConfigState {
    val tomlPath = projectDir.resolve(".schema-sync.toml")
    val store = ConfigStore(tomlPath)
    val loaded = store.load().config
    val withConn = loaded.copy(
        connection = ConnectionConfig(url = "jdbc:sqlite:${dbFile.toAbsolutePath()}"),
    )
    return ConfigState(withConn, store).also { it.update { withConn } }
}
