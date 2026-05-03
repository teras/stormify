package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.BasicWindow
import com.googlecode.lanterna.gui2.Button
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.EmptySpace
import com.googlecode.lanterna.gui2.Interactable
import com.googlecode.lanterna.gui2.Label
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.gui2.Window
import com.googlecode.lanterna.gui2.WindowBasedTextGUI
import com.googlecode.lanterna.gui2.WindowListenerAdapter
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.db.Dialect
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reusable "tab content" — a self-contained editor that can be hosted inside a
 * larger tabbed window. The host owns the window-level concerns (Esc, F1) and
 * delegates [handleKey] for tab-specific shortcuts (Insert/Delete etc.). It
 * calls [commit] when leaving the tab so editor-internal state can be flushed
 * to the config store.
 */
internal data class TabPane(
    val component: Panel,
    val initialFocus: Interactable,
    val handleKey: (KeyStroke, Interactable?) -> Boolean = { _, _ -> false },
    val commit: () -> Unit = {},
)

/**
 * F7 Config — tabbed editor combining Defaults and Slots in a single window.
 * Each tab is exposed as a focusable button at the top; pressing Enter on a
 * button switches the body to that tab's editor. Tab/Shift-Tab traverses
 * fields *within* a tab. Esc commits the active tab and closes.
 */
fun runConfigView(gui: WindowBasedTextGUI, state: ConfigState, dialect: Dialect) {
    val window = BasicWindow("Config")
    window.setHints(listOf(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS))

    data class TabSpec(val label: String, val build: () -> TabPane, val hint: String)
    val tabs = listOf(
        TabSpec("Database", { buildDbDefaultsPane(gui, state, dialect) }, DEFAULTS_PANE_HINT),
        TabSpec("Kotlin",   { buildKtDefaultsPane(state) },               KT_PANE_HINT),
        TabSpec("Slots",    { buildSlotsPane(gui, state) },               SLOTS_PANE_HINT),
    )
    val cached = arrayOfNulls<TabPane>(tabs.size)
    fun pane(i: Int): TabPane = cached[i] ?: tabs[i].build().also { cached[i] = it }

    var activeIdx = 0
    val contentSlot = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))
    val hint = Label("")
    val tabButtons = mutableListOf<Button>()

    fun renderTabLabels() {
        tabs.forEachIndexed { i, spec ->
            tabButtons[i].label = if (i == activeIdx) "[ ${spec.label} ]" else "  ${spec.label}  "
        }
    }

    /**
     * Swap which tab is visible. [focusContent] = true moves focus into the
     * active tab's first interactable; false leaves focus on the tab buttons
     * (used at initial open, so the user can see the buttons highlighted and
     * immediately understands they're the way to switch tabs).
     */
    fun showTab(i: Int, focusContent: Boolean) {
        if (i == activeIdx && cached[activeIdx] != null) {
            if (focusContent) cached[activeIdx]?.initialFocus?.takeFocus()
            return
        }
        cached[activeIdx]?.commit?.invoke()
        activeIdx = i
        val active = pane(i)
        contentSlot.removeAllComponents()
        contentSlot.addComponent(active.component)
        hint.text = tabs[i].hint
        renderTabLabels()
        if (focusContent) active.initialFocus.takeFocus()
    }

    val tabBar = Panel(LinearLayout(Direction.HORIZONTAL).setSpacing(1))
    tabs.forEachIndexed { i, spec ->
        // Activating the button enters the tab content — discoverable by the
        // user after they Tab between buttons.
        val b = plainButton(spec.label) { showTab(i, focusContent = true) }
        tabButtons += b
        tabBar.addComponent(b)
    }

    val root = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))
    root.addComponent(tabBar)
    root.addComponent(EmptySpace(TerminalSize(1, 1)))
    root.addComponent(contentSlot)
    root.addComponent(EmptySpace(TerminalSize(1, 1)))
    root.addComponent(hint)
    root.addComponent(Label("Enter on tab button switches tab · Esc save & close · F1 help"))

    window.component = root
    showTab(0, focusContent = false)
    window.focusedInteractable = tabButtons[0]

    window.addWindowListener(object : WindowListenerAdapter() {
        override fun onUnhandledInput(basePane: Window, keyStroke: KeyStroke, hasBeenHandled: AtomicBoolean) {
            when {
                keyStroke.keyType == KeyType.F1 || keyStroke.character == '?' -> {
                    showHelp(gui); hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.Escape || keyStroke.character == 'q' || keyStroke.character == 'Q' -> {
                    cached[activeIdx]?.commit?.invoke()
                    window.close(); hasBeenHandled.set(true)
                }
                ThemeManager.handleKey(keyStroke, gui) -> {
                    gui.screen.refresh(); hasBeenHandled.set(true)
                }
                cached[activeIdx]?.handleKey?.invoke(keyStroke, window.focusedInteractable) == true -> {
                    hasBeenHandled.set(true)
                }
            }
        }
    })

    gui.addWindowAndWait(window)
}
