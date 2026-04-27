package onl.ycode.stormify.schemasync

import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.googlecode.lanterna.terminal.MouseCaptureMode
import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.config.ConfigStore
import onl.ycode.stormify.schemasync.fixture.fixtureDiffs
import onl.ycode.stormify.schemasync.fixture.fixtureTables
import onl.ycode.stormify.schemasync.tui.Symbols
import onl.ycode.stormify.schemasync.tui.runSchemaSync

private const val USAGE = """Usage: schema-sync [options]

Options:
  --ascii        Render the TUI using only ASCII characters (no Unicode box drawing).
  -h, --help     Show this help message and exit.
"""

fun main(args: Array<String>) {
    if (args.any { it == "-h" || it == "--help" }) {
        print(USAGE)
        return
    }
    Symbols.ascii = args.contains("--ascii")

    val store = ConfigStore.forCurrentDirectory()
    val configResult = store.load()
    val state = ConfigState(configResult.config, store)

    val factory = DefaultTerminalFactory()
        .setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE)
    val terminal = factory.createTerminal()
    val screen: Screen = TerminalScreen(terminal)
    screen.startScreen()
    try {
        runSchemaSync(
            screen = screen,
            configState = state,
            tables = fixtureTables,
            diffs = fixtureDiffs,
            title = "Stormify Schema Sync — jdbc:postgresql://localhost:5432/myapp",
        )
    } finally {
        screen.stopScreen()
    }
}
