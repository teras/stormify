package onl.ycode.stormify.schemasync.entity

import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.model.TableStatus

/**
 * Pairs Kotlin entities with DB tables by table key, then computes per-column
 * deltas. Outputs one [TableDiff] per unique table key seen in either side.
 */
object DiffEngine {

    fun diff(
        entities: List<KotlinEntity>,
        dbColumnsByTable: Map<String, List<ColumnRef>>,
        dbTableKeys: List<String>,
    ): List<TableDiff> {
        val entitiesByTable = entities.associateBy { it.tableKey }
        val allKeys = (entitiesByTable.keys + dbTableKeys).distinct().sorted()
        return allKeys.map { key ->
            diffTable(key, entitiesByTable[key], dbColumnsByTable[key] ?: emptyList())
        }
    }

    private fun diffTable(
        tableKey: String,
        entity: KotlinEntity?,
        dbColumns: List<ColumnRef>,
    ): TableDiff {
        if (entity == null) {
            // DB-only: every column is informational, no Kotlin counterpart.
            val deltas = dbColumns.map { ColumnDelta(it.name, ColumnDelta.Kind.DB_ONLY, null, it) }
            return TableDiff(tableKey, null, dbColumns, TableStatus.DB_ONLY, deltas)
        }
        if (dbColumns.isEmpty()) {
            // Entity-only: every field needs a column added.
            val deltas = entity.fields.map { f ->
                ColumnDelta(f.column, ColumnDelta.Kind.ENTITY_ONLY, f, null)
            }
            return TableDiff(tableKey, entity, dbColumns, TableStatus.ENTITY_ONLY, deltas)
        }
        // Both sides present: pair columns by name.
        val dbByName = dbColumns.associateBy { it.name }
        val fieldByCol = entity.fields.associateBy { it.column }
        val allCols = (dbByName.keys + fieldByCol.keys).distinct()
        val deltas = allCols.map { name ->
            val field = fieldByCol[name]
            val col = dbByName[name]
            when {
                field != null && col != null -> {
                    val reason = mismatchReason(field, col)
                    if (reason != null) ColumnDelta(name, ColumnDelta.Kind.TYPE_MISMATCH, field, col, reason)
                    else ColumnDelta(name, ColumnDelta.Kind.SYNCED, field, col)
                }
                field != null -> ColumnDelta(name, ColumnDelta.Kind.ENTITY_ONLY, field, null)
                else -> ColumnDelta(name, ColumnDelta.Kind.DB_ONLY, null, col)
            }
        }
        val status = if (deltas.all { it.kind == ColumnDelta.Kind.SYNCED }) {
            TableStatus.SYNCED
        } else {
            TableStatus.DIFF
        }
        return TableDiff(tableKey, entity, dbColumns, status, deltas)
    }

    /**
     * Returns null when the entity field and DB column are compatible enough
     * to treat as SYNCED, or a short reason string when they diverge in a way
     * the user should see.
     *
     * Resolution differences within the same category (Int vs Long, VARCHAR(50)
     * vs VARCHAR(100), nullable vs not-null, …) are NOT mismatches —
     * schema-sync's reduced scope does not emit ALTER COLUMN TYPE/NULLABILITY,
     * so such rows are treated as SYNCED rather than warning-only.
     */
    private fun mismatchReason(field: EntityField, col: ColumnRef): String? {
        val fFam = field.family
        val cFam = col.family
        if (fFam != null && cFam != null && fFam != cFam) return "type"
        return null
    }
}
