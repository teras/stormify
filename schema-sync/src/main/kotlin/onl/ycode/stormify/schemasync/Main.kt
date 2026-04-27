package onl.ycode.stormify.schemasync

import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.googlecode.lanterna.terminal.MouseCaptureMode
import onl.ycode.stormify.schemasync.fixture.fixtureDiffs
import onl.ycode.stormify.schemasync.fixture.fixtureTables
import onl.ycode.stormify.schemasync.tui.Symbols
import onl.ycode.stormify.schemasync.tui.runSchemaSync

fun main(args: Array<String>) {
    Symbols.ascii = args.contains("--ascii")
    val factory = DefaultTerminalFactory()
        .setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE)
    val terminal = factory.createTerminal()
    val screen: Screen = TerminalScreen(terminal)
    screen.startScreen()
    try {
        runSchemaSync(
            screen = screen,
            tables = fixtureTables,
            diffs = fixtureDiffs,
            title = "Stormify Schema Sync — jdbc:postgresql://localhost:5432/myapp",
        )
    } finally {
        screen.stopScreen()
    }
}
