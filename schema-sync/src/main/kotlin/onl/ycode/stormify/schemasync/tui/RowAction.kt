package onl.ycode.stormify.schemasync.tui

import onl.ycode.stormify.schemasync.model.TableEntry
import onl.ycode.stormify.schemasync.model.TableStatus

class RowAction(val entry: TableEntry, private val formatter: RowFormatter) : Runnable {
    var action: Action = when (entry.status) {
        TableStatus.SYNCED -> Action.SYNCED
        TableStatus.PROBLEMATIC -> Action.PROBLEMATIC
        else -> Action.NONE
    }

    override fun run() {
        if (!action.locked) action = action.cycle()
    }

    override fun toString(): String = formatter.renderRow(entry, action)
}
