package onl.ycode.stormify.schemasync.entity

import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.model.TableStatus

/** Result of pairing one Kotlin entity (or none) with one DB table (or none). */
data class TableDiff(
    val tableKey: String,
    val entity: KotlinEntity?,
    val dbColumns: List<ColumnRef>,
    val status: TableStatus,
    val columnDeltas: List<ColumnDelta>,
)

/** A single column-level difference within a [TableDiff]. */
data class ColumnDelta(
    val name: String,
    val kind: Kind,
    val entityField: EntityField?,
    val dbColumn: ColumnRef?,
    /** When [kind] is [Kind.TYPE_MISMATCH], short reason text (e.g. "category", "nullable"). */
    val mismatchReason: String? = null,
) {
    enum class Kind { SYNCED, ENTITY_ONLY, DB_ONLY, TYPE_MISMATCH }
}
