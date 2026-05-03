package onl.ycode.stormify.schemasync.tui

import onl.ycode.stormify.schemasync.entity.ColumnDelta
import onl.ycode.stormify.schemasync.entity.TableDiff
import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.model.TableEntry

/** Build [TableEntry] entries for the left tables list from per-table diffs.
 *  [viewKeys] contains the lower-cased table keys of DB objects that are
 *  actually VIEWs, so the UI can flag them. */
fun buildTableEntries(diffs: List<TableDiff>, viewKeys: Set<String> = emptySet()): List<TableEntry> = diffs.map { d ->
    val unsyncedCount = d.columnDeltas.count { it.kind != ColumnDelta.Kind.SYNCED }
    TableEntry(
        table = d.tableKey,
        entity = d.primary?.className,
        diffs = unsyncedCount,
        status = d.status,
        hasTypeMismatch = d.columnDeltas.any { it.kind == ColumnDelta.Kind.TYPE_MISMATCH },
        hasMissingDbFields = d.columnDeltas.any { it.kind == ColumnDelta.Kind.ENTITY_ONLY },
        hasMissingKotlinFields = d.columnDeltas.any { it.kind == ColumnDelta.Kind.DB_ONLY },
        isView = d.tableKey.substringAfterLast('.').lowercase() in viewKeys,
        entityCount = d.entityCount,
    )
}

/** Group columns by their `schema.table` key (or just `table` when no schema). */
fun groupByTable(columns: List<ColumnRef>): Map<String, List<ColumnRef>> =
    columns.groupBy { listOfNotNull(it.schema, it.table).joinToString(".") }
