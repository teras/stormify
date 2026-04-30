package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.BasicWindow
import com.googlecode.lanterna.gui2.CheckBoxList
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.EmptySpace
import com.googlecode.lanterna.gui2.Label
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.gui2.Window
import com.googlecode.lanterna.gui2.WindowBasedTextGUI
import com.googlecode.lanterna.gui2.WindowListenerAdapter
import com.googlecode.lanterna.gui2.dialogs.MessageDialog
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import onl.ycode.stormify.schemasync.entity.ColumnDelta
import onl.ycode.stormify.schemasync.entity.KotlinEntity
import onl.ycode.stormify.schemasync.entity.TableDiff
import onl.ycode.stormify.schemasync.entity.source.EntityWriter
import onl.ycode.stormify.schemasync.entity.source.PsiEnvironment
import onl.ycode.stormify.schemasync.db.JdbcCategoryMapper
import onl.ycode.stormify.schemasync.model.ColumnRef
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** A single proposed PSI edit, with a human-readable label and origin info. */
private data class WriterAction(
    val label: String,
    val sourcePath: Path,
    val edit: EntityWriter.Edit,
)

/**
 * F7 Code edits — applies PSI splices to the Kotlin entity sources based on the
 * current diff:
 *  - DIFF table with DB_ONLY column → insert a new `var` into the entity class.
 *  - DIFF table with ENTITY_ONLY field → annotate that field with `@Transient`.
 *
 * Selections persist within the window; Enter applies all checked items, then
 * the window closes. The user must restart schema-sync to see the refreshed
 * diff (re-scan + re-introspect).
 */
/** @return number of PSI edits actually applied (0 if cancelled or none checked). */
fun runWriterView(
    gui: WindowBasedTextGUI,
    entities: List<KotlinEntity>,
    diffs: Collection<TableDiff>,
): Int {
    val actions = buildActions(entities, diffs)
    var appliedCount = 0
    val window = BasicWindow("Code edits")
    window.setHints(listOf(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS))

    val root = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))

    if (actions.isEmpty()) {
        root.addComponent(Label(
            "No edits available.\n\n" +
                "Code edits operate on Kotlin sources scanned via --sources.\n" +
                "Available actions appear here when:\n" +
                "  • a DB column has no matching entity field (insert into entity)\n" +
                "  • an entity field has no matching DB column (mark @Transient)",
        ).withTitledBorder("Pending edits"))
        root.addComponent(EmptySpace(TerminalSize(1, 1)))
        root.addComponent(Label("Esc / Enter close · F1 help"))
        window.component = root
        installCloseHandler(window, gui)
        gui.addWindowAndWait(window)
        return 0
    }

    val list = CheckBoxList<WriterAction>()
    actions.forEach { list.addItem(it, true) } // all checked by default

    root.addComponent(list.withTitledBorder("${actions.size} pending edits — Space to toggle"))
    root.addComponent(EmptySpace(TerminalSize(1, 1)))
    root.addComponent(Label("Space toggle · Enter apply checked · Esc cancel · F1 help"))

    window.component = root
    window.focusedInteractable = list

    window.addWindowListener(object : WindowListenerAdapter() {
        override fun onUnhandledInput(basePane: Window, keyStroke: KeyStroke, hasBeenHandled: AtomicBoolean) {
            when {
                keyStroke.keyType == KeyType.F1 || keyStroke.character == '?' -> {
                    showHelp(gui)
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.Escape || keyStroke.character == 'q' || keyStroke.character == 'Q' -> {
                    window.close()
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.Enter -> {
                    val checked = (0 until list.itemCount)
                        .mapNotNull { i -> if (list.isChecked(i)) list.getItemAt(i) else null }
                    if (checked.isEmpty()) {
                        MessageDialog.showMessageDialog(gui, "Code edits", "Nothing checked.", MessageDialogButton.OK)
                    } else {
                        appliedCount = applyActions(checked)
                        MessageDialog.showMessageDialog(
                            gui,
                            "Code edits",
                            "Applied $appliedCount of ${checked.size} edits. Refreshing…",
                            MessageDialogButton.OK,
                        )
                        window.close()
                    }
                    hasBeenHandled.set(true)
                }
            }
        }
    })

    gui.addWindowAndWait(window)
    return appliedCount
}

private fun installCloseHandler(window: BasicWindow, gui: WindowBasedTextGUI) {
    window.addWindowListener(object : WindowListenerAdapter() {
        override fun onUnhandledInput(basePane: Window, keyStroke: KeyStroke, hasBeenHandled: AtomicBoolean) {
            when {
                keyStroke.keyType == KeyType.F1 || keyStroke.character == '?' -> {
                    showHelp(gui); hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.Escape || keyStroke.keyType == KeyType.Enter
                    || keyStroke.character == 'q' || keyStroke.character == 'Q' -> {
                    window.close(); hasBeenHandled.set(true)
                }
            }
        }
    })
}

private fun buildActions(entities: List<KotlinEntity>, diffs: Collection<TableDiff>): List<WriterAction> {
    val out = mutableListOf<WriterAction>()
    val byTableKey = entities.associateBy { it.tableKey }
    for (diff in diffs) {
        val entity = diff.entity ?: byTableKey[diff.tableKey] ?: continue
        val source = entity.sourcePath ?: continue
        val path = Path.of(source)
        for (delta in diff.columnDeltas) {
            when (delta.kind) {
                ColumnDelta.Kind.DB_ONLY -> {
                    val col = delta.dbColumn ?: continue
                    val propName = camel(col.name)
                    val type = inferKotlinType(col)
                    val nullableMark = if (col.nullable) "?" else ""
                    out += WriterAction(
                        label = "INSERT  ${entity.className}.$propName: $type$nullableMark  (column ${diff.tableKey}.${col.name})",
                        sourcePath = path,
                        edit = EntityWriter.Edit.AddProperty(
                            className = entity.className,
                            propertyName = propName,
                            kotlinType = type,
                            nullable = col.nullable,
                        ),
                    )
                }
                ColumnDelta.Kind.ENTITY_ONLY -> {
                    val field = delta.entityField ?: continue
                    out += WriterAction(
                        label = "@Transient ${entity.className}.${field.name}  (no DB column ${diff.tableKey}.${field.column})",
                        sourcePath = path,
                        edit = EntityWriter.Edit.MarkTransient(entity.className, field.name),
                    )
                }
                ColumnDelta.Kind.SYNCED, ColumnDelta.Kind.TYPE_MISMATCH -> {}
            }
        }
    }
    return out
}

private fun applyActions(actions: List<WriterAction>): Int {
    val grouped = actions.groupBy({ it.sourcePath }, { it.edit })
    var applied = 0
    PsiEnvironment().use { env ->
        for ((path, edits) in grouped) {
            if (EntityWriter.applyEdits(env, path, edits)) applied += edits.size
        }
    }
    return applied
}

/** snake_case → camelCase. */
private fun camel(s: String): String {
    val parts = s.split('_')
    if (parts.size == 1) return parts[0]
    return parts[0] + parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
}

private fun inferKotlinType(col: ColumnRef): String = JdbcCategoryMapper.kotlinTypeFor(col.jdbcType)
