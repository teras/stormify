package onl.ycode.stormify.schemasync

import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.googlecode.lanterna.terminal.MouseCaptureMode
import onl.ycode.stormify.schemasync.classifier.SchemaClassifier
import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.config.ConfigStore
import onl.ycode.stormify.schemasync.config.ConnectionConfig
import onl.ycode.stormify.schemasync.db.DbIntrospector
import onl.ycode.stormify.schemasync.db.Dialect
import onl.ycode.stormify.schemasync.entity.DiffEngine
import onl.ycode.stormify.schemasync.entity.EntityLoader
import onl.ycode.stormify.schemasync.entity.source.EntityScanner
import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.tui.Symbols
import onl.ycode.stormify.schemasync.tui.buildTableEntries
import onl.ycode.stormify.schemasync.tui.groupByTable
import onl.ycode.stormify.schemasync.tui.SchemaSyncOutcome
import onl.ycode.stormify.schemasync.tui.runSchemaSync
import java.nio.file.Path

private const val USAGE = """Usage: schema-sync [options]

Options:
  --url <jdbc-url>       JDBC URL (e.g. jdbc:postgresql://host:5432/db).
                         Persisted to .schema-sync.toml after first use.
  --user <user>          DB user. Persisted to .schema-sync.toml.
  --password <password>  DB password. Persisted to .schema-sync.toml.
  --entities <path>      Path to entities JSON.
  --sources <dir>        Path to a Kotlin source root. Scans recursively for
                         entities (classes with @DbTable or property `id`).
                         May be repeated. Takes precedence over --entities.
  --ascii                Render the TUI using only ASCII characters.
  -h, --help             Show this help and exit.

Supported JDBC URLs (drivers bundled):
  jdbc:sqlite:<path>
  jdbc:postgresql://<host>[:<port>]/<db>
  jdbc:mysql://<host>[:<port>]/<db>
  jdbc:mariadb://<host>[:<port>]/<db>
  jdbc:oracle:thin:@<host>:<port>:<sid>
  jdbc:sqlserver://<host>[:<port>];databaseName=<db>
"""

fun main(args: Array<String>) {
    if (args.any { it == "-h" || it == "--help" }) {
        print(USAGE)
        return
    }
    Symbols.ascii = args.any { it == "--ascii" }

    val store = ConfigStore.forCurrentDirectory()
    val configResult = store.load()
    val state = ConfigState(configResult.config, store)

    val cliUrl = argValue(args, "--url")
    val cliUser = argValue(args, "--user")
    val cliPassword = argValue(args, "--password")
    if (cliUrl != null || cliUser != null || cliPassword != null) {
        val current = state.current.connection
        state.update { cfg ->
            cfg.copy(
                connection = ConnectionConfig(
                    url = cliUrl ?: current?.url ?: error("--url is required when first configuring a connection"),
                    user = cliUser ?: current?.user,
                    password = cliPassword ?: current?.password,
                ),
            )
        }
    }

    val connection = state.current.connection
        ?: run {
            System.err.println("No DB connection configured. Pass --url <jdbc-url> (see --help).")
            return
        }

    val classifier = SchemaClassifier().apply {
        seed(state.current.seeds)
        state.current.assignments.forEach { a ->
            train(a.category, a.slot, a.column.substringAfterLast('.'))
        }
    }

    val sourceRoots = argValuesAll(args, "--sources").map(Path::of)
    val entitiesJsonPath = argValue(args, "--entities")

    val factory = DefaultTerminalFactory()
        .setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE)
    val terminal = factory.createTerminal()
    val screen: Screen = TerminalScreen(terminal)
    screen.startScreen()
    try {
        var outcome: SchemaSyncOutcome
        do {
            val (columns, tableKeys, dialect) = loadColumnsAndTables(connection)
            val columnsByTable = groupByTable(columns)
            val effectiveTableKeys = tableKeys.ifEmpty { columnsByTable.keys.toList() }
            val entities = when {
                sourceRoots.isNotEmpty() -> EntityScanner.scan(sourceRoots).entities
                entitiesJsonPath != null -> EntityLoader.load(Path.of(entitiesJsonPath))
                else -> emptyList()
            }
            val diffs = DiffEngine.diff(entities, columnsByTable, effectiveTableKeys)
            val diffsByTable = diffs.associateBy { it.tableKey }
            val tables = buildTableEntries(diffs)
            outcome = runSchemaSync(
                screen = screen,
                configState = state,
                classifier = classifier,
                columnsToClassify = columns.filter { it.category != null },
                tables = tables,
                diffsByTable = diffsByTable,
                entities = entities,
                dialect = dialect,
                title = "Stormify Schema Sync — ${connection.url}",
            )
        } while (outcome == SchemaSyncOutcome.RESCAN)
    } finally {
        screen.stopScreen()
        classifier.close()
    }
}

private data class IntrospectionResult(
    val columns: List<ColumnRef>,
    val tableKeys: List<String>,
    val dialect: Dialect,
)

private fun loadColumnsAndTables(connection: ConnectionConfig): IntrospectionResult =
    DbIntrospector.connect(connection.url, connection.user, connection.password).use { jdbc ->
        val intro = DbIntrospector(jdbc)
        val keys = intro.listTables().map { it.key }
        val cols = intro.listColumns { done, total ->
            if (total > 20) System.err.print("\rIntrospecting $done/$total tables...")
        }
        if (keys.size > 20) System.err.println()
        IntrospectionResult(cols, keys, Dialect.detect(jdbc))
    }

private fun argValue(args: Array<String>, name: String): String? {
    val idx = args.indexOfFirst { it == name || it.startsWith("$name=") }
    if (idx < 0) return null
    val arg = args[idx]
    if (arg.contains('=')) return arg.substringAfter('=')
    val next = args.getOrNull(idx + 1) ?: return null
    // Don't swallow another flag as this flag's value.
    if (next.startsWith("--") || next.startsWith("-")) return null
    return next
}

private fun argValuesAll(args: Array<String>, name: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == name && i + 1 < args.size -> { out += args[i + 1]; i += 2; continue }
            a.startsWith("$name=") -> { out += a.substringAfter('='); i += 1; continue }
            else -> i += 1
        }
    }
    return out
}
