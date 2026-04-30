package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.EmptySpace
import com.googlecode.lanterna.gui2.Interactable
import com.googlecode.lanterna.gui2.Label
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.input.KeyStroke
import onl.ycode.stormify.schemasync.classifier.SchemaClassifier
import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.entity.ColumnDelta
import onl.ycode.stormify.schemasync.model.SlotCategory

private const val NAME_WIDTH = 12
private const val DDL_WIDTH = 18

/**
 * Right column: per-property classifier UI when the focused middle row is a
 * classifiable entity field, info-only otherwise (FK, deterministic type,
 * only-db, only-entity-deterministic, type-mismatch). Auto-saves slot picks
 * via [ConfigState.setAssignment] and [SchemaClassifier.train].
 */
class ClassifierPane(
    private val state: ConfigState,
    private val classifier: SchemaClassifier,
    private val onAssigned: () -> Unit,
) {
    private val container: Panel = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))
    private val infoLabel = Label("Select a property")
    private val slotsList = SelectionAwareListBox()
    private val footer = Label("")

    val component: Panel get() = container

    private var currentSlots: List<Pair<String, String>> = emptyList()

    init {
        container.addComponent(infoLabel)
        container.addComponent(EmptySpace(TerminalSize(1, 1)))
        container.addComponent(slotsList)
        container.addComponent(EmptySpace(TerminalSize(1, 1)))
        container.addComponent(footer)
    }

    /** Re-render for the given middle-column row. */
    fun showFor(tableKey: String, delta: ColumnDelta?) {
        slotsList.clearItems()
        currentSlots = emptyList()

        if (delta == null) {
            infoLabel.text = "(no property selected)"
            footer.text = ""
            return
        }

        when (delta.kind) {
            ColumnDelta.Kind.TYPE_MISMATCH -> renderMismatch(delta)
            ColumnDelta.Kind.SYNCED -> renderSynced(delta)
            ColumnDelta.Kind.DB_ONLY -> renderDbOnly(delta)
            ColumnDelta.Kind.ENTITY_ONLY -> renderEntityOnly(tableKey, delta)
        }
    }

    private fun renderMismatch(delta: ColumnDelta) {
        val e = delta.entityField?.type ?: "?"
        val d = delta.dbColumn?.dbType ?: "?"
        infoLabel.text = "Type mismatch (read-only)\n" +
            "  entity: $e\n" +
            "  db:     $d\n" +
            "  reason: ${delta.mismatchReason ?: "differs"}"
        footer.text = "schema-sync does not emit ALTER COLUMN TYPE."
    }

    private fun renderSynced(delta: ColumnDelta) {
        val f = delta.entityField
        val c = delta.dbColumn
        infoLabel.text = "Synced\n" +
            "  property: ${f?.name ?: "?"}\n" +
            "  column:   ${c?.name ?: "?"}\n" +
            "  type:     ${c?.dbType ?: f?.type ?: "?"}"
        footer.text = "Space cycles insert/delete · Del clears."
    }

    private fun renderDbOnly(delta: ColumnDelta) {
        val c = delta.dbColumn ?: return
        infoLabel.text = "DB-only column (will be inserted into entity)\n" +
            "  column:   ${c.name}\n" +
            "  db type:  ${c.dbType}\n" +
            "  nullable: ${c.nullable}"
        footer.text = "Space cycles insert/delete · Del clears."
    }

    private fun renderEntityOnly(tableKey: String, delta: ColumnDelta) {
        val f = delta.entityField ?: return
        val cat = f.category
        if (cat == null) {
            // Deterministic Kotlin type (Boolean/LocalDate/...) or FK reference.
            val refNote = if (f.referencedEntity != null) "FK -> ${f.referencedEntity}" else "deterministic type"
            infoLabel.text = "Entity-only field (will be added to DB)\n" +
                "  property: ${f.name}\n" +
                "  type:     ${f.type}${if (f.nullable) "?" else ""}\n" +
                "  $refNote"
            footer.text = "Space cycles insert/delete · Del clears."
            return
        }
        val suggestions = classifier.classify(cat, f.column, topN = 3)
        val topSlot = suggestions.firstOrNull()?.slotKey
        val score = suggestions.firstOrNull()?.score ?: 0f
        val stars = when {
            score >= 15f -> "***"
            score >= 5f -> "**"
            score > 0f -> "*"
            else -> ""
        }
        val colKey = "$tableKey.${f.column}"
        val saved = state.current.assignments.firstOrNull { it.column == colKey }?.slot

        infoLabel.text = "Classify ${f.name} (${cat.name.lowercase()})\n" +
            "  type:     ${f.type}${if (f.nullable) "?" else ""}\n" +
            "  suggest:  ${topSlot ?: "(none)"} $stars\n" +
            "  current:  ${saved ?: "(none)"}"

        currentSlots = slotsFor(cat)
        currentSlots.forEachIndexed { i, (name, ddl) ->
            val marker = when {
                saved == name -> "*"
                name == topSlot -> Symbols.arrow
                else -> " "
            }
            val label = " $marker [${i + 1}] ${name.fit(NAME_WIDTH)} ${ddl.fit(DDL_WIDTH)}"
            slotsList.addItem(label) {
                assign(tableKey, delta, cat, name)
            }
        }
        if (currentSlots.isNotEmpty()) {
            slotsList.selectedIndex = saved
                ?.let { s -> currentSlots.indexOfFirst { it.first == s } }
                ?.takeIf { it >= 0 }
                ?: currentSlots.indexOfFirst { it.first == topSlot }.coerceAtLeast(0)
        }
        footer.text = "1-9 / Enter pick · Space cycles row action · Del clears"
    }

    private fun slotsFor(category: SlotCategory): List<Pair<String, String>> = when (category) {
        SlotCategory.TEXT -> state.current.slots.text.map { it.name to it.ddl }
        SlotCategory.INTEGRAL -> state.current.slots.integral.map { it.name to it.ddl }
        SlotCategory.DECIMAL -> state.current.slots.decimal.map { it.name to it.ddl }
    }

    private fun assign(tableKey: String, delta: ColumnDelta, category: SlotCategory, slotName: String) {
        val field = delta.entityField ?: return
        val colKey = "$tableKey.${field.column}"
        classifier.train(category, slotName, field.column)
        state.setAssignment(colKey, category, slotName)
        showFor(tableKey, delta)
        onAssigned()
    }

    /**
     * Routes a key to the slot list when the right pane is focused. Returns
     * true when handled. The window-level dispatcher should call this only
     * when the current focus is inside the pane.
     */
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

    /** The interactable that should receive focus when the user moves to the right pane. */
    val focusTarget: Interactable get() = slotsList

    /**
     * Whether the right pane currently has anything actionable. Info-only rows
     * (FK, deterministic type, only-db, type-mismatch, synced) leave [slotsList]
     * empty — moving focus there would visually look like "focus lost", so the
     * window dispatcher uses this to decide whether to follow → from props.
     */
    val hasFocusableContent: Boolean get() = slotsList.itemCount > 0
}
