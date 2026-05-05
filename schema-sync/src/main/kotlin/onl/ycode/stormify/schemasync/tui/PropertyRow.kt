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
        formatter.render(delta, actions.get(tableKey, delta.name))
}

/** Computes column widths once per table view, then renders rows in a single pass. */
class PropertyRowFormatter(deltas: List<ColumnDelta>) {
    private val nameWidth: Int =
        maxOf("property".length, deltas.maxOfOrNull { it.name.length } ?: 0).coerceAtMost(32)

    fun render(delta: ColumnDelta, action: PropertyAction): String {
        val sym = symbolFor(delta.kind)
        val name = delta.name.fit(nameWidth)
        val tick = if (action == PropertyAction.INSERT) Symbols.tick else " "
        return " $sym $name $tick "
    }

    /** Border title; this pane has a single column, so just the column name. */
    val titleText: String = "Property"

    /** No interior column separator — single-column list. */
    val crossColumns: List<Int> = emptyList()

    val paneWidth: Int = 1 + 1 + 1 + nameWidth + 1 + 1 + 1
}
