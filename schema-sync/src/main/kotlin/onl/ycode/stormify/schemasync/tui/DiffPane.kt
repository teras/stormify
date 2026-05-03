package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.gui2.CheckBoxList
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.EmptySpace
import com.googlecode.lanterna.gui2.Interactable
import com.googlecode.lanterna.gui2.Label
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.input.KeyStroke
import onl.ycode.stormify.schemasync.classifier.ClassificationCache
import onl.ycode.stormify.schemasync.classifier.SchemaClassifier
import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.db.Dialect
import onl.ycode.stormify.schemasync.db.MigrationGenerator
import onl.ycode.stormify.schemasync.entity.ColumnDelta
import onl.ycode.stormify.schemasync.entity.EntityStyleDetector
import onl.ycode.stormify.schemasync.entity.KotlinEntity
import onl.ycode.stormify.schemasync.entity.TableDiff
import onl.ycode.stormify.schemasync.entity.source.PsiEnvironment
import onl.ycode.stormify.schemasync.model.SlotCategory
import onl.ycode.stormify.schemasync.model.TableStatus

private const val NAME_WIDTH = 12
private const val DDL_WIDTH = 18

/**
 * Right pane: live preview of what F2 would write for the currently-selected
 * table. Renders three colour-coded sections — `DB needs` (ALTER TABLE
 * statements, sourced from [MigrationGenerator]), `<Entity> needs` (Kotlin
 * property declarations, sourced from [EntityWriter]), and `Type conflicts`
 * (side-by-side diff for type/capacity mismatches). For ENTITY_ONLY fields
 * with a classifiable category, a slot picker appears at the bottom; selecting
 * a slot reflects immediately in the SQL preview.
 */
class DiffPane(
    private val state: ConfigState,
    private val classifier: SchemaClassifier,
    private val cache: ClassificationCache,
    private val actions: PropertyActions,
    private val onAssigned: () -> Unit,
    private val dialect: Dialect = Dialect.GENERIC,
    private val entities: List<KotlinEntity> = emptyList(),
) {
    private val entityStyle by lazy { EntityStyleDetector.detect(entities) }
    private val psiEnv by lazy { PsiEnvironment() }

    private val container: Panel = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))
    private val diffViewer = DiffViewer(listOf(DiffLine("(no table selected)"))).apply {
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
    }
    private val targetsHeader = Label("")
    private val targetsList = CheckBoxList<String>()
    private val slotsHeader = Label("")
    private val slotsList = SelectionAwareListBox()
    private val bordered: TitledBorder = container.withTitledBorder("Diff")

    /** The bordered component to drop into the parent layout. Title updates dynamically. */
    val borderedComponent: TitledBorder get() = bordered

    private var currentDiff: TableDiff? = null
    private var currentDelta: ColumnDelta? = null
    private var currentSlots: List<Pair<String, String>> = emptyList()

    /** Set while [renderTargetsPicker] is repopulating the checkbox list, so
     *  the listener can ignore the burst of `setChecked` callbacks Lanterna
     *  fires for each addItem(checked=true). Without this guard each render
     *  re-toggles every entry against the persistent action state. */
    private var suppressTargetsListener = false

    init {
        container.addComponent(diffViewer)
        container.addComponent(EmptySpace(TerminalSize(1, 1)))
        container.addComponent(targetsHeader)
        container.addComponent(targetsList)
        container.addComponent(slotsHeader)
        container.addComponent(slotsList)

        // Attach exactly once. Earlier the listener was added inside
        // renderTargetsPicker on every redraw, leaking N listeners after N
        // slot navigations and turning each user toggle into N renderDiff()
        // calls — enough to freeze the UI on a multi-entity slot.
        targetsList.addListener { idx, _ ->
            if (suppressTargetsListener) return@addListener
            val diff = currentDiff ?: return@addListener
            if (idx !in diff.entities.indices) return@addListener
            val primary = diff.entities.first().className
            val target = diff.entities[idx].className
            actions.toggleTarget(diff.tableKey, primary, target)
            renderDiff()
            onAssigned()
        }
    }

    /** Re-render based on current table diff and selected delta. */
    fun showFor(diff: TableDiff?, delta: ColumnDelta?) {
        currentDiff = diff
        currentDelta = delta
        renderDiff()
        renderTargetsPicker()
        renderSlotPicker()
    }

    /** Re-render diff text without changing the current selection — used after Space toggles. */
    fun refresh() {
        renderDiff()
        renderTargetsPicker()
        renderSlotPicker()
    }

    /** Show one checkbox per claiming entity when the slot has more than one
     *  claim AND there is at least one DB column missing from the union of
     *  entities (i.e. an actual INSERT splice would be emitted). Toggling
     *  determines which entities receive those new properties. Hidden in the
     *  common 1:1 case and on fully-synced multi-entity slots where there is
     *  nothing to insert anywhere. */
    private fun renderTargetsPicker() {
        suppressTargetsListener = true
        try {
            targetsList.clearItems()
            val diff = currentDiff
            val hasInsertableColumns = diff?.columnDeltas?.any { it.kind == ColumnDelta.Kind.DB_ONLY } == true
            if (diff == null || diff.entities.size < 2 || !hasInsertableColumns) {
                targetsHeader.text = ""
                return
            }
            val primary = diff.entities.first().className
            val checked = actions.targetsFor(diff.tableKey, primary)
            targetsHeader.text = "Insert new columns into:"
            diff.entities.forEach { e ->
                val short = e.className.substringAfterLast('.')
                targetsList.addItem(short, e.className in checked)
            }
        } finally {
            suppressTargetsListener = false
        }
    }

    /** Whether the right pane currently owns focus. Drives the leading "● "
     *  marker injected by [applyTitle]. Set externally by the window dispatcher
     *  after each navigation event. */
    var paneFocused: Boolean = false
        set(value) {
            field = value
            applyTitle(currentBaseTitle)
        }

    private var currentBaseTitle: String = "Diff"

    private fun applyTitle(base: String) {
        currentBaseTitle = base
        bordered.title = base + (if (paneFocused) " ●" else "")
    }

    private fun renderDiff() {
        val diff = currentDiff
        if (diff == null) {
            applyTitle("Diff")
            diffViewer.setLines(listOf(DiffLine("(no table selected)")))
            return
        }
        val sep = Symbols.dash
        applyTitle(when (diff.status) {
            TableStatus.SYNCED -> "Synced $sep ${diff.tableKey}"
            TableStatus.DIFF -> "Diff $sep ${diff.tableKey}"
            TableStatus.ENTITY_ONLY -> "New table $sep ${diff.tableKey}"
            TableStatus.DB_ONLY -> "New entity $sep ${pascalCase(diff.tableKey.substringAfterLast('.'))}"
        })
        diffViewer.setLines(buildDiffLines(diff))
    }

    /** Builds the live diff content for [diff]: SQL ALTERs (sourced from
     *  [MigrationGenerator]), Kotlin property additions / new entity files
     *  (sourced from the same [renderEntitiesDiffLines] F2 uses), plus the
     *  per-column type-mismatch summary. */
    private fun buildDiffLines(diff: TableDiff): List<DiffLine> {
        val out = mutableListOf<DiffLine>()
        val entityOnly = diff.columnDeltas.filter { it.kind == ColumnDelta.Kind.ENTITY_ONLY }
        val dbOnly = diff.columnDeltas.filter { it.kind == ColumnDelta.Kind.DB_ONLY }
        val typeMismatch = diff.columnDeltas.filter { it.kind == ColumnDelta.Kind.TYPE_MISMATCH }

        if (entityOnly.isEmpty() && dbOnly.isEmpty() && typeMismatch.isEmpty()) {
            out += DiffLine("(in sync ${Symbols.dash} entity matches DB)")
            return out
        }

        if (entityOnly.isNotEmpty()) {
            out += DiffLine("${Symbols.rule} DB needs ${Symbols.rule}")
            // For ENTITY_ONLY tables the whole table is missing → CREATE TABLE.
            // For DIFF tables only the listed columns are missing → ALTER TABLE.
            val sqlLines = if (diff.status == TableStatus.ENTITY_ONLY) {
                MigrationGenerator.createTableStatementFor(
                    diff, state.current.slots, state.current.defaults, cache, dialect, entities,
                )
            } else {
                MigrationGenerator.alterStatementsFor(
                    diff, state.current.slots, state.current.defaults, cache, dialect, entities,
                )
            }
            for (line in sqlLines) out += DiffLine("+$line", TextColor.ANSI.GREEN)
            if (sqlLines.isEmpty()) {
                for (d in entityOnly) {
                    val field = d.entityField ?: continue
                    out += DiffLine(
                        "+/* ${field.column} <unclassified ${field.type}> */",
                        TextColor.ANSI.YELLOW,
                    )
                }
            }
            out += DiffLine("")
        }

        if (dbOnly.isNotEmpty() || diff.status == TableStatus.DB_ONLY) {
            // Delegate to ApplyView's renderEntitiesDiffLines so what we show
            // here is exactly what F2's "Entities" tab would render. We build
            // pending changes for just this table, then ask the same renderer.
            val pending = buildPendingChanges(
                entities = entities,
                diffs = listOf(diff),
                actions = actions,
                policy = state.current.namingPolicy,
                kotlinDefaults = state.current.kotlin,
            )
            if (!pending.isEmpty) {
                renderEntitiesDiffLines(pending, entityStyle, psiEnv).forEach { out += it }
            }
        }

        if (typeMismatch.isNotEmpty()) {
            out += DiffLine("${Symbols.rule} Type conflicts ${Symbols.rule}")
            for (d in typeMismatch) {
                val field = d.entityField ?: continue
                val col = d.dbColumn ?: continue
                val reason = d.mismatchReason?.let { " ${Symbols.dash} $it" } ?: ""
                out += DiffLine("${Symbols.neq} ${col.name}$reason", TextColor.ANSI.YELLOW)
                out += DiffLine("    DB:     ${col.dbType}", TextColor.ANSI.RED)
                out += DiffLine(
                    "    entity: ${field.type}${if (field.nullable) "?" else ""}  (${field.name})",
                    TextColor.ANSI.GREEN,
                )
            }
        }

        return out
    }


    private fun renderSlotPicker() {
        slotsList.clearItems()
        currentSlots = emptyList()
        val diff = currentDiff
        val delta = currentDelta
        if (diff == null || delta == null || delta.kind != ColumnDelta.Kind.ENTITY_ONLY) {
            slotsHeader.text = ""
            return
        }
        val field = delta.entityField ?: run { slotsHeader.text = ""; return }
        val cat = field.category ?: run { slotsHeader.text = ""; return }
        val act = actions.get(diff.tableKey, delta.name, delta.kind)
        if (act != PropertyAction.INSERT) {
            slotsHeader.text = ""
            return
        }
        val key = "${diff.tableKey}.${field.column}"
        val current = cache.slotFor(key)

        slotsHeader.text = "Slot for ${field.column} (${cat.name.lowercase()})"

        currentSlots = slotsFor(cat)
        currentSlots.forEachIndexed { i, (name, ddl) ->
            val marker = if (current == name) Symbols.arrow else " "
            val label = " $marker [${i + 1}] ${name.fit(NAME_WIDTH)} ${ddl.fit(DDL_WIDTH)}"
            slotsList.addItem(label) { assign(diff.tableKey, field.column, cat, name) }
        }
        if (currentSlots.isNotEmpty()) {
            slotsList.selectedIndex = current
                ?.let { s -> currentSlots.indexOfFirst { it.first == s } }
                ?.takeIf { it >= 0 }
                ?: 0
        }
    }

    private fun slotsFor(cat: SlotCategory): List<Pair<String, String>> = when (cat) {
        SlotCategory.TEXT -> state.current.slots.text.map { it.name to it.ddl }
        SlotCategory.INTEGRAL -> state.current.slots.integral.map { it.name to it.ddl }
        SlotCategory.DECIMAL -> state.current.slots.decimal.map { it.name to it.ddl }
    }

    private fun assign(tableKey: String, column: String, cat: SlotCategory, slotName: String) {
        classifier.train(cat, slotName, column)
        cache.put("$tableKey.$column", cat, slotName)
        renderDiff()
        renderSlotPicker()
        onAssigned()
    }

    /** Routes 1-9 keys to slot selection when the pane is focused. */
    fun handleKey(ks: KeyStroke): Boolean {
        if (currentSlots.isEmpty()) return false
        val ch = ks.character
        if (ch != null && ch in '1'..'9') {
            val idx = ch.digitToInt() - 1
            if (idx in currentSlots.indices) {
                slotsList.selectedIndex = idx
                slotsList.runSelectedItem()
                return true
            }
        }
        return false
    }

    val focusTarget: Interactable
        get() = if (targetsList.itemCount > 0) targetsList else slotsList
    val hasFocusableContent: Boolean
        get() = slotsList.itemCount > 0 || targetsList.itemCount > 0

    /** True when [target] is one of this pane's own focusable widgets — used by
     *  the window dispatcher to decide whether to mark the diff title focused.
     *  Includes [diffViewer] (the scrollable text body) since Lanterna's Tab
     *  traversal lands there first; checking only slots/targets would silently
     *  skip the most common landing spot. */
    fun ownsFocus(target: com.googlecode.lanterna.gui2.Interactable): Boolean =
        target === slotsList || target === targetsList || target === diffViewer
}

