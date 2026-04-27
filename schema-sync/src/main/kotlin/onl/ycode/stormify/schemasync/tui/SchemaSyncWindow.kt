package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.bundle.LanternaThemes
import com.googlecode.lanterna.gui2.ActionListBox
import com.googlecode.lanterna.gui2.BasicWindow
import com.googlecode.lanterna.gui2.Button
import com.googlecode.lanterna.gui2.CheckBox
import com.googlecode.lanterna.gui2.DefaultWindowManager
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.EmptySpace
import com.googlecode.lanterna.gui2.GridLayout
import com.googlecode.lanterna.gui2.Interactable
import com.googlecode.lanterna.gui2.Label
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.MultiWindowTextGUI
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.gui2.TextBox
import com.googlecode.lanterna.gui2.Window
import com.googlecode.lanterna.gui2.WindowBasedTextGUI
import com.googlecode.lanterna.gui2.WindowListenerAdapter
import com.googlecode.lanterna.gui2.dialogs.MessageDialog
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.input.MouseAction
import com.googlecode.lanterna.input.MouseActionType
import com.googlecode.lanterna.screen.Screen
import onl.ycode.stormify.schemasync.model.ColumnDiff
import onl.ycode.stormify.schemasync.model.TableEntry
import onl.ycode.stormify.schemasync.model.TableStatus
import java.util.concurrent.atomic.AtomicBoolean

private enum class Outcome { APPLY, CANCEL }

private const val ACTION_COL_WIDTH = 4
private const val DOUBLE_CLICK_MS = 400L

fun runSchemaSync(
    screen: Screen,
    tables: List<TableEntry>,
    diffs: Map<String, List<ColumnDiff>>,
    title: String = "Stormify Schema Sync",
) {
    val gui = MultiWindowTextGUI(screen, DefaultWindowManager(), EmptySpace(TextColor.ANSI.DEFAULT))
    val themeNames = listOf("default", "businessmachine", "blaster", "bigsnake", "defrost")
    var themeIdx = 0
    fun applyTheme() {
        LanternaThemes.getRegisteredTheme(themeNames[themeIdx])?.let { gui.theme = it }
    }
    applyTheme()
    val window = BasicWindow(title)
    window.setHints(listOf(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS))

    val root = Panel(LinearLayout(Direction.VERTICAL))
    root.layoutManager = LinearLayout(Direction.VERTICAL).setSpacing(0)

    val statusLabel = Label("")
    fun refreshStatus() {
        statusLabel.text =
            "${tables.size} tables · ${tables.count { it.status == TableStatus.DIFF }} with diffs · " +
                "↑↓ PgUp PgDn · Space cycle · / filter · Tab panes · Enter apply · Esc cancel · F2 [${themeNames[themeIdx]}]"
    }
    refreshStatus()
    root.addComponent(statusLabel)
    root.addComponent(EmptySpace(TerminalSize(1, 1)))

    val filterRow = Panel(LinearLayout(Direction.HORIZONTAL))
    filterRow.addComponent(Label("Filter (/): "))
    root.addComponent(filterRow)

    val formatter = RowFormatter(tables)
    val previewRenderer = DiffPreviewRenderer(diffs)

    val allRows = tables.map { RowAction(it, formatter) }
    var currentFilter = ""
    var hideSynced = false

    val split = Panel(GridLayout(2).setHorizontalSpacing(0).setVerticalSpacing(0))
    split.layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)

    val list = object : ActionListBox() {
        var onSelectionChanged: (() -> Unit)? = null
        private var lastClickAt: Long = 0L
        private var lastClickRow: Int = -1

        init {
            renderer = AsciiListBoxRenderer()
        }

        override fun handleKeyStroke(keyStroke: KeyStroke): Interactable.Result {
            val cancelCycle: Pair<Int, Action>? = run {
                if (keyStroke !is MouseAction) return@run null
                if (keyStroke.actionType != MouseActionType.CLICK_DOWN) return@run null
                val gp = globalPosition ?: return@run null
                val localCol = keyStroke.position.column - gp.column
                val idx = getIndexByMouseAction(keyStroke)
                if (idx !in 0 until itemCount) return@run null

                val now = System.currentTimeMillis()
                val isDoubleClick = idx == lastClickRow && (now - lastClickAt) <= DOUBLE_CLICK_MS
                if (isDoubleClick) {
                    lastClickAt = 0L
                    lastClickRow = -1
                } else {
                    lastClickAt = now
                    lastClickRow = idx
                }

                if (localCol < ACTION_COL_WIDTH || isDoubleClick) return@run null
                val item = getItemAt(idx) as? RowAction ?: return@run null
                idx to item.action
            }

            val before = selectedIndex
            val r = super.handleKeyStroke(keyStroke)
            if (cancelCycle != null) {
                val (idx, savedAction) = cancelCycle
                (getItemAt(idx) as? RowAction)?.action = savedAction
            }
            if (selectedIndex != before) onSelectionChanged?.invoke()
            return r
        }
    }
    fun rebuildList() {
        val prevSelected = (list.selectedItem as? RowAction)?.entry
        list.clearItems()
        val filter = currentFilter.lowercase()
        val visible = allRows.filter { row ->
            (!hideSynced || row.entry.status != TableStatus.SYNCED) &&
                (filter.isEmpty() ||
                    row.entry.table.lowercase().contains(filter) ||
                    (row.entry.entity?.lowercase()?.contains(filter) == true))
        }
        visible.forEach { list.addItem(it) }
        if (prevSelected != null) {
            val idx = visible.indexOfFirst { it.entry == prevSelected }
            if (idx >= 0) list.selectedIndex = idx
        }
    }
    rebuildList()

    val previewPanel = Panel(LinearLayout(Direction.VERTICAL))
    val previewTitleLabel = Label("")
    val previewBodyLabel = Label("")
    previewPanel.addComponent(previewTitleLabel)
    previewPanel.addComponent(EmptySpace(TerminalSize(1, 1)))
    previewPanel.addComponent(previewBodyLabel)

    val filterBox = TextBox(TerminalSize(40, 1))
    filterBox.setTextChangeListener { newText, _ ->
        currentFilter = newText
        rebuildList()
    }
    filterRow.addComponent(filterBox)
    filterRow.addComponent(EmptySpace(TerminalSize(2, 1)))
    val hideSyncedBox = CheckBox("Hide synced").apply {
        addListener { checked ->
            hideSynced = checked
            rebuildList()
        }
    }
    filterRow.addComponent(hideSyncedBox)

    val headerLabel = Label(formatter.headerRow())
    val ruleLabel = Label(formatter.headerRule())
    val listWithHeader = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))
    listWithHeader.addComponent(headerLabel)
    listWithHeader.addComponent(ruleLabel)
    listWithHeader.addComponent(list)

    val leftBordered = listWithHeader.withTitledBorder("Tables (${tables.size})")
    val rightBordered = previewPanel.withTitledBorder("Diff preview")

    val termCols = screen.terminalSize.columns
    val leftWidth = formatter.leftPaneWidth.coerceAtMost(termCols / 2)
    val rightWidth = (termCols - leftWidth - 1).coerceAtLeast(30)
    leftBordered.preferredSize = TerminalSize(leftWidth, screen.terminalSize.rows - 6)
    rightBordered.preferredSize = TerminalSize(rightWidth, screen.terminalSize.rows - 6)
    leftBordered.layoutData = GridLayout.createLayoutData(
        GridLayout.Alignment.BEGINNING, GridLayout.Alignment.FILL, false, true,
    )
    rightBordered.layoutData = GridLayout.createLayoutData(
        GridLayout.Alignment.FILL, GridLayout.Alignment.FILL, true, true,
    )
    previewBodyLabel.preferredSize = TerminalSize(rightWidth - 4, screen.terminalSize.rows - 10)
    split.addComponent(leftBordered)
    split.addComponent(rightBordered)
    root.addComponent(split)

    fun applyBulk(target: Action) {
        for (i in 0 until list.itemCount) {
            val item = list.getItemAt(i) as? RowAction ?: continue
            if (!item.action.locked) item.action = target
        }
        list.invalidate()
    }
    fun flatButton(label: String, onClick: () -> Unit): Button =
        Button(label, onClick).apply { renderer = Button.FlatButtonRenderer() }

    val bottom = Panel(LinearLayout(Direction.HORIZONTAL))
    var outcome = Outcome.CANCEL
    bottom.addComponent(EmptySpace(TerminalSize(1, 1)))
    bottom.addComponent(flatButton("${Action.TABLE_TO_ENTITY.label} All") { applyBulk(Action.TABLE_TO_ENTITY) })
    bottom.addComponent(EmptySpace(TerminalSize(1, 1)))
    bottom.addComponent(flatButton("${Action.ENTITY_TO_TABLE.label} All") { applyBulk(Action.ENTITY_TO_TABLE) })
    bottom.addComponent(EmptySpace(TerminalSize(1, 1)))
    bottom.addComponent(flatButton("Clear") { applyBulk(Action.NONE) })
    bottom.addComponent(EmptySpace(TerminalSize(3, 1)))
    bottom.addComponent(flatButton("Apply") {
        outcome = Outcome.APPLY
        window.close()
    })
    bottom.addComponent(EmptySpace(TerminalSize(1, 1)))
    bottom.addComponent(flatButton("Cancel") {
        outcome = Outcome.CANCEL
        window.close()
    })
    root.addComponent(bottom)
    root.addComponent(EmptySpace(TerminalSize(1, 1)))

    list.onSelectionChanged = {
        val idx = list.selectedIndex
        if (idx in 0 until list.itemCount) {
            (list.getItemAt(idx) as? RowAction)?.let {
                previewRenderer.update(it.entry, previewTitleLabel, previewBodyLabel)
            }
        }
    }
    if (list.itemCount > 0) {
        (list.getItemAt(0) as? RowAction)?.let {
            previewRenderer.update(it.entry, previewTitleLabel, previewBodyLabel)
        }
    }

    window.addWindowListener(object : WindowListenerAdapter() {
        override fun onUnhandledInput(basePane: Window, keyStroke: KeyStroke, hasBeenHandled: AtomicBoolean) {
            when {
                keyStroke.character == '/' -> {
                    filterBox.takeFocus()
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.Escape -> {
                    if (window.focusedInteractable === filterBox && filterBox.text.isNotEmpty()) {
                        filterBox.text = ""
                        currentFilter = ""
                        rebuildList()
                        list.takeFocus()
                    } else {
                        outcome = Outcome.CANCEL
                        window.close()
                    }
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.F2 -> {
                    themeIdx = (themeIdx + 1) % themeNames.size
                    applyTheme()
                    refreshStatus()
                    gui.screen.refresh()
                    hasBeenHandled.set(true)
                }
                keyStroke.character == 'q' || keyStroke.character == 'Q' -> {
                    outcome = Outcome.CANCEL
                    window.close()
                    hasBeenHandled.set(true)
                }
            }
        }
    })

    window.component = root
    window.focusedInteractable = list
    gui.addWindowAndWait(window)

    if (outcome == Outcome.APPLY) {
        val selected = allRows.filter { it.action != Action.NONE && !it.action.locked }
        showApplySummary(gui, selected)
    }
}

private fun showApplySummary(gui: WindowBasedTextGUI, selected: List<RowAction>) {
    val toEntity = selected.count { it.action == Action.TABLE_TO_ENTITY }
    val toTable = selected.count { it.action == Action.ENTITY_TO_TABLE }
    val arrow = Symbols.arrow
    MessageDialog.showMessageDialog(
        gui,
        "Applied",
        "Processed ${selected.size} tables\n  $toEntity table $arrow entity\n  $toTable entity $arrow table\nSQL written to ./stormify-schema-changes.sql\n\nReview with: git diff",
        MessageDialogButton.OK,
    )
}
