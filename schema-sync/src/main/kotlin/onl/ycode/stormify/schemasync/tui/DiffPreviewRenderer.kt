package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.gui2.Label
import onl.ycode.stormify.schemasync.model.ColumnDiff
import onl.ycode.stormify.schemasync.model.DiffKind
import onl.ycode.stormify.schemasync.model.TableEntry
import onl.ycode.stormify.schemasync.model.TableStatus

class DiffPreviewRenderer(private val diffs: Map<String, List<ColumnDiff>>) {
    fun update(entry: TableEntry, title: Label, body: Label) {
        title.text = "Table: ${entry.table}   Entity: ${entry.entity ?: Symbols.dash}"
        val tableDiffs = diffs[entry.table]
        val sb = StringBuilder()
        when {
            entry.status == TableStatus.DB_ONLY ->
                sb.append("No matching entity. A new Kotlin class will be generated.\n")
            entry.status == TableStatus.ENTITY_ONLY ->
                sb.append("No matching table. A CREATE TABLE will be generated.\n")
            entry.status == TableStatus.SYNCED ->
                sb.append("Entity is already in sync with DB.\n")
            entry.status == TableStatus.PROBLEMATIC ->
                sb.append("Cannot be synced as is ${Symbols.dash} manual review required.\n")
            tableDiffs.isNullOrEmpty() ->
                sb.append("No changes.\n")
            else -> {
                sb.append("Changes:\n\n")
                tableDiffs.forEach { d ->
                    val action = when (d.kind) {
                        DiffKind.ADD_TO_ENTITY -> "+ add      "
                        DiffKind.MARK_TRANSIENT -> "~ @Transient"
                        DiffKind.TYPE_CHANGE -> "! type     "
                    }
                    sb.append("  $action  ${d.name.padEnd(22)} ${d.type.padEnd(14)}  ${d.note}\n")
                }
                sb.append("\nSource preview:\n")
                sb.append("  // …\n")
                tableDiffs.forEach { d ->
                    when (d.kind) {
                        DiffKind.ADD_TO_ENTITY ->
                            sb.append("+     var ${d.name}: ${d.type} by db(null)\n")
                        DiffKind.MARK_TRANSIENT -> {
                            sb.append("+     @Transient\n")
                            sb.append("      var ${d.name}: ${d.type} by db(null)\n")
                        }
                        DiffKind.TYPE_CHANGE ->
                            sb.append("!     // type change ${d.name} ${Symbols.arrow} ${d.type} (skipped)\n")
                    }
                }
                sb.append("  // …\n")
            }
        }
        body.text = sb.toString()
    }
}
