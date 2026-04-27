package onl.ycode.stormify.schemasync.fixture

import onl.ycode.stormify.schemasync.model.ColumnDiff
import onl.ycode.stormify.schemasync.model.DiffKind
import onl.ycode.stormify.schemasync.model.TableEntry
import onl.ycode.stormify.schemasync.model.TableStatus

val fixtureTables: List<TableEntry> = buildList {
    add(TableEntry("user", "com.myapp.User", 2, TableStatus.DIFF))
    add(TableEntry("order", "com.myapp.Order", 1, TableStatus.DIFF))
    add(TableEntry("product", "com.myapp.Product", 0, TableStatus.SYNCED))
    add(TableEntry("category", "com.myapp.Category", 0, TableStatus.SYNCED))
    add(TableEntry("order_item", "com.myapp.OrderItem", 3, TableStatus.DIFF))
    add(TableEntry("audit_log", null, 0, TableStatus.DB_ONLY))
    add(TableEntry("legacy_shipments", null, 0, TableStatus.DB_ONLY))
    add(TableEntry("invoice", "com.myapp.Invoice", 0, TableStatus.PROBLEMATIC))
    val areas = listOf("billing", "inventory", "shipping", "crm", "analytics", "reports", "hr", "finance")
    val kinds = listOf("log", "summary", "detail", "stage", "history", "event", "snapshot", "queue")
    var i = 0
    for (area in areas) {
        for (kind in kinds) {
            repeat(8) { n ->
                i++
                val name = "${area}_${kind}_${"%03d".format(n)}"
                val status = when (i % 5) {
                    0 -> TableStatus.DIFF
                    1 -> TableStatus.SYNCED
                    2 -> TableStatus.DB_ONLY
                    3 -> TableStatus.ENTITY_ONLY
                    else -> TableStatus.PROBLEMATIC
                }
                val entity = if (status == TableStatus.DB_ONLY) null
                else "com.myapp.${area.replaceFirstChar { it.uppercase() }}${kind.replaceFirstChar { it.uppercase() }}$n"
                val diffs = if (status == TableStatus.DIFF) (i % 4 + 1) else 0
                add(TableEntry(name, entity, diffs, status))
            }
        }
    }
}

val fixtureDiffs: Map<String, List<ColumnDiff>> = fixtureTables
    .filter { it.status == TableStatus.DIFF && it.diffs > 0 }
    .associate { row ->
        row.table to List(row.diffs) { idx ->
            val kind = when (idx % 3) {
                0 -> DiffKind.ADD_TO_ENTITY
                1 -> DiffKind.MARK_TRANSIENT
                else -> DiffKind.TYPE_CHANGE
            }
            ColumnDiff(
                kind = kind,
                name = "col_${row.table.take(6)}_$idx",
                type = listOf("String?", "Int", "Long?", "Instant?", "BigDecimal?")[idx % 5],
                note = when (kind) {
                    DiffKind.ADD_TO_ENTITY -> "DB has column, entity missing"
                    DiffKind.MARK_TRANSIENT -> "entity has field, DB missing"
                    DiffKind.TYPE_CHANGE -> "type mismatch — review"
                },
            )
        }
    }
