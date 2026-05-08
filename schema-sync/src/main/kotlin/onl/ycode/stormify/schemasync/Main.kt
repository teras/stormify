package onl.ycode.stormify.schemasync

import com.googlecode.lanterna.gui2.MultiWindowTextGUI
import com.googlecode.lanterna.gui2.dialogs.MessageDialog
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.googlecode.lanterna.terminal.MouseCaptureMode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import onl.ycode.stormify.schemasync.classifier.ClassificationCache
import onl.ycode.stormify.schemasync.classifier.SchemaClassifier
import onl.ycode.stormify.schemasync.classifier.autoFillCache
import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.config.ConfigStore
import onl.ycode.stormify.schemasync.config.ConnectionConfig
import onl.ycode.stormify.schemasync.db.DbIntrospector
import onl.ycode.stormify.schemasync.db.Dialect
import onl.ycode.stormify.schemasync.entity.DiffEngine
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
  --sources <dir>        Path to a Kotlin source root. Scans recursively for
                         entities (classes annotated with @DbTable, @Table,
                         @Entity, or carrying any @DbField / @Id property).
                         May be repeated.
  --ascii                Render the TUI using only ASCII characters.
  -h, --help             Show this help and exit.

Headless / CI mode (no TUI; activated by either --export-* flag):
  --export-sql <file>    Write SQL migration to <file>.
  --export-kt <file>     Write a unified-diff patch (apply with `git apply`)
                         covering Kotlin splices and brand-new entity files.
                         Requires at least one --sources <dir>.
  --categories <list>    Comma-separated category tokens. Tokens:
                           tables, views,
                           in-sync, missing-db, missing-kt,
                           type-conflicts, default-conflicts,
                           both, db-only, entity-only,
                           single, multi.
                         Or `all` to select every token.
                         Default: every token except in-sync.
  --filter-entity <glob> Glob (case-insensitive, `*`/`?`) matching tableKey
                         or class name. Repeatable; matches any (OR).
  --filter-property <glob>
                         Glob matching column name. Repeatable; matches any.

Supported JDBC URLs (drivers bundled):
  jdbc:sqlite:<path>
  jdbc:postgresql://<host>[:<port>]/<db>
  jdbc:mysql://<host>[:<port>]/<db>
  jdbc:mariadb://<host>[:<port>]/<db>
  jdbc:oracle:thin:@<host>:<port>:<sid>
  jdbc:sqlserver://<host>[:<port>];databaseName=<db>
"""

fun main(args: Array<String>) {
    // Driver tunings that need to be in place before the JDBC class loader
    // initialises a Driver instance (Oracle reads its defaultRowPrefetch
    // when the OracleDriver class loads, not per-connection). Bumping from
    // the JDBC default of 10 to 1000 brings introspection over the data
    // dictionary down from minutes to seconds on remote schemas — and
    // matches the cursorFetchSize stormify hands to setFetchSize, so the
    // server-side and client-side batch sizes line up. setProperty only
    // takes effect if the property isn't already set, so a caller-supplied
    // -D override at the JVM command line still wins.
    if (System.getProperty("oracle.jdbc.defaultRowPrefetch") == null)
        System.setProperty("oracle.jdbc.defaultRowPrefetch", "1000")

    // Nuke java.util.logging entirely. Lucene's static-initialiser warnings
    // (Vector API capability check, HotspotVMOptions probe) emit at WARNING
    // through jul before our log-level filters can take effect on the
    // already-attached default ConsoleHandler. The TUI hijacks the screen
    // anyway, so any jul output would just paint over the rendered frame
    // and trigger the "Warnings" modal. Reset the LogManager to drop all
    // handlers, then raise the root level so nothing is emitted.
    java.util.logging.LogManager.getLogManager().reset()
    java.util.logging.Logger.getLogger("").level = java.util.logging.Level.OFF

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

    // Headless / CI mode: any --export-* flag short-circuits the TUI bring-up
    // and runs introspect → diff → write directly. jul/stderr are not
    // redirected here so warnings stream to the calling shell as usual.
    if (hasHeadlessFlag(args)) {
        kotlin.system.exitProcess(runHeadless(args, state))
    }

    val sourceRoots = argValuesAll(args, "--sources").map(Path::of)

    // Redirect stderr to a buffer for the whole TUI session. Warnings produced
    // by the JVM (Unsafe deprecation), the Kotlin compiler embeddable used by
    // PsiEnvironment, JDBC drivers, etc. would otherwise be drawn over by
    // raw-mode screen refreshes and disappear before the user can read them.
    // Drained into a modal after each introspection + scan cycle.
    val stderrBuffer = ByteArrayOutputStream()
    val originalErr = System.err

    // Last-resort drains. Even if the JVM dies from a native-lib mismatch,
    // a SIGSEGV in Lanterna/JNI, or a System.exit() while stderr is still
    // redirected to our buffer, the user must see the diagnostics.
    fun flushBufferedErr() {
        System.setErr(originalErr)
        val tail = stderrBuffer.toString(Charsets.UTF_8)
        stderrBuffer.reset()
        if (tail.isNotEmpty()) originalErr.print(tail)
    }
    Runtime.getRuntime().addShutdownHook(Thread { flushBufferedErr() })
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        flushBufferedErr()
        e.printStackTrace(originalErr)
    }
    System.setErr(PrintStream(stderrBuffer, true, Charsets.UTF_8))

    var screen: Screen? = null
    var classifier: SchemaClassifier? = null
    try {
        // Always stream-based ANSI: never let Lanterna fall back to its AWT
        // SwingTerminalFrame. On Windows that fallback would also be triggered
        // when WinConsole (JNA) refuses to share a console with java.exe, and
        // popping a Swing window for a CLI tool is never what we want. conhost
        // (Win10 1809+), Windows Terminal, and every Unix terminal speak ANSI
        // escapes natively, so the TUI renders into the parent console.
        val factory = DefaultTerminalFactory()
            .setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE_DRAG)
            .setForceTextTerminal(true)
        val terminal = factory.createTerminal()
        val createdScreen = TerminalScreen(terminal).also { screen = it }
        createdScreen.startScreen()
        val createdClassifier = SchemaClassifier().also { classifier = it }
        var outcome: SchemaSyncOutcome
        do {
            val intro = loadColumnsAndTablesCancellable(createdScreen, connection, stderrBuffer) ?: return
            val columnsByTable = groupByTable(intro.columns)
            val effectiveTableKeys = intro.tableKeys.ifEmpty { columnsByTable.keys.toList() }
            val entities = if (sourceRoots.isNotEmpty()) EntityScanner.scan(sourceRoots, state.current.namingPolicy) else emptyList()
            drainStderrWarnings(createdScreen, stderrBuffer)
            val diffs = DiffEngine.diff(entities, columnsByTable, effectiveTableKeys, state.current.namingPolicy)
            val diffsByTable = diffs.associateBy { it.tableKey }
            val tables = buildTableEntries(diffs, intro.viewKeys)
            val dialect = intro.dialect

            createdClassifier.trainFromSync(diffs, state.current.slots)
            val cache = ClassificationCache().also {
                autoFillCache(it, createdClassifier, diffs, state.current.slots)
            }

            outcome = runSchemaSync(
                screen = createdScreen,
                configState = state,
                classifier = createdClassifier,
                cache = cache,
                columnsToClassify = intro.columns.filter { it.category != null },
                tables = tables,
                diffsByTable = diffsByTable,
                entities = entities,
                dialect = dialect,
                title = "Stormify Schema Sync ${Symbols.dash} ${connection.url}",
            )
        } while (outcome == SchemaSyncOutcome.RESCAN)
    } finally {
        runCatching { screen?.stopScreen() }
        runCatching { classifier?.close() }
        flushBufferedErr()
    }
}

private data class IntrospectionResult(
    val columns: List<ColumnRef>,
    val tableKeys: List<String>,
    val viewKeys: Set<String>,
    val dialect: Dialect,
)

/**
 * Runs introspection on a worker thread while the calling thread keeps the
 * Lanterna screen in raw mode and polls for ESC. On ESC, the JDBC connection
 * is closed (which aborts the running bulk query in every supported driver),
 * the worker is joined, and the function returns null so the main loop can
 * exit cleanly.
 */
private data class IntrospectProgress(val label: String, val done: Int, val total: Int)

private fun loadColumnsAndTablesCancellable(
    screen: Screen,
    connection: ConnectionConfig,
    @Suppress("UNUSED_PARAMETER") stderrBuffer: ByteArrayOutputStream,
): IntrospectionResult? {
    val intro = DbIntrospector.connect(connection.url, connection.user, connection.password)
    val progress = AtomicReference(IntrospectProgress("Connecting…", 0, 0))
    val cancelled = AtomicBoolean(false)
    val resultRef = AtomicReference<IntrospectionResult?>()
    val errorRef = AtomicReference<Throwable?>()

    val worker = Thread({
        try {
            val tables = intro.listTables()
            val keys = tables.map { it.key }
            val views = tables.asSequence()
                .filter { it.kind == DbIntrospector.TableId.Kind.VIEW }
                .map { it.name.lowercase() }
                .toSet()
            val cols = intro.listColumns { done, total ->
                progress.set(IntrospectProgress("Introspecting tables", done, total))
            }
            resultRef.set(IntrospectionResult(cols, keys, views, intro.dialect))
        } catch (e: Throwable) {
            if (!cancelled.get()) errorRef.set(e)
        }
    }, "schema-sync-introspect").apply { isDaemon = true; start() }

    try {
        while (worker.isAlive) {
            drawProgress(screen, progress.get())
            val key = screen.pollInput()
            if (key != null) {
                val isCtrlC = key.character?.code == 'c'.code && key.isCtrlDown
                if (key.keyType == KeyType.Escape || isCtrlC) {
                    cancelled.set(true)
                    runCatching { intro.cancelInflight() }
                    worker.join(2000)
                    return null
                }
            }
            Thread.sleep(40)
        }
        worker.join()
    } finally {
        runCatching { intro.cancelInflight() }
    }
    errorRef.get()?.let { throw it }
    return resultRef.get()
}

/** Drains [stderrBuffer] into a modal dialog if non-empty. Caller passes the
 *  buffer that's been intercepting stderr; this is invoked after each
 *  introspection + entity-scan cycle so warnings from JVM/Kotlin/JDBC are
 *  surfaced once instead of being painted over by the raw-mode TUI. */
private fun drainStderrWarnings(screen: Screen, stderrBuffer: ByteArrayOutputStream) {
    val warnings = stderrBuffer.toString(Charsets.UTF_8).trim()
    if (warnings.isEmpty()) return
    stderrBuffer.reset()
    val gui = MultiWindowTextGUI(screen)
    MessageDialog.showMessageDialog(gui, "Warnings", warnings, MessageDialogButton.OK)
}

/** Centred single-line status drawn on the raw-mode screen. No progress bar —
 *  Oracle's two-pass query (columns then defaults) doesn't map cleanly to a
 *  single fraction, and the bar would just stall and resume confusingly. */
private fun drawProgress(screen: Screen, p: IntrospectProgress) {
    screen.doResizeIfNecessary()
    val size = screen.terminalSize
    screen.clear()
    val gfx = screen.newTextGraphics()
    val fraction = if (p.total > 0) " ${p.done}/${p.total}" else ""
    val line = "${p.label}$fraction  ${Symbols.dash}  ESC to cancel"
    val x = ((size.columns - line.length) / 2).coerceAtLeast(0)
    val y = (size.rows / 2).coerceAtLeast(0)
    gfx.putString(x, y, line)
    screen.refresh()
}

