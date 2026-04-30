package onl.ycode.stormify.schemasync.tui

import onl.ycode.stormify.schemasync.model.TableEntry
import onl.ycode.stormify.schemasync.model.TableStatus

class RowFormatter(entries: List<TableEntry>) {
    private val tableWidth: Int = maxOf("table".length, entries.maxOf { it.table.length })
    private val entityWidth: Int = maxOf("entity".length, entries.maxOf { entityShort(it.entity).length })

    fun renderRow(entry: TableEntry): String =
        " ${badge(entry.status)} ${entry.table.fit(tableWidth)} ${Symbols.vbar} ${entityShort(entry.entity).fit(entityWidth)} "

    fun headerRow(): String =
        "   ${"table".fit(tableWidth)} ${Symbols.vbar} ${"entity".fit(entityWidth)} "

    /** Column index where the row separator (`│`) sits. */
    val crossColumns: List<Int> = listOf(1 + 1 + 1 + tableWidth + 1)

    val leftPaneWidth: Int = 1 + 1 + 1 + tableWidth + 3 + entityWidth + 1

    /** 1-cell status badge (unicode glyph, ASCII fallback in `--ascii` mode). */
    private fun badge(status: TableStatus): String = if (Symbols.ascii) {
        when (status) {
            TableStatus.SYNCED -> "="
            TableStatus.DIFF -> "*"
            TableStatus.ENTITY_ONLY -> ">"
            TableStatus.DB_ONLY -> "<"
            TableStatus.PROBLEMATIC -> "!"
        }
    } else {
        when (status) {
            TableStatus.SYNCED -> "═"
            TableStatus.DIFF -> "≠"
            TableStatus.ENTITY_ONLY -> "▶"
            TableStatus.DB_ONLY -> "◀"
            TableStatus.PROBLEMATIC -> "✗"
        }
    }
}

internal fun entityShort(fqn: String?): String = fqn?.substringAfterLast('.') ?: ""

internal fun String.fit(n: Int): String =
    if (length > n) take(n - Symbols.ellipsis.length) + Symbols.ellipsis else padEnd(n)
