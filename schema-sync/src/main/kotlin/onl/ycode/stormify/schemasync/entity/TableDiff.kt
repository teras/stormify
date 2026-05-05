package onl.ycode.stormify.schemasync.entity

import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.model.TableStatus

/**
 * One DB-side slot (a physical table or a phantom for entities without a
 * matching table) paired with the 0..N Kotlin entities that claim it. The
 * first entry of [entities] is the *primary* — the recipient for new fields
 * added by F2 Apply when the slot has more than one claim. The list is
 * pre-sorted by the tiebreaker rule in [DiffEngine].
 */
data class TableDiff(
    val tableKey: String,
    val entities: List<KotlinEntity>,
    val dbColumns: List<ColumnRef>,
    val status: TableStatus,
    val columnDeltas: List<ColumnDelta>,
) {
    val primary: KotlinEntity? get() = entities.firstOrNull()
    val entityCount: Int get() = entities.size
}

/** A single column-level difference within a [TableDiff]. */
data class ColumnDelta(
    val name: String,
    val kind: Kind,
    /** A representative field (from the primary when present, else from any
     *  entity that declares the column). Null only when no entity has it. */
    val entityField: EntityField?,
    val dbColumn: ColumnRef?,
    /** When [kind] is [Kind.TYPE_MISMATCH], short reason text (e.g. "category", "nullable"). */
    val mismatchReason: String? = null,
    /** When non-null, the entity-side and DB-side defaults disagree after
     *  normalization. First component is the entity literal, second is the
     *  raw DB default. Set independently of [kind] so a row can be otherwise
     *  SYNCED but still surface a default divergence. */
    val defaultMismatch: Pair<String?, String?>? = null,
) {
    enum class Kind { SYNCED, ENTITY_ONLY, DB_ONLY, TYPE_MISMATCH }
}
