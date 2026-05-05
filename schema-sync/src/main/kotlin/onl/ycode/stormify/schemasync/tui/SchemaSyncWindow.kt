package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.gui2.ActionListBox
import com.googlecode.lanterna.gui2.BasicWindow
import com.googlecode.lanterna.gui2.CheckBoxList
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
import com.googlecode.lanterna.gui2.Button
import com.googlecode.lanterna.gui2.WindowListenerAdapter
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.Screen
import onl.ycode.stormify.schemasync.classifier.ClassificationCache
import onl.ycode.stormify.schemasync.classifier.SchemaClassifier
import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.db.Dialect
import onl.ycode.stormify.schemasync.entity.ColumnDelta
import onl.ycode.stormify.schemasync.entity.KotlinEntity
import onl.ycode.stormify.schemasync.entity.TableDiff
import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.model.TableEntry
import onl.ycode.stormify.schemasync.model.TableStatus
import java.util.concurrent.atomic.AtomicBoolean

enum class SchemaSyncOutcome { CANCEL, RESCAN }
private typealias Outcome = SchemaSyncOutcome

@Suppress("LongParameterList", "LongMethod")
fun runSchemaSync(
    screen: Screen,
    configState: ConfigState,
    classifier: SchemaClassifier,
    cache: ClassificationCache,
    columnsToClassify: List<ColumnRef>,
    tables: List<TableEntry>,
    diffsByTable: Map<String, TableDiff>,
    entities: List<KotlinEntity> = emptyList(),
    dialect: Dialect = Dialect.GENERIC,
    title: String = "Stormify Schema Sync",
): SchemaSyncOutcome {
    val gui = MultiWindowTextGUI(screen, DefaultWindowManager(), EmptySpace(TextColor.ANSI.DEFAULT))
    ThemeManager.apply(gui)
    val window = BasicWindow(title)
    window.setHints(listOf(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS))

    val propertyActions = PropertyActions()

    val root = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))

    val statusLabel = Label("")
    fun pendingCount(): Int = diffsByTable.values.sumOf { diff ->
        diff.columnDeltas.count {
            propertyActions.get(diff.tableKey, it.name) != PropertyAction.NONE
        }
    }
    var visibleCount = 0
    val configButton = plainButton("Config (F7)") { runConfigView(gui, configState, dialect) }
    val themeButton = plainButton("Theme: ${ThemeManager.current()} (F8)") {}
    fun refreshStatus() {
        val pc = pendingCount()
        statusLabel.text =
            "${tables.size} tables · $visibleCount visible · ${dialect.tomlKey} · " +
                "$pc pending change${if (pc == 1) "" else "s"}"
        themeButton.label = "Theme: ${ThemeManager.current()} (F8)"
    }
    themeButton.addListener {
        ThemeManager.cycle(gui)
        refreshStatus()
        gui.screen.refresh()
    }
    refreshStatus()
    val statusRow = Panel(LinearLayout(Direction.HORIZONTAL).setSpacing(1)).apply {
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.None)
    }
    statusRow.addComponent(statusLabel)
    statusRow.addComponent(EmptySpace(TerminalSize(1, 1)).apply {
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
    })
    statusRow.addComponent(configButton)
    statusRow.addComponent(themeButton)
    root.addComponent(statusRow)

    val filterRow = Panel(LinearLayout(Direction.HORIZONTAL))
    filterRow.addComponent(Label("Filter (/) — prefix @ for column: "))
    root.addComponent(filterRow)
    root.addComponent(EmptySpace(TerminalSize(1, 1)))

    fun hasPendingFor(tableKey: String): Boolean {
        val diff = diffsByTable[tableKey] ?: return false
        return diff.columnDeltas.any {
            propertyActions.get(tableKey, it.name) != PropertyAction.NONE
        }
    }

    val tableFormatter = RowFormatter(tables) { entry -> hasPendingFor(entry.table) }

    val allTableRows = tables.map { TableRowItem(it, tableFormatter) }
    var currentFilter = ""
    val categoryFilter = CategoryFilter.defaultSelection()

    val tablesList = SelectionAwareListBox(separatorColumns = tableFormatter.crossColumns)

    // Set after loadPropsFor / selectedTableKey are declared further down. The
    // initial rebuildTablesList() call at construction time uses a no-op stub
    // because the initial pane state is established separately at the bottom
    // of the function via the explicit selectedTableKey()?.let { … } call.
    var refreshPanesAfterRebuild: () -> Unit = {}

    fun rebuildTablesList() {
        val prev = (tablesList.selectedItem as? TableRowItem)?.entry
        tablesList.clearItems()
        val raw = currentFilter.lowercase()
        // `@needle` switches the search to column / property names (any of the
        // slot's deltas matches). Anything else searches table + entity names
        // as before. The `@` prefix was picked so it doesn't collide with the
        // F3 bulk classifier syntax (which uses `!`, `:`, `*`).
        val byColumn = raw.startsWith("@")
        val filter = if (byColumn) raw.removePrefix("@") else raw
        val visible = allTableRows.filter { row ->
            categoryFilter.accept(row.entry) && (filter.isEmpty() || when {
                byColumn -> diffsByTable[row.entry.table]?.columnDeltas
                    ?.any { it.name.lowercase().contains(filter) } == true
                else -> row.entry.table.lowercase().contains(filter) ||
                    (row.entry.entity?.lowercase()?.contains(filter) == true)
            })
        }
        visible.forEach { tablesList.addItem(it) }
        if (prev != null) {
            val idx = visible.indexOfFirst { it.entry == prev }
            if (idx >= 0) tablesList.selectedIndex = idx
        }
        visibleCount = visible.size
        refreshStatus()
        refreshPanesAfterRebuild()
    }
    rebuildTablesList()

    val filterBox = TextBox(TerminalSize(40, 1))
    filterBox.setTextChangeListener { newText, _ ->
        currentFilter = newText
        rebuildTablesList()
    }
    filterRow.addComponent(filterBox)
    filterRow.addComponent(EmptySpace(TerminalSize(2, 1)))
    filterRow.addComponent(Label("Show: "))
    lateinit var categoryButton: Button
    categoryButton = plainButton(categoryFilter.summary()) {
        if (showCategoryPicker(gui, categoryFilter)) {
            categoryButton.label = categoryFilter.summary()
            rebuildTablesList()
        }
    }
    filterRow.addComponent(categoryButton)

    tablesList.layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)

    val globalPropsFormatter = PropertyRowFormatter(diffsByTable.values.flatMap { it.columnDeltas })
    val propsList = SelectionAwareListBox(globalPropsFormatter.crossColumns)
    propsList.layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)

    var currentTableKey: String? = null

    fun bulkSetTables(action: PropertyAction) {
        for (i in 0 until tablesList.itemCount) {
            val key = (tablesList.getItemAt(i) as? TableRowItem)?.entry?.table ?: continue
            val diff = diffsByTable[key] ?: continue
            for (delta in diff.columnDeltas) {
                val ok = action == PropertyAction.NONE ||
                    PropertyAction.isTogglable(delta.kind)
                if (ok) propertyActions.set(key, delta.name, action)
            }
        }
        tablesList.invalidate()
        propsList.invalidate()
    }

    fun bulkSetProps(action: PropertyAction) {
        val key = currentTableKey ?: return
        for (i in 0 until propsList.itemCount) {
            val row = propsList.getItemAt(i) as? PropertyRow ?: continue
            val ok = action == PropertyAction.NONE ||
                PropertyAction.isTogglable(row.delta.kind)
            if (ok) propertyActions.set(key, row.delta.name, action)
        }
        tablesList.invalidate()
        propsList.invalidate()
    }

    // Forward-reference holder: diffPane and refreshStatus are declared later.
    var bulkPostAction: () -> Unit = {}

    val tablesAllBtn = plainButton("All") { bulkSetTables(PropertyAction.INSERT); bulkPostAction() }
    val tablesNoneBtn = plainButton("None") { bulkSetTables(PropertyAction.NONE); bulkPostAction() }
    val tablesInnerBordered = tablesList.withTitledBorder(tableFormatter.titleText, tableFormatter.crossColumns)
    val tablesButtons = Panel(LinearLayout(Direction.HORIZONTAL).setSpacing(1)).apply {
        addComponent(tablesAllBtn)
        addComponent(tablesNoneBtn)
    }
    val tablesBordered = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0)).apply {
        addComponent(tablesInnerBordered.apply { layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow) })
        addComponent(tablesButtons)
    }

    val propsAllBtn = plainButton("All") { bulkSetProps(PropertyAction.INSERT); bulkPostAction() }
    val propsNoneBtn = plainButton("None") { bulkSetProps(PropertyAction.NONE); bulkPostAction() }
    val propsInnerBordered = propsList.withTitledBorder(globalPropsFormatter.titleText)
    val propsButtons = Panel(LinearLayout(Direction.HORIZONTAL).setSpacing(1)).apply {
        addComponent(propsAllBtn)
        addComponent(propsNoneBtn)
    }
    val propsBordered = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0)).apply {
        addComponent(propsInnerBordered.apply { layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow) })
        addComponent(propsButtons)
    }

    val diffPane = DiffPane(
        configState, classifier, cache, propertyActions,
        onAssigned = { propsList.invalidate() },
        dialect = dialect,
        entities = entities,
    )
    val diffBordered = diffPane.borderedComponent
    bulkPostAction = { diffPane.refresh(); refreshStatus() }

    var outcome = Outcome.CANCEL

    fun runF2Apply() {
        val pending = buildPendingChanges(entities, diffsByTable.values, propertyActions, policy = configState.current.namingPolicy, kotlinDefaults = configState.current.kotlin)
        val confirmed = runApplyConfirmation(gui, configState, cache, diffsByTable.values, pending, dialect, propertyActions, entities)
        if (confirmed) {
            outcome = Outcome.RESCAN
            window.close()
        }
    }
    fun runF3Classify() {
        val visibleKeys = (0 until tablesList.itemCount)
            .mapNotNull { (tablesList.getItemAt(it) as? TableRowItem)?.entry?.table }
            .toSet()
        val activeDiffs = diffsByTable.values
            .filter { it.tableKey in visibleKeys }
            .mapNotNull { diff ->
                val active = diff.columnDeltas.filter {
                    propertyActions.get(diff.tableKey, it.name) == PropertyAction.INSERT
                }
                if (active.isEmpty()) null else diff.copy(columnDeltas = active)
            }
        runBulkClassifyView(gui, configState, cache, activeDiffs)
        diffPane.refresh()
    }

    fun applyLeftDisplay(table: Int, entity: Int) {
        if (tableFormatter.displayTableWidth == table && tableFormatter.displayEntityWidth == entity) return
        tableFormatter.displayTableWidth = table
        tableFormatter.displayEntityWidth = entity
        tablesList.setSeparatorColumns(tableFormatter.crossColumns)
        tablesInnerBordered.title = markFocused(tableFormatter.titleText, window.focusedInteractable === tablesList)
        tablesInnerBordered.crossColumns = tableFormatter.crossColumns
        tablesList.invalidate()
    }

    val applyBtn = plainButton("Apply (F2)") { runF2Apply() }
    val classifyBtn = plainButton("Classify (F3)") { runF3Classify() }
    val diffButtons = Panel(LinearLayout(Direction.HORIZONTAL).setSpacing(1)).apply {
        addComponent(applyBtn)
        addComponent(classifyBtn)
    }
    val diffColumn = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0)).apply {
        addComponent(diffBordered.apply {
            layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
        })
        addComponent(diffButtons)
    }

    val priorityLayout = PriorityShrinkLayout(
        tablePref = { tableFormatter.tableWidth },
        entityPref = { tableFormatter.entityWidth },
        midPref = { globalPropsFormatter.paneWidth + 2 },
        leftOuterWidth = { t, e -> tableFormatter.outerWidthFor(t, e) },
        onLeftDisplayChange = { t, e -> applyLeftDisplay(t, e) },
    )

    val mainRow = Panel(priorityLayout).apply {
        addComponent(tablesBordered)
        addComponent(propsBordered)
        addComponent(diffColumn)
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
    }
    root.addComponent(mainRow)

    fun selectedDelta(): ColumnDelta? {
        val idx = propsList.selectedIndex
        if (idx < 0 || idx >= propsList.itemCount) return null
        return (propsList.getItemAt(idx) as? PropertyRow)?.delta
    }

    fun refreshDiffForSelection() {
        val key = currentTableKey ?: return
        diffPane.showFor(diffsByTable[key], selectedDelta())
        if (window.focusedInteractable === diffPane.focusTarget &&
            !diffPane.hasFocusableContent) {
            propsList.takeFocus()
        }
    }

    fun loadPropsFor(tableKey: String) {
        currentTableKey = tableKey
        propsList.clearItems()
        val diff = diffsByTable[tableKey]
        if (diff == null) {
            diffPane.showFor(null, null)
            return
        }
        // Mirror the entity diff: entity-side rows in declaration order first,
        // then DB-only rows in column order — same top-down sequence the user
        // reads in the file diff.
        val primaryFields = diff.primary?.fields.orEmpty()
        val entityRank: (ColumnDelta) -> Int = { d ->
            d.entityField?.let { f -> primaryFields.indexOf(f).takeIf { it >= 0 } } ?: -1
        }
        val dbRank: (ColumnDelta) -> Int = { d ->
            d.dbColumn?.let { c -> diff.dbColumns.indexOf(c) } ?: -1
        }
        val ordered = diff.columnDeltas.withIndex()
            .sortedWith(compareBy(
                { if (it.value.entityField != null) 0 else 1 },
                { entityRank(it.value).takeIf { idx -> idx >= 0 } ?: dbRank(it.value) },
                { it.index },
            ))
            .map { it.value }
        ordered.forEach { delta ->
            propsList.addItem(PropertyRow(tableKey, delta, propertyActions, globalPropsFormatter))
        }
        if (ordered.isNotEmpty()) {
            val firstActionable = ordered.indexOfFirst { it.kind != ColumnDelta.Kind.SYNCED }
            propsList.selectedIndex = firstActionable.takeIf { it >= 0 } ?: 0
        }
        refreshDiffForSelection()
    }

    fun selectedTableKey(): String? =
        (tablesList.selectedItem as? TableRowItem)?.entry?.table

    tablesList.onSelectionChanged = {
        selectedTableKey()?.let { loadPropsFor(it) }
    }
    tablesList.onSpace = {
        val key = selectedTableKey()
        val diff = key?.let(diffsByTable::get)
        if (diff != null) {
            val anyMarked = diff.columnDeltas.any {
                propertyActions.get(key, it.name) != PropertyAction.NONE
            }
            val target = if (anyMarked) PropertyAction.NONE else PropertyAction.INSERT
            for (delta in diff.columnDeltas) {
                if (target == PropertyAction.NONE || PropertyAction.isTogglable(delta.kind)) {
                    propertyActions.set(key, delta.name, target)
                }
            }
            tablesList.invalidate()
            propsList.invalidate()
            diffPane.refresh()
            refreshStatus()
        }
    }
    propsList.onSelectionChanged = {
        refreshDiffForSelection()
    }
    propsList.onSpace = {
        val delta = selectedDelta()
        val key = currentTableKey
        if (delta != null && key != null) {
            propertyActions.cycle(key, delta.name, delta.kind)
            propsList.invalidate()
            tablesList.invalidate()
            diffPane.refresh()
            refreshStatus()
        }
    }
    propsList.onDelete = {
        val delta = selectedDelta()
        val key = currentTableKey
        if (delta != null && key != null) {
            propertyActions.set(key, delta.name, PropertyAction.NONE)
            propsList.invalidate()
            tablesList.invalidate()
            diffPane.refresh()
            refreshStatus()
        }
    }

    refreshPanesAfterRebuild = {
        val key = selectedTableKey()
        if (key != null) {
            if (key != currentTableKey) loadPropsFor(key)
        } else {
            currentTableKey = null
            propsList.clearItems()
            diffPane.showFor(null, null)
        }
    }

    selectedTableKey()?.let { loadPropsFor(it) }

    // Diff pane is focusable only when its slot picker is active; otherwise
    // focusing it would land on an empty list and look like "focus lost".
    fun activePanes(): List<Interactable> = buildList {
        add(tablesList)
        add(propsList)
        if (diffPane.hasFocusableContent) add(diffPane.focusTarget)
    }

    fun currentPaneIndex(): Int {
        val f = window.focusedInteractable ?: return -1
        return activePanes().indexOf(f)
    }

    /** Mark the focused pane's titled border with a "●" so the user sees at a
     *  glance which pane owns focus. Direct identity checks against each pane's
     *  known focusables — `currentPaneIndex()` uses strict equality with
     *  `diffPane.focusTarget` and misses cases where focus lands on the other
     *  diff-pane child (slots vs targets). */
    fun applyFocusMarkers() {
        val f = window.focusedInteractable
        tablesInnerBordered.title = markFocused(tableFormatter.titleText, f === tablesList)
        propsInnerBordered.title = markFocused(globalPropsFormatter.titleText, f === propsList)
        diffPane.paneFocused = f != null && diffPane.ownsFocus(f)
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

    // diff focus target resolves to slotsList vs targetsList at runtime; the
    // others are stable but use thunks for uniformity.
    val focusOrder: List<() -> Interactable?> = listOf(
        { tablesList },
        { propsList },
        { diffPane.viewerFocusTarget },
        { if (diffPane.hasFocusableContent) diffPane.focusTarget else null },
        { filterBox },
        { categoryButton },
        { tablesAllBtn },
        { tablesNoneBtn },
        { propsAllBtn },
        { propsNoneBtn },
        { applyBtn },
        { classifyBtn },
        { configButton },
        { themeButton },
    )

    fun moveFocus(delta: Int) {
        val resolved = focusOrder.mapNotNull { it() }
        if (resolved.isEmpty()) return
        val current = window.focusedInteractable
        val idx = if (current == null) -1 else resolved.indexOfFirst { it === current }
        val nextIdx = if (idx < 0) (if (delta > 0) 0 else resolved.lastIndex) else (idx + delta).mod(resolved.size)
        resolved[nextIdx].takeFocus()
    }

    fun focusInRightPane(): Boolean =
        window.focusedInteractable === diffPane.focusTarget

    /** True when focus is on any of the three main panes (tables / properties /
     *  diff). Lets `1`–`9` slot-picker shortcuts work from the left/middle pane
     *  too — the user expects digits to act on the visible slot picker without
     *  first Tabbing to the diff pane. Filter widgets are excluded so typing
     *  digits into the filter box still types the digit. */
    fun focusInMainPanes(): Boolean {
        val f = window.focusedInteractable ?: return false
        return f === tablesList || f === propsList || diffPane.ownsFocus(f)
    }

    window.addWindowListener(object : WindowListenerAdapter() {
        // Lanterna's Tab traversal handles focus internally — `onUnhandledInput`
        // never fires for Tab, and tracking per-component `afterEnterFocus`
        // would mean subclassing every focusable widget. Instead defer one
        // refresh per input via the GUI thread; by the time it runs, focus has
        // settled wherever Lanterna routed it.
        override fun onInput(basePane: Window, keyStroke: KeyStroke, deliverEvent: AtomicBoolean) {
            when (keyStroke.keyType) {
                KeyType.Tab -> { moveFocus(+1); deliverEvent.set(false) }
                KeyType.ReverseTab -> { moveFocus(-1); deliverEvent.set(false) }
                else -> {}
            }
            gui.guiThread.invokeLater { applyFocusMarkers() }
        }
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
                    } else if (confirmQuit(gui, propertyActions.userEditCount())) {
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
                focusInMainPanes() && diffPane.handleKey(keyStroke) -> {
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.F2 -> {
                    runF2Apply()
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.F3 -> {
                    runF3Classify()
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.F7 -> {
                    runConfigView(gui, configState, dialect)
                    hasBeenHandled.set(true)
                }
                ThemeManager.handleKey(keyStroke, gui) -> {
                    refreshStatus()
                    gui.screen.refresh()
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.F1 || keyStroke.character == '?' -> {
                    showHelp(gui)
                    hasBeenHandled.set(true)
                }
                keyStroke.character == 'q' || keyStroke.character == 'Q' -> {
                    if (confirmQuit(gui, propertyActions.userEditCount())) {
                        outcome = Outcome.CANCEL
                        window.close()
                    }
                    hasBeenHandled.set(true)
                }
            }
        }
    })

    window.component = root
    window.focusedInteractable = tablesList
    applyFocusMarkers()
    gui.addWindowAndWait(window)

    return outcome
}

/**
 * Asks the user before discarding pending code edits. Returns true when the
 * caller may proceed — i.e. there's nothing pending, or the user picked Exit.
 * Cancel (or Esc inside the dialog) leaves the main window open.
 *
 * Uses [Window.Hint.NO_DECORATIONS] + our own [TitledBorder] so the frame
 * looks identical across every theme (Lanterna's bundled `default-theme`
 * leaves `windowdecoration` unset, which renders title-only stubs that look
 * borderless on the top and left — the custom themes use
 * `FatWindowDecorationRenderer` which draws a full frame).
 */
private fun confirmQuit(
    gui: com.googlecode.lanterna.gui2.WindowBasedTextGUI,
    pending: Int,
): Boolean {
    if (pending <= 0) return true

    var exit = false
    val dialog = com.googlecode.lanterna.gui2.BasicWindow()
    dialog.setHints(listOf(
        com.googlecode.lanterna.gui2.Window.Hint.CENTERED,
        com.googlecode.lanterna.gui2.Window.Hint.NO_DECORATIONS,
    ))

    val msg = "$pending property action${if (pending == 1) "" else "s"} you marked will be lost."

    val cancelBtn = plainButton("Cancel") { dialog.close() }
    val exitBtn = plainButton("Exit") { exit = true; dialog.close() }

    val buttons = com.googlecode.lanterna.gui2.Panel(
        com.googlecode.lanterna.gui2.LinearLayout(com.googlecode.lanterna.gui2.Direction.HORIZONTAL).setSpacing(2)
    )
    buttons.addComponent(cancelBtn)
    buttons.addComponent(exitBtn)

    val inner = com.googlecode.lanterna.gui2.Panel(
        com.googlecode.lanterna.gui2.LinearLayout(com.googlecode.lanterna.gui2.Direction.VERTICAL).setSpacing(0)
    )
    inner.addComponent(com.googlecode.lanterna.gui2.Label(" $msg "))
    inner.addComponent(com.googlecode.lanterna.gui2.EmptySpace(com.googlecode.lanterna.TerminalSize(1, 1)))
    inner.addComponent(buttons)
    inner.addComponent(com.googlecode.lanterna.gui2.EmptySpace(com.googlecode.lanterna.TerminalSize(1, 0)))

    dialog.component = inner.withTitledBorder("Discard pending changes?")
    dialog.focusedInteractable = cancelBtn

    dialog.addWindowListener(object : WindowListenerAdapter() {
        override fun onUnhandledInput(
            basePane: com.googlecode.lanterna.gui2.Window,
            keyStroke: KeyStroke,
            hasBeenHandled: java.util.concurrent.atomic.AtomicBoolean,
        ) {
            if (keyStroke.keyType == com.googlecode.lanterna.input.KeyType.Escape) {
                dialog.close()
                hasBeenHandled.set(true)
            }
        }
    })

    gui.addWindowAndWait(dialog)
    return exit
}

/**
 * Filter dimensions in the popup. **OR within a group, AND across groups** —
 * a row is visible only when it matches at least one checked option in *every*
 * group. Splitting categories this way avoids the "Views + DIFF rows" mix-up
 * that pure OR semantics produce: the user can now ask for "DIFF tables only"
 * by leaving Views unchecked while keeping the DIFF status options checked.
 */
private enum class FilterGroup(val label: String) {
    CARDINALITY("Cardinality"),
    KIND("Object kind"),
    TABLE_PRESENCE("Table presence"),
    COLUMN_STATUS("Column status");
}

private enum class RowCategory(
    val group: FilterGroup,
    val label: String,
    val accept: (onl.ycode.stormify.schemasync.model.TableEntry) -> Boolean,
) {
    TABLES(FilterGroup.KIND, "Tables", { !it.isView }),
    VIEWS(FilterGroup.KIND, "Views", { it.isView }),

    // Column-scope: gated on `status` so they only describe the state inside
    // slots that have both an entity and a DB table. The three DIFF
    // subdivisions can overlap (a single DIFF row may have a type mismatch
    // *and* a missing DB field) — that is intentional: pick the union you
    // care about. `In sync` stands alone since SYNCED rules out all three.
    SYNCED(FilterGroup.COLUMN_STATUS, "In sync", { it.status == TableStatus.SYNCED }),
    TYPE_CONFLICTS(FilterGroup.COLUMN_STATUS, "Type conflicts",
        { it.status == TableStatus.DIFF && it.hasTypeMismatch }),
    MISSING_DB_FIELDS(FilterGroup.COLUMN_STATUS, "Missing DB fields",
        { it.status == TableStatus.DIFF && it.hasMissingDbFields }),
    MISSING_KOTLIN_FIELDS(FilterGroup.COLUMN_STATUS, "Missing Kotlin fields",
        { it.status == TableStatus.DIFF && it.hasMissingKotlinFields }),

    // Table-scope: every slot is in **exactly one** of the three. `Both`
    // covers SYNCED and DIFF (slot has both sides); the other two cover the
    // whole-table absences. Mutually exclusive and exhaustive.
    BOTH_PRESENT(FilterGroup.TABLE_PRESENCE, "Both",
        { it.status == TableStatus.SYNCED || it.status == TableStatus.DIFF }),
    DB_ONLY(FilterGroup.TABLE_PRESENCE, "DB-only tables", { it.status == TableStatus.DB_ONLY }),
    ENTITY_ONLY(FilterGroup.TABLE_PRESENCE, "Entity-only tables", { it.status == TableStatus.ENTITY_ONLY }),

    SINGLE_ENTITY(FilterGroup.CARDINALITY, "Single entity", { it.entityCount <= 1 }),
    MULTI_ENTITY(FilterGroup.CARDINALITY, "Multi-entity", { it.entityCount > 1 });

    override fun toString(): String = label
}

/** Mutable selection set backing the filter popup. Default = every option in
 *  every group except `In sync` — surfaces every row that needs attention while
 *  still satisfying the AND-across-groups rule. */
private class CategoryFilter(initial: Set<RowCategory>) {
    private val selected: MutableSet<RowCategory> = initial.toMutableSet()

    fun snapshot(): Set<RowCategory> = selected.toSet()
    fun replace(next: Set<RowCategory>) { selected.clear(); selected += next }

    /** AND across groups: every group must contribute at least one match. A
     *  group with **zero** checked options vacuously fails the AND, so the row
     *  is rejected — the user opted everything out of that dimension.
     *
     *  Column-status options describe what's happening *inside* a slot that
     *  has both sides. For DB-only / entity-only rows there is no column-level
     *  status, so the column-status group is treated as vacuously true and the
     *  row's visibility is governed solely by [FilterGroup.TABLE_PRESENCE]. */
    fun accept(entry: onl.ycode.stormify.schemasync.model.TableEntry): Boolean =
        FilterGroup.entries.all { group ->
            if (group == FilterGroup.COLUMN_STATUS &&
                entry.status != TableStatus.SYNCED &&
                entry.status != TableStatus.DIFF) return@all true
            selected.any { it.group == group && it.accept(entry) }
        }

    fun summary(): String {
        val total = RowCategory.entries.size
        return when {
            selected.isEmpty() -> "(none)"
            selected.size == total -> "All"
            selected.size == 1 -> selected.first().label
            else -> "${selected.size}/$total categories"
        }
    }

    companion object {
        fun defaultSelection() = CategoryFilter(RowCategory.entries.toSet() - RowCategory.SYNCED)
    }
}

/**
 * Modal popup with one [CheckBoxList] per [FilterGroup], stacked under a
 * header [Label]. Returns true when the user confirmed (OK), and [filter] has
 * been updated in place. Cancel / Esc leaves the filter untouched.
 */
private fun showCategoryPicker(
    gui: com.googlecode.lanterna.gui2.WindowBasedTextGUI,
    filter: CategoryFilter,
): Boolean {
    val snapshot = filter.snapshot()
    val groupLists: Map<FilterGroup, CheckBoxList<RowCategory>> = FilterGroup.entries.associateWith { group ->
        CheckBoxList<RowCategory>().apply {
            RowCategory.entries.filter { it.group == group }
                .forEach { addItem(it, it in snapshot) }
        }
    }

    val window = BasicWindow("Show categories")
    window.setHints(listOf(Window.Hint.CENTERED, Window.Hint.MODAL))

    var confirmed = false
    val ok = plainButton("OK") {
        val next = groupLists.values.flatMap { list ->
            (0 until list.itemCount).mapNotNull { i ->
                list.getItemAt(i).takeIf { list.isChecked(it) }
            }
        }.toSet()
        filter.replace(next)
        confirmed = true
        window.close()
    }
    val cancel = plainButton("Cancel") { window.close() }

    // Column status only describes Both rows; when Both is unchecked the
    // options can't filter anything (CategoryFilter.accept short-circuits the
    // group for non-Both rows). Keep the rows visible to avoid layout
    // jitter, but render them as plain labels (no `[x]`/`[ ]` brackets) and
    // disable focus so the user sees they're inert. The internal checked
    // state is preserved, so re-checking Both restores the prior selection.
    val tablePresenceList = groupLists.getValue(FilterGroup.TABLE_PRESENCE)
    val columnStatusList = groupLists.getValue(FilterGroup.COLUMN_STATUS)
    columnStatusList.setListItemRenderer(InertWhenDisabledCheckBoxRenderer())
    fun syncColumnStatusEnabled() {
        columnStatusList.isEnabled = tablePresenceList.isChecked(RowCategory.BOTH_PRESENT) == true
        columnStatusList.invalidate()
    }
    tablePresenceList.addListener { _, _ -> syncColumnStatusEnabled() }

    fun setAll(checked: Boolean) {
        groupLists.values.forEach { list ->
            (0 until list.itemCount).forEach { i ->
                list.setChecked(list.getItemAt(i), checked)
            }
        }
        syncColumnStatusEnabled()
    }
    val selectAll = plainButton("Select all") { setAll(true) }
    val deselectAll = plainButton("Deselect all") { setAll(false) }

    val buttons = Panel(LinearLayout(Direction.HORIZONTAL))
    buttons.addComponent(selectAll)
    buttons.addComponent(EmptySpace(TerminalSize(1, 1)))
    buttons.addComponent(deselectAll)
    buttons.addComponent(EmptySpace(TerminalSize(3, 1)))
    buttons.addComponent(ok)
    buttons.addComponent(EmptySpace(TerminalSize(2, 1)))
    buttons.addComponent(cancel)

    val root = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))
    FilterGroup.entries.forEachIndexed { idx, group ->
        if (idx > 0) root.addComponent(EmptySpace(TerminalSize(1, 1)))
        root.addComponent(Label("${Symbols.hbar}${Symbols.hbar} ${group.label} ${Symbols.hbar}${Symbols.hbar}"))
        root.addComponent(groupLists.getValue(group))
    }
    syncColumnStatusEnabled()
    root.addComponent(EmptySpace(TerminalSize(1, 1)))
    root.addComponent(Label("Space toggles · Tab moves between groups · Esc cancels"))
    root.addComponent(EmptySpace(TerminalSize(1, 1)))
    root.addComponent(buttons)

    window.component = root
    window.focusedInteractable = groupLists.getValue(FilterGroup.entries.first())

    window.addWindowListener(object : WindowListenerAdapter() {
        override fun onUnhandledInput(basePane: Window, keyStroke: KeyStroke, hasBeenHandled: AtomicBoolean) {
            when {
                keyStroke.keyType == KeyType.Escape -> {
                    window.close()
                    hasBeenHandled.set(true)
                }
                ThemeManager.handleKey(keyStroke, gui) -> {
                    gui.screen.refresh()
                    hasBeenHandled.set(true)
                }
            }
        }
    })

    gui.addWindowAndWait(window)
    return confirmed
}

/** Substitute the first two horizontal-bar fillers in [base] with `"● "` when
 *  [focused], preserving overall length so multi-segment titles keep aligning
 *  with the body's column separators. The trailing space prevents the marker
 *  from visually merging with the surrounding `─` run. Falls back to appending
 *  " ●" when the title has no filler (single-segment titles like `Property`). */
private fun markFocused(base: String, focused: Boolean): String {
    if (!focused) return base
    val hbar = Symbols.hbar[0]
    val idx = base.indexOf(hbar)
    if (idx < 0) return "$base ●"
    val sb = StringBuilder(base)
    sb[idx] = '●'
    if (idx + 1 < sb.length && sb[idx + 1] == hbar) sb[idx + 1] = ' '
    return sb.toString()
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
    val scrollbarMouse = ScrollbarMouse()

    // Lanterna emits no native double-click; we detect it from CLICK_DOWN.
    private var lastClickRow: Int = -1
    private var lastClickAt: Long = 0
    private val doubleClickWindowMs: Long = 400

    init {
        renderer = asciiRenderer
        setListItemRenderer(PersistentSelectionItemRenderer())
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
        keyStroke.asMouse?.let { m ->
            if (handleScrollbarMouse(m)) return Interactable.Result.HANDLED
        }
        val before = selectedIndex
        val r = super.handleKeyStroke(keyStroke)
        if (selectedIndex != before) onSelectionChanged?.invoke()
        keyStroke.asMouse?.let { m ->
            if (m.actionType == com.googlecode.lanterna.input.MouseActionType.CLICK_DOWN && onSpace != null) {
                val now = System.currentTimeMillis()
                val row = selectedIndex
                if (row >= 0 && row == lastClickRow && now - lastClickAt <= doubleClickWindowMs) {
                    onSpace?.invoke()
                    lastClickRow = -1
                    lastClickAt = 0
                } else {
                    lastClickRow = row
                    lastClickAt = now
                }
            }
        }
        return when (r) {
            Interactable.Result.MOVE_FOCUS_UP,
            Interactable.Result.MOVE_FOCUS_DOWN -> Interactable.Result.HANDLED
            else -> r
        }
    }

    private fun handleScrollbarMouse(action: com.googlecode.lanterna.input.MouseAction): Boolean {
        val sz = size ?: return false
        if (itemCount <= sz.rows) return false
        val local = toLocal(action.position) ?: return false
        val newIndex = scrollbarMouse.handle(
            action = action,
            localX = local.column,
            localY = local.row,
            scrollbarCol = sz.columns - 1,
            height = sz.rows,
            contentRows = itemCount,
            viewportRows = 1,
            currentTop = selectedIndex.coerceAtLeast(0),
        ) ?: return action.actionType == com.googlecode.lanterna.input.MouseActionType.DRAG
        val before = selectedIndex
        selectedIndex = newIndex.coerceIn(0, itemCount - 1)
        if (selectedIndex != before) onSelectionChanged?.invoke()
        return true
    }
}
