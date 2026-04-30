package onl.ycode.stormify.schemasync.tui

import onl.ycode.stormify.schemasync.entity.ColumnDelta

/**
 * One row in the middle (properties) column. Mutable because the user toggles
 * the per-property action with Space — the list redraws by re-invoking
 * [toString] on each item.
 */
class PropertyRow(
    val tableKey: String,
    val delta: ColumnDelta,
    private val actions: PropertyActions,
    private val formatter: PropertyRowFormatter,
) : Runnable {

    /** No-op: ActionListBox needs a Runnable; row activation is handled at window level. */
    override fun run() = Unit

    override fun toString(): String =
        formatter.render(delta, actions.get(tableKey, delta.name, delta.kind))
}

/** Computes column widths once per table view, then renders rows in a single pass. */
class PropertyRowFormatter(deltas: List<ColumnDelta>) {
    private val nameWidth: Int =
        maxOf("property".length, deltas.maxOfOrNull { it.name.length } ?: 0).coerceAtMost(32)
    private val typeWidth: Int =
        maxOf("type".length, deltas.maxOfOrNull { typeText(it).length } ?: 0).coerceAtMost(28)

    fun render(delta: ColumnDelta, action: PropertyAction): String {
        val sym = symbolFor(delta.kind, action)
        val name = delta.name.fit(nameWidth)
        val type = typeText(delta).fit(typeWidth)
        return " $sym ${name} ${Symbols.vbar} $type "
    }

    fun headerRow(): String =
        "   ${"property".fit(nameWidth)} ${Symbols.vbar} ${"type".fit(typeWidth)} "

    /** Column index of the `│` separator between name and type. */
    val crossColumns: List<Int> = listOf(1 + 1 + 1 + nameWidth + 1)

    val paneWidth: Int = 1 + 1 + 1 + nameWidth + 3 + typeWidth + 1

    private fun typeText(delta: ColumnDelta): String =
        delta.dbColumn?.dbType ?: delta.entityField?.type ?: ""
}
