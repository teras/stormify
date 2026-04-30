package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.bundle.LanternaThemes
import com.googlecode.lanterna.gui2.ActionListBox
import com.googlecode.lanterna.gui2.BasicWindow
import com.googlecode.lanterna.gui2.CheckBox
import com.googlecode.lanterna.gui2.DefaultWindowManager
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.EmptySpace
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
import com.googlecode.lanterna.screen.Screen
import onl.ycode.stormify.schemasync.classifier.SchemaClassifier
import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.db.Dialect
import onl.ycode.stormify.schemasync.db.MigrationGenerator
import onl.ycode.stormify.schemasync.entity.ColumnDelta
import onl.ycode.stormify.schemasync.entity.KotlinEntity
import onl.ycode.stormify.schemasync.entity.TableDiff
import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.model.TableEntry
import onl.ycode.stormify.schemasync.model.TableStatus
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

enum class SchemaSyncOutcome { CANCEL, RESCAN }
private typealias Outcome = SchemaSyncOutcome

@Suppress("LongParameterList", "LongMethod")
fun runSchemaSync(
    screen: Screen,
    configState: ConfigState,
    classifier: SchemaClassifier,
    columnsToClassify: List<ColumnRef>,
    tables: List<TableEntry>,
    diffsByTable: Map<String, TableDiff>,
    entities: List<KotlinEntity> = emptyList(),
    dialect: Dialect = Dialect.GENERIC,
    title: String = "Stormify Schema Sync",
): SchemaSyncOutcome {
    val gui = MultiWindowTextGUI(screen, DefaultWindowManager(), EmptySpace(TextColor.ANSI.DEFAULT))
    val themeNames = listOf("default", "businessmachine", "blaster", "bigsnake", "conqueror", "defrost")
    var themeIdx = 0
    fun applyTheme() {
        LanternaThemes.getRegisteredTheme(themeNames[themeIdx])?.let { gui.theme = it }
    }
    applyTheme()
    val window = BasicWindow(title)
    window.setHints(listOf(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS))

    val propertyActions = PropertyActions()

    val root = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))

    val statusLabel = Label("")
    fun refreshStatus() {
        statusLabel.text =
            "${tables.size} tables · ${tables.count { it.status == TableStatus.DIFF }} with diffs · " +
                "${dialect.tomlKey} · F2 [${themeNames[themeIdx]}] · F3 slots · F5 export · F6 defaults · F7 writer"
    }
    refreshStatus()
    root.addComponent(statusLabel)
    root.addComponent(EmptySpace(TerminalSize(1, 1)))

    val filterRow = Panel(LinearLayout(Direction.HORIZONTAL))
    filterRow.addComponent(Label("Filter (/): "))
    root.addComponent(filterRow)

    val tableFormatter = RowFormatter(tables)

    val allTableRows = tables.map { TableRowItem(it, tableFormatter) }
    var currentFilter = ""
    var hideSynced = false

    val tablesList = SelectionAwareListBox(separatorColumns = tableFormatter.crossColumns)

    fun rebuildTablesList() {
        val prev = (tablesList.selectedItem as? TableRowItem)?.entry
        tablesList.clearItems()
        val filter = currentFilter.lowercase()
        val visible = allTableRows.filter { row ->
            (!hideSynced || row.entry.status != TableStatus.SYNCED) &&
                (filter.isEmpty() ||
                    row.entry.table.lowercase().contains(filter) ||
                    (row.entry.entity?.lowercase()?.contains(filter) == true))
        }
        visible.forEach { tablesList.addItem(it) }
        if (prev != null) {
            val idx = visible.indexOfFirst { it.entry == prev }
            if (idx >= 0) tablesList.selectedIndex = idx
        }
    }
    rebuildTablesList()

    val filterBox = TextBox(TerminalSize(40, 1))
    filterBox.setTextChangeListener { newText, _ ->
        currentFilter = newText
        rebuildTablesList()
    }
    filterRow.addComponent(filterBox)
    filterRow.addComponent(EmptySpace(TerminalSize(2, 1)))
    val hideSyncedBox = CheckBox("Hide synced").apply {
        addListener { checked ->
            hideSynced = checked
            rebuildTablesList()
        }
    }
    filterRow.addComponent(hideSyncedBox)

    val tablesHeader = Label(tableFormatter.headerRow())
    val tablesRule = HeaderRule(tableFormatter.crossColumns).apply {
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill)
    }
    tablesList.layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
    val tablesPanel = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0)).apply {
        addComponent(tablesHeader)
        addComponent(tablesRule)
        addComponent(tablesList)
    }
    val tablesBordered = tablesPanel.withTitledBorder("Tables (${tables.size})")

    val globalPropsFormatter = PropertyRowFormatter(diffsByTable.values.flatMap { it.columnDeltas })
    val propsList = SelectionAwareListBox(globalPropsFormatter.crossColumns)
    val propsHeaderLabel = Label(globalPropsFormatter.headerRow())
    val propsRule = HeaderRule(globalPropsFormatter.crossColumns).apply {
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill)
    }
    propsList.layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
    val propsPanel = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0)).apply {
        addComponent(propsHeaderLabel)
        addComponent(propsRule)
        addComponent(propsList)
    }
    val propsBordered = propsPanel.withTitledBorder("Properties")

    val classifierPane = ClassifierPane(configState, classifier) {
        propsList.invalidate()
    }
    val classifierBordered = classifierPane.component.withTitledBorder("Classification")

    val infoLabel = Label("")
    val infoBordered = Panel(LinearLayout(Direction.VERTICAL)).apply {
        addComponent(infoLabel)
    }.withTitledBorder("Entity info")

    tablesBordered.preferredSize = TerminalSize(tableFormatter.leftPaneWidth + 2, 8)
    tablesBordered.layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill)
    propsBordered.preferredSize = TerminalSize(globalPropsFormatter.paneWidth + 2, 8)
    propsBordered.layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill)
    classifierBordered.layoutData =
        LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)

    val topRow = Panel(LinearLayout(Direction.HORIZONTAL).setSpacing(0)).apply {
        addComponent(propsBordered)
        addComponent(classifierBordered)
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
    }
    infoBordered.layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill)

    val rightCol = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0)).apply {
        addComponent(topRow)
        addComponent(infoBordered)
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
    }

    val mainRow = Panel(LinearLayout(Direction.HORIZONTAL).setSpacing(0)).apply {
        addComponent(tablesBordered)
        addComponent(rightCol)
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
    }
    root.addComponent(mainRow)

    var currentTableKey: String? = null
    val currentDeltasRef: MutableList<ColumnDelta> = mutableListOf()

    fun updateInfoBar() {
        val key = currentTableKey
        if (key == null) {
            infoLabel.text = ""
            return
        }
        val diff = diffsByTable[key]
        val deltas = currentDeltasRef
        val adds = deltas.count { it.kind == ColumnDelta.Kind.ENTITY_ONLY }
        val orphans = deltas.count { it.kind == ColumnDelta.Kind.DB_ONLY }
        val mismatches = deltas.count { it.kind == ColumnDelta.Kind.TYPE_MISMATCH }
        val sb = StringBuilder()
        sb.append("table:    ").append(key).append('\n')
        sb.append("entity:   ").append(diff?.entity?.className ?: "(none)").append('\n')
        sb.append("source:   ").append(diff?.entity?.sourcePath ?: "-").append('\n')
        sb.append("status:   ").append("$adds add · $orphans orphan · $mismatches mismatch · ${deltas.size} total")
        infoLabel.text = sb.toString()
    }

    fun selectedDelta(): ColumnDelta? {
        val idx = propsList.selectedIndex
        return (propsList.getItemAt(idx) as? PropertyRow)?.delta
    }

    fun refreshClassifierForSelection() {
        val key = currentTableKey ?: return
        classifierPane.showFor(key, selectedDelta())
        if (window.focusedInteractable === classifierPane.focusTarget &&
            !classifierPane.hasFocusableContent) {
            propsList.takeFocus()
        }
    }

    fun loadPropsFor(tableKey: String) {
        currentTableKey = tableKey
        propsList.clearItems()
        currentDeltasRef.clear()
        val diff = diffsByTable[tableKey]
        if (diff == null) {
            propsHeaderLabel.text = "(no diff data)"
            updateInfoBar()
            classifierPane.showFor(tableKey, null)
            return
        }
        val deltas = diff.columnDeltas
        currentDeltasRef.addAll(deltas)
        deltas.forEach { delta ->
            propsList.addItem(PropertyRow(tableKey, delta, propertyActions, globalPropsFormatter))
        }
        if (deltas.isNotEmpty()) propsList.selectedIndex = 0
        updateInfoBar()
        refreshClassifierForSelection()
    }

    fun selectedTableKey(): String? =
        (tablesList.selectedItem as? TableRowItem)?.entry?.table

    tablesList.onSelectionChanged = {
        selectedTableKey()?.let { loadPropsFor(it) }
    }
    propsList.onSelectionChanged = {
        refreshClassifierForSelection()
    }
    propsList.onSpace = {
        val delta = selectedDelta()
        val key = currentTableKey
        if (delta != null && key != null) {
            propertyActions.cycle(key, delta.name, delta.kind)
            propsList.invalidate()
            updateInfoBar()
        }
    }
    propsList.onDelete = {
        val delta = selectedDelta()
        val key = currentTableKey
        if (delta != null && key != null) {
            propertyActions.set(key, delta.name, PropertyAction.NONE)
            propsList.invalidate()
            updateInfoBar()
        }
    }

    selectedTableKey()?.let { loadPropsFor(it) }

    var outcome = Outcome.CANCEL

    // Classifier is included only when it has actionable content; otherwise
    // focusing it would land on an empty list and look like "focus lost".
    fun activePanes(): List<Interactable> = buildList {
        add(tablesList)
        add(propsList)
        if (classifierPane.hasFocusableContent) add(classifierPane.focusTarget)
    }

    fun currentPaneIndex(): Int {
        val f = window.focusedInteractable ?: return -1
        return activePanes().indexOf(f)
    }

    fun movePane(delta: Int): Boolean {
        val panes = activePanes()
        val idx = currentPaneIndex()
        if (idx < 0) return false
        val next = idx + delta
        if (next !in panes.indices) return true
        panes[next].takeFocus()
        return true
    }

    fun focusInRightPane(): Boolean =
        window.focusedInteractable === classifierPane.focusTarget

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
                        rebuildTablesList()
                        tablesList.takeFocus()
                    } else {
                        outcome = Outcome.CANCEL
                        window.close()
                    }
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.ArrowRight && currentPaneIndex() >= 0 -> {
                    movePane(+1)
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.ArrowLeft && currentPaneIndex() >= 0 -> {
                    movePane(-1)
                    hasBeenHandled.set(true)
                }
                focusInRightPane() && classifierPane.handleKey(keyStroke) -> {
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.F2 -> {
                    themeIdx = if (keyStroke.isShiftDown) {
                        (themeIdx - 1 + themeNames.size) % themeNames.size
                    } else {
                        (themeIdx + 1) % themeNames.size
                    }
                    applyTheme()
                    refreshStatus()
                    gui.screen.refresh()
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.F3 -> {
                    runSlotsView(gui, configState)
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.F5 -> {
                    val out = Path.of("migration.sql").toAbsolutePath()
                    val stats = MigrationGenerator.generate(
                        diffs = diffsByTable.values.toList(),
                        profile = configState.current.slots,
                        defaults = configState.current.defaults,
                        assignments = configState.current.assignments,
                        output = out,
                        dialect = dialect,
                    )
                    val msg = buildString {
                        appendLine("Migration written to:")
                        appendLine(out.toString())
                        appendLine()
                        appendLine("${stats.addedColumns} ALTER TABLE ADD COLUMN")
                        appendLine("${stats.createdTables} CREATE TABLE")
                        if (stats.unclassifiedFields > 0) {
                            appendLine("${stats.unclassifiedFields} fields skipped (unclassified)")
                        }
                        if (stats.orphanColumns > 0) {
                            appendLine("${stats.orphanColumns} orphan DB columns (informational only)")
                        }
                    }
                    MessageDialog.showMessageDialog(gui, "Exported", msg, MessageDialogButton.OK)
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.F6 -> {
                    runDefaultsView(gui, configState, dialect)
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.F7 -> {
                    val applied = runWriterView(gui, entities, diffsByTable.values)
                    if (applied > 0) {
                        outcome = Outcome.RESCAN
                        window.close()
                    }
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.F1 || keyStroke.character == '?' -> {
                    showHelp(gui)
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
    window.focusedInteractable = tablesList
    gui.addWindowAndWait(window)

    return outcome
}

private class TableRowItem(val entry: TableEntry, private val formatter: RowFormatter) : Runnable {
    override fun run() = Unit
    override fun toString(): String = formatter.renderRow(entry)
}

/**
 * ActionListBox tuned for the schema-sync 3-pane layout:
 *  - ←/→ are returned UNHANDLED so the window dispatcher can route them as pane navigation
 *  - ↑/↓ at the first/last row are trapped (HANDLED) so focus stays inside the list
 *  - [onSelectionChanged] / [onSpace] / [onDelete] are optional hooks for the property pane
 */
internal class SelectionAwareListBox(
    separatorColumns: List<Int> = emptyList(),
) : ActionListBox() {
    var onSelectionChanged: (() -> Unit)? = null
    var onSpace: (() -> Unit)? = null
    var onDelete: (() -> Unit)? = null

    private val asciiRenderer = AsciiListBoxRenderer(separatorColumns)

    init {
        renderer = asciiRenderer
    }

    fun setSeparatorColumns(cols: List<Int>) {
        asciiRenderer.separatorColumns = cols
        invalidate()
    }

    override fun handleKeyStroke(keyStroke: KeyStroke): Interactable.Result {
        if (keyStroke.character == ' ' && onSpace != null) {
            onSpace?.invoke()
            return Interactable.Result.HANDLED
        }
        if (keyStroke.keyType == KeyType.Delete && onDelete != null) {
            onDelete?.invoke()
            return Interactable.Result.HANDLED
        }
        if (keyStroke.keyType == KeyType.ArrowLeft || keyStroke.keyType == KeyType.ArrowRight) {
            return Interactable.Result.UNHANDLED
        }
        val before = selectedIndex
        val r = super.handleKeyStroke(keyStroke)
        if (selectedIndex != before) onSelectionChanged?.invoke()
        return when (r) {
            Interactable.Result.MOVE_FOCUS_UP,
            Interactable.Result.MOVE_FOCUS_DOWN -> Interactable.Result.HANDLED
            else -> r
        }
    }
}
