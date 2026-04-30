package onl.ycode.stormify.schemasync.tui

import onl.ycode.stormify.schemasync.entity.ColumnDelta
import onl.ycode.stormify.schemasync.entity.TableDiff
import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.model.TableEntry

/** Build [TableEntry] entries for the left tables list from per-table diffs. */
fun buildTableEntries(diffs: List<TableDiff>): List<TableEntry> = diffs.map { d ->
    val unsyncedCount = d.columnDeltas.count { it.kind != ColumnDelta.Kind.SYNCED }
    TableEntry(
        table = d.tableKey,
        entity = d.entity?.className,
        diffs = unsyncedCount,
        status = d.status,
    )
}

/** Group columns by their `schema.table` key (or just `table` when no schema). */
fun groupByTable(columns: List<ColumnRef>): Map<String, List<ColumnRef>> =
    columns.groupBy { listOfNotNull(it.schema, it.table).joinToString(".") }
