package onl.ycode.stormify.schemasync.tui

import onl.ycode.stormify.schemasync.model.TableEntry

class RowFormatter(entries: List<TableEntry>) {
    private val tableWidth: Int = maxOf(10, entries.maxOf { it.table.length })
    private val entityWidth: Int = maxOf(10, entries.maxOf { entityShort(it.entity).length })

    fun renderRow(entry: TableEntry, action: Action): String =
        " ${action.label}  ${entry.table.fit(tableWidth)} ${Symbols.vbar} ${entityShort(entry.entity).fit(entityWidth)} "

    fun headerRow(): String =
        "     ${"table".fit(tableWidth)} ${Symbols.vbar} ${"entity".fit(entityWidth)} "

    /** Column index where the row separator (`│`) sits. Used by HeaderRule to place its cross. */
    val crossColumn: Int get() = 1 + 2 + 2 + tableWidth + 1

    val leftPaneWidth: Int get() = 1 + 2 + 2 + tableWidth + 3 + entityWidth + 1 + 2 + 2
}

internal fun entityShort(fqn: String?): String = fqn?.substringAfterLast('.') ?: ""

internal fun String.fit(n: Int): String =
    if (length > n) take(n - Symbols.ellipsis.length) + Symbols.ellipsis else padEnd(n)
