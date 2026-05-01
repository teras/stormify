package onl.ycode.stormify.schemasync.tui

import onl.ycode.stormify.schemasync.model.TableEntry
import onl.ycode.stormify.schemasync.model.TableStatus

class RowFormatter(entries: List<TableEntry>) {
    private val tableWidth: Int = maxOf("table".length, entries.maxOf { it.table.length })
    private val entityWidth: Int = maxOf("entity".length, entries.maxOf { entityDisplay(it).length })

    fun renderRow(entry: TableEntry): String =
        " ${badge(entry.status)} ${entry.table.fit(tableWidth)} ${Symbols.vbar} ${entityDisplay(entry).fit(entityWidth)} "

    /** Border title doubling as `Table │ Entity` column header. */
    val titleText: String = columnHeaderTitle(listOf(
        "Table" to (tableWidth - 4).coerceAtLeast(1),
        "Entity" to 1,
    ))

    /** Column index where the row separator (`│`) sits. */
    val crossColumns: List<Int> = listOf(1 + 1 + 1 + tableWidth + 1)

    val leftPaneWidth: Int = 1 + 1 + 1 + tableWidth + 3 + entityWidth + 1

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
 */
internal fun entityDisplay(entry: TableEntry): String =
    entry.entity?.let(::entityShort)
        ?: pascalCase(entry.table.substringAfterLast('.'))

private fun pascalCase(snake: String): String =
    snake.split('_').filter { it.isNotEmpty() }
        .joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }

internal fun String.fit(n: Int): String =
    if (length > n) take(n - Symbols.ellipsis.length) + Symbols.ellipsis else padEnd(n)
