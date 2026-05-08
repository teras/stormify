package onl.ycode.stormify.schemasync.tui

import onl.ycode.stormify.schemasync.model.TableEntry
import onl.ycode.stormify.schemasync.model.TableStatus

/**
 * Renders table-list rows for the left pane. The pane shows up to two
 * sub-columns (Table and Entity) inside a single bordered widget. The
 * layout drives column visibility by setting [displayTableWidth] and
 * [displayEntityWidth]: each may shrink continuously down to the MIN
 * sliver size (2 cells) before being set to 0 (dropped). All title /
 * separator / pane-width derived properties read these two fields, so a
 * single layout call can re-shape the pane without touching renderer
 * internals.
 */
class RowFormatter(
    entries: List<TableEntry>,
    var hasPending: (TableEntry) -> Boolean = { false },
) {

    /** Preferred raw width of the Table sub-column (longest table name vs header). */
    val tableWidth: Int = maxOf("table".length, entries.maxOfOrNull { it.table.length } ?: 0)
    /** Preferred raw width of the Entity sub-column (longest display name vs header). */
    val entityWidth: Int = maxOf("entity".length, entries.maxOfOrNull { entityDisplay(it).length } ?: 0)

    /**
     * Active rendered widths, set by the layout each pass. A value of 0
     * means "drop this sub-column entirely". Any value ≥ MIN is rendered
     * as content (truncated with an ellipsis if shorter than preferred).
     */
    var displayTableWidth: Int = tableWidth
    var displayEntityWidth: Int = entityWidth

    fun renderRow(entry: TableEntry): String {
        val tw = displayTableWidth
        val ew = displayEntityWidth
        val tick = if (hasPending(entry)) Symbols.tick else " "
        return when {
            tw == 0 && ew == 0 -> ""
            tw == 0 -> " ${badge(entry.status)} ${entityDisplay(entry).fit(ew)} $tick "
            ew == 0 -> " ${badge(entry.status)} ${entry.table.fit(tw)} $tick "
            else -> " ${badge(entry.status)} ${entry.table.fit(tw)} ${Symbols.vbar} ${entityDisplay(entry).fit(ew)} $tick "
        }
    }

    /** Border title; reflects which sub-columns are currently visible. */
    val titleText: String get() {
        val tw = displayTableWidth
        val ew = displayEntityWidth
        return when {
            tw == 0 && ew == 0 -> ""
            tw == 0 -> "Entity"
            ew == 0 -> "Table"
            else -> columnHeaderTitle(listOf(
                "Table" to (tw - 4).coerceAtLeast(1),
                "Entity" to 1,
            ))
        }
    }

    /** Inner-coordinate columns where the row separator (`│`) sits. */
    val crossColumns: List<Int> get() {
        val tw = displayTableWidth
        val ew = displayEntityWidth
        return if (tw > 0 && ew > 0) listOf(1 + 1 + 1 + tw + 1) else emptyList()
    }

    /** Inner pane width (without the surrounding border) for the active widths. */
    val paneInnerWidth: Int get() = innerWidthFor(displayTableWidth, displayEntityWidth)

    /** Inner pane width for arbitrary (table, entity) sub-widths. */
    fun innerWidthFor(tw: Int, ew: Int): Int = when {
        tw == 0 && ew == 0 -> 0                              // sliver: bordered = 2
        tw == 0 -> 1 + 1 + 1 + ew + 1 + 1 + 1                // " ▶ entity ✓ "
        ew == 0 -> 1 + 1 + 1 + tw + 1 + 1 + 1                // " ▶ table ✓ "
        else -> 1 + 1 + 1 + tw + 3 + ew + 1 + 1 + 1          // " ▶ table │ entity ✓ "
    }

    /** Bordered (outer) pane width for arbitrary (table, entity) sub-widths. */
    fun outerWidthFor(tw: Int, ew: Int): Int =
        if (tw == 0 && ew == 0) 2 else innerWidthFor(tw, ew) + 2

    /** 1-cell status badge (unicode glyph, ASCII fallback in `--ascii` mode). */
    private fun badge(status: TableStatus): String = if (Symbols.ascii) {
        when (status) {
            TableStatus.SYNCED -> "="
            TableStatus.DIFF -> "*"
            TableStatus.ENTITY_ONLY -> "<"
            TableStatus.DB_ONLY -> ">"
        }
    } else {
        when (status) {
            TableStatus.SYNCED -> "═"
            TableStatus.DIFF -> "≠"
            TableStatus.ENTITY_ONLY -> "◀"
            TableStatus.DB_ONLY -> "▶"
        }
    }
}

internal fun entityShort(fqn: String?): String = fqn?.substringAfterLast('.') ?: ""

/**
 * What goes in the Entity column for a row. When an entity is mapped, we show
 * its simple class name; for DB-only rows we surface the class name the table
 * *would* take if it were turned into an entity (snake_case → PascalCase) so
 * both columns stay populated and the badge alone disambiguates direction.
 * A trailing `*` flags slots that have more than one entity claim.
 */
internal fun entityDisplay(entry: TableEntry): String {
    val base = entry.entity?.let(::entityShort)
        ?: pascalCase(entry.table.substringAfterLast('.'))
    return if (entry.entityCount > 1) "$base *" else base
}

internal fun pascalCase(snake: String): String =
    snake.split('_').filter { it.isNotEmpty() }
        .joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }

internal fun String.fit(n: Int): String =
    if (length > n) take((n - Symbols.ellipsis.length).coerceAtLeast(0)) + Symbols.ellipsis.take(n) else padEnd(n)
