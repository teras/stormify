package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.gui2.ActionListBox
import com.googlecode.lanterna.gui2.BasicWindow
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
import onl.ycode.stormify.schemasync.classifier.ClassificationCache
import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.entity.ColumnDelta
import onl.ycode.stormify.schemasync.entity.TableDiff
import onl.ycode.stormify.schemasync.model.SlotCategory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * F3 Bulk classify — left pane lists every ENTITY_ONLY classifiable column,
 * right pane is a read-only display of slots for the active category with the
 * row's slot highlighted. Tab / Shift-Tab cycles the category; ←/→ shift the
 * slot relatively; type-ahead builds a `contains` filter; Space toggles row
 * selection; 1-9 applies the slot at that index.
 */
fun runBulkClassifyView(
    gui: WindowBasedTextGUI,
    state: ConfigState,
    cache: ClassificationCache,
    diffs: Collection<TableDiff>,
) {
    val window = BasicWindow("Bulk classify")
    window.setHints(listOf(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS))

    var activeCategory: SlotCategory = SlotCategory.TEXT
    var filter = ""
    val selected: MutableMap<SlotCategory, MutableSet<String>> =
        SlotCategory.entries.associateWith { mutableSetOf<String>() }.toMutableMap()
    val rowsAll: List<ClassifyRow> = collectClassifiableRows(diffs)

    var renderAllRef: () -> Unit = {}
    var cycleCategoryToNonEmptyRef: (Int) -> Unit = {}
    var fieldsBorderedRef: TitledBorder? = null
    val tabButtons: Map<SlotCategory, ClickableLabel> = SlotCategory.entries.associateWith { c ->
        ClickableLabel("") { activeCategory = c; renderAllRef() }
    }
    val tabsRow = Panel(LinearLayout(Direction.HORIZONTAL).setSpacing(1)).apply {
        SlotCategory.entries.forEach { addComponent(tabButtons.getValue(it)) }
    }
    val filterLeft = Label("")
    val filterRight = Label("")
    val filterRow = Panel(LinearLayout(Direction.HORIZONTAL).setSpacing(0)).apply {
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.None)
        addComponent(filterLeft)
        addComponent(EmptySpace(TerminalSize(0, 1)).apply {
            layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
        })
        addComponent(filterRight)
    }

    // Forward-declared cursor-change callback: the list subclass below captures
    // it by reference so the real body (defined further down) can use helpers
    // that, in turn, need to know the list exists.
    var onFieldsCursorChanged: () -> Unit = {}

    val slotsPanel = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))
    val slotLabels = mutableListOf<Label>()
    val fieldsList = object : ActionListBox() {
        private val scrollbarMouse = ScrollbarMouse()

        override fun handleKeyStroke(keyStroke: KeyStroke): Interactable.Result {
            if (keyStroke.character == ' '
                || (keyStroke.character?.let { it in '1'..'9' } == true)
            ) return Interactable.Result.UNHANDLED
            keyStroke.asMouse?.let { m ->
                val sz = size
                if (sz != null && itemCount > sz.rows) {
                    val local = toLocal(m.position)
                    if (local != null) {
                        val newIndex = scrollbarMouse.handle(
                            action = m,
                            localX = local.column,
                            localY = local.row,
                            scrollbarCol = sz.columns - 1,
                            height = sz.rows,
                            contentRows = itemCount,
                            viewportRows = sz.rows,
                            currentTop = selectedIndex.coerceAtLeast(0),
                        )
                        if (newIndex != null) {
                            val before = selectedIndex
                            selectedIndex = newIndex.coerceIn(0, itemCount - 1)
                            if (selectedIndex != before) onFieldsCursorChanged()
                            return Interactable.Result.HANDLED
                        }
                        if (m.actionType == com.googlecode.lanterna.input.MouseActionType.DRAG)
                            return Interactable.Result.HANDLED
                    }
                }
            }
            val before = selectedIndex
            val r = super.handleKeyStroke(keyStroke)
            // Lanterna's ActionListBox returns MOVE_FOCUS_UP/DOWN at the list
            // edges; intercept and cycle the category instead so navigation
            // wraps without ever leaving the fields list.
            if (keyStroke.keyType == KeyType.ArrowUp && r == Interactable.Result.MOVE_FOCUS_UP) {
                cycleCategoryToNonEmptyRef(-1)
                if (itemCount > 0) selectedIndex = itemCount - 1
                onFieldsCursorChanged()
                return Interactable.Result.HANDLED
            }
            if (keyStroke.keyType == KeyType.ArrowDown && r == Interactable.Result.MOVE_FOCUS_DOWN) {
                cycleCategoryToNonEmptyRef(+1)
                if (itemCount > 0) selectedIndex = 0
                onFieldsCursorChanged()
                return Interactable.Result.HANDLED
            }
            if (selectedIndex != before) onFieldsCursorChanged()
            return r
        }
    }

    fun visibleRows(): List<ClassifyRow> {
        val raw = filter.lowercase()
        val negate = raw.startsWith("!")
        val afterNeg = if (negate) raw.drop(1) else raw
        val slotMode = afterNeg.startsWith(":")
        val body = if (slotMode) afterNeg.drop(1) else afterNeg
        val regex: Regex? = if (body.isEmpty()) null else {
            val pattern = body.split('*').joinToString(".*") { Regex.escape(it) }
            Regex(pattern)
        }
        return rowsAll.filter { row ->
            if (row.category != activeCategory) return@filter false
            if (regex == null) return@filter true
            val hit = if (slotMode) {
                val slot = (cache.slotFor(row.columnKey)
                    ?: state.current.slots.defaultName(row.category) ?: "").lowercase()
                regex.containsMatchIn(slot)
            } else {
                regex.containsMatchIn(row.label.lowercase()) ||
                    regex.containsMatchIn(row.columnKey.lowercase())
            }
            if (negate) !hit else hit
        }
    }

    fun slotNames(): List<String> = when (activeCategory) {
        SlotCategory.TEXT -> state.current.slots.text.map { it.name }
        SlotCategory.INTEGRAL -> state.current.slots.integral.map { it.name }
        SlotCategory.DECIMAL -> state.current.slots.decimal.map { it.name }
    }

    fun slotEntries(): List<Pair<String, String>> = when (activeCategory) {
        SlotCategory.TEXT -> state.current.slots.text.map { it.name to it.ddl }
        SlotCategory.INTEGRAL -> state.current.slots.integral.map { it.name to it.ddl }
        SlotCategory.DECIMAL -> state.current.slots.decimal.map { it.name to it.ddl }
    }

    fun renderFieldsListItems() {
        val keep = fieldsList.selectedIndex.coerceAtLeast(0)
        fieldsList.clearItems()
        val rows = visibleRows()
        val sel = selected.getValue(activeCategory)
        val names = slotNames()
        val width = names.size
        rows.forEach { row ->
            val mark = if (row.columnKey in sel) Symbols.selectMark else Symbols.selectEmpty
            val current = cache.slotFor(row.columnKey) ?: state.current.slots.defaultName(row.category) ?: "?"
            val idx = names.indexOf(current)
            val indicator = buildString(width) {
                for (i in 0 until width) append(if (i == idx) Symbols.slotMark else Symbols.slotEmpty)
            }
            fieldsList.addItem("$mark ${row.label.padEnd(40)} $indicator $current", Runnable {})
        }
        if (keep in 0 until fieldsList.itemCount) fieldsList.selectedIndex = keep
    }

    fun applyToSelectionOrCursor(slotName: String) {
        val sel = selected.getValue(activeCategory)
        val targets: List<ClassifyRow> = if (sel.isEmpty()) {
            visibleRows().getOrNull(fieldsList.selectedIndex)?.let(::listOf) ?: emptyList()
        } else {
            visibleRows().filter { it.columnKey in sel }
        }
        targets.forEach { cache.put(it.columnKey, activeCategory, slotName) }
        renderFieldsListItems()
    }

    fun syncSlotsHighlight() {
        val rows = visibleRows()
        val currentRow = rows.getOrNull(fieldsList.selectedIndex)
        val currentSlot = currentRow?.let {
            cache.slotFor(it.columnKey) ?: state.current.slots.defaultName(activeCategory)
        }
        val names = slotNames()
        slotLabels.forEachIndexed { i, label ->
            if (names.getOrNull(i) == currentSlot) {
                label.foregroundColor = TextColor.ANSI.WHITE
                label.backgroundColor = TextColor.ANSI.BLUE
            } else {
                label.foregroundColor = null
                label.backgroundColor = null
            }
        }
    }

    val allSlotEntries: List<Pair<String, String>> =
        state.current.slots.text.map { it.name to it.ddl } +
            state.current.slots.integral.map { it.name to it.ddl } +
            state.current.slots.decimal.map { it.name to it.ddl }
    val slotsNameWidth = allSlotEntries.maxOfOrNull { it.first.length } ?: 0
    val slotsDdlWidth = allSlotEntries.maxOfOrNull { it.second.length } ?: 0
    val slotsRowWidth = 3 + slotsNameWidth + 2 + slotsDdlWidth
    val slotsBoxWidth = slotsRowWidth + 2

    fun rebuildSlotsPanel() {
        slotsPanel.removeAllComponents()
        slotLabels.clear()
        val entries = slotEntries()
        entries.forEachIndexed { i, (name, ddl) ->
            val key = if (i < 9) "${i + 1}." else "  "
            val text = "$key ${name.padEnd(slotsNameWidth)}  ${ddl.padEnd(slotsDdlWidth)}"
            val label = Label(text)
            slotLabels += label
            slotsPanel.addComponent(label)
        }
    }

    fun renderHeader() {
        SlotCategory.entries.forEach { c ->
            val n = rowsAll.count { it.category == c }
            val active = c == activeCategory
            val open = if (active) "[" else " "
            val close = if (active) "]" else " "
            tabButtons.getValue(c).label = "$open ${c.name} $n $close"
        }
        fieldsBorderedRef?.title = "${activeCategory.name} Fields"
        val sel = selected.getValue(activeCategory).size
        filterLeft.text = if (filter.isEmpty()) "Filter: (type to filter, Backspace / Ctrl+U to clear)"
                          else "Filter: $filter"
        filterRight.text = buildString {
            if (filter.isNotEmpty()) append("(${visibleRows().size} matches)")
            if (sel > 0) {
                if (isNotEmpty()) append(" · ")
                append("$sel selected")
            }
            if (isNotEmpty()) append(" ")
            append("[! = exclude, : = slot, * = wildcard]")
        }
    }

    fun renderAll() {
        renderHeader()
        rebuildSlotsPanel()
        renderFieldsListItems()
        syncSlotsHighlight()
    }
    renderAllRef = ::renderAll

    fun toggleCurrentSelection() {
        val key = visibleRows().getOrNull(fieldsList.selectedIndex)?.columnKey ?: return
        val sel = selected.getValue(activeCategory)
        if (key in sel) sel -= key else sel += key
        renderFieldsListItems()
        renderHeader()
    }

    fun cycleCategory(step: Int) {
        val cats = SlotCategory.entries
        val n = cats.size
        activeCategory = cats[((cats.indexOf(activeCategory) + step) % n + n) % n]
        renderAll()
        if (fieldsList.itemCount > 0) fieldsList.selectedIndex = 0
        syncSlotsHighlight()
    }

    /** Like [cycleCategory] but skips categories with no rows; used by the
     *  ↑/↓ edge wrap so the user lands on actionable content. Falls back to a
     *  plain cycle when every category is empty. */
    fun cycleCategoryToNonEmpty(step: Int) {
        val cats = SlotCategory.entries
        val n = cats.size
        val start = cats.indexOf(activeCategory)
        for (i in 1..n) {
            val candidate = cats[((start + step * i) % n + n) % n]
            if (rowsAll.any { it.category == candidate }) {
                activeCategory = candidate
                renderAll()
                if (fieldsList.itemCount > 0) fieldsList.selectedIndex = 0
                syncSlotsHighlight()
                return
            }
        }
    }

    fun shiftSlot(step: Int) {
        val names = slotNames()
        if (names.isEmpty()) return
        val sel = selected.getValue(activeCategory)
        val targets: List<ClassifyRow> = if (sel.isEmpty()) {
            visibleRows().getOrNull(fieldsList.selectedIndex)?.let(::listOf) ?: emptyList()
        } else {
            visibleRows().filter { it.columnKey in sel }
        }
        val n = names.size
        targets.forEach { row ->
            val current = cache.slotFor(row.columnKey)
                ?: state.current.slots.defaultName(activeCategory)
                ?: names.first()
            val idx = names.indexOf(current).let { if (it < 0) 0 else it }
            val newIdx = ((idx + step) % n + n) % n
            cache.put(row.columnKey, activeCategory, names[newIdx])
        }
        renderFieldsListItems()
        syncSlotsHighlight()
    }

    cycleCategoryToNonEmptyRef = ::cycleCategoryToNonEmpty
    onFieldsCursorChanged = { syncSlotsHighlight() }

    val slotsMaxCount = maxOf(
        state.current.slots.text.size,
        state.current.slots.integral.size,
        state.current.slots.decimal.size,
    )
    val slotsBordered = slotsPanel.withTitledBorder("Slots").apply {
        preferredSize = TerminalSize(slotsBoxWidth, slotsMaxCount.coerceAtLeast(1) + 2)
    }
    val fieldsBordered = fieldsList.withTitledBorder("Fields").apply {
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
    }
    fieldsBorderedRef = fieldsBordered
    val workspace = Panel(LinearLayout(Direction.HORIZONTAL).setSpacing(0)).apply {
        addComponent(fieldsBordered)
        addComponent(slotsBordered)
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
    }

    val root = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))
    root.addComponent(tabsRow)
    root.addComponent(filterRow)
    root.addComponent(workspace)
    root.addComponent(EmptySpace(TerminalSize(1, 1)))
    root.addComponent(Label(
        "Type to filter · Tab/Shift-Tab category · ←→ shift slot · Space select · 0 clear · 1-9 apply · Esc back",
    ))

    window.component = root
    window.focusedInteractable = fieldsList
    renderAll()
    if (fieldsList.itemCount > 0) fieldsList.selectedIndex = 0
    syncSlotsHighlight()

    window.addWindowListener(object : WindowListenerAdapter() {
        // Intercept category / slot keys at the window level so they work
        // identically whether focus is on the fields list or on a tab button.
        override fun onInput(basePane: Window, keyStroke: KeyStroke, deliverEvent: AtomicBoolean) {
            when (keyStroke.keyType) {
                KeyType.Tab -> { cycleCategory(+1); deliverEvent.set(false) }
                KeyType.ReverseTab -> { cycleCategory(-1); deliverEvent.set(false) }
                KeyType.ArrowLeft -> { shiftSlot(-1); deliverEvent.set(false) }
                KeyType.ArrowRight -> { shiftSlot(+1); deliverEvent.set(false) }
                else -> {}
            }
        }
        override fun onUnhandledInput(basePane: Window, keyStroke: KeyStroke, hasBeenHandled: AtomicBoolean) {
            when {
                keyStroke.keyType == KeyType.F1 || keyStroke.character == '?' -> {
                    showHelp(gui); hasBeenHandled.set(true)
                }
                ThemeManager.handleKey(keyStroke, gui) -> {
                    gui.screen.refresh(); hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.Escape -> {
                    window.close()
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.Backspace -> {
                    if (filter.isNotEmpty()) {
                        filter = filter.dropLast(1); renderAll()
                    }
                    hasBeenHandled.set(true)
                }
                keyStroke.isCtrlDown && keyStroke.character == 'u' -> {
                    if (filter.isNotEmpty()) { filter = ""; renderAll() }
                    hasBeenHandled.set(true)
                }
                keyStroke.character == ' ' -> {
                    toggleCurrentSelection(); hasBeenHandled.set(true)
                }
                keyStroke.character == '0' -> {
                    selected.getValue(activeCategory).clear()
                    renderFieldsListItems(); renderHeader()
                    hasBeenHandled.set(true)
                }
                keyStroke.character?.let { it in '1'..'9' } == true -> {
                    slotNames().getOrNull(keyStroke.character.digitToInt() - 1)
                        ?.let(::applyToSelectionOrCursor)
                    if (selected.getValue(activeCategory).isEmpty()) {
                        val next = (fieldsList.selectedIndex + 1)
                            .coerceAtMost(fieldsList.itemCount - 1)
                        if (next != fieldsList.selectedIndex) {
                            fieldsList.selectedIndex = next
                            syncSlotsHighlight()
                        }
                    }
                    hasBeenHandled.set(true)
                }
                keyStroke.character?.let { it.isLetterOrDigit() || it == '_' || it == '.' || it == '!' || it == '*' || it == ':' } == true -> {
                    filter += keyStroke.character.toString()
                    renderAll()
                    hasBeenHandled.set(true)
                }
            }
        }
    })

    gui.addWindowAndWait(window)
}

/** A row in the bulk classify list. */
private data class ClassifyRow(
    val columnKey: String,
    val category: SlotCategory,
    val label: String,
)

private fun collectClassifiableRows(diffs: Collection<TableDiff>): List<ClassifyRow> {
    val out = mutableListOf<ClassifyRow>()
    for (diff in diffs) {
        val entity = diff.primary ?: continue
        val className = entity.className.substringAfterLast('.')
        for (delta in diff.columnDeltas) {
            if (delta.kind != ColumnDelta.Kind.ENTITY_ONLY) continue
            val field = delta.entityField ?: continue
            val cat = field.category ?: continue
            out += ClassifyRow(
                columnKey = "${diff.tableKey}.${field.column}",
                category = cat,
                label = "$className.${field.name}",
            )
        }
    }
    return out.sortedBy { it.label }
}
