package onl.ycode.stormify.schemasync.model

data class TableEntry(
    val table: String,
    val entity: String?,
    val diffs: Int,
    val status: TableStatus,
    /** True when at least one column has a TYPE_MISMATCH delta. */
    val hasTypeMismatch: Boolean = false,
    /** True when the entity declares fields the DB doesn't have. */
    val hasMissingDbFields: Boolean = false,
    /** True when the DB has columns the entity doesn't declare. */
    val hasMissingKotlinFields: Boolean = false,
    /** True when the DB object backing this entry is a VIEW (not a TABLE). */
    val isView: Boolean = false,
    /** Number of Kotlin entities mapped to this slot. >1 → multi-claim. */
    val entityCount: Int = if (entity != null) 1 else 0,
)

enum class TableStatus { SYNCED, DIFF, ENTITY_ONLY, DB_ONLY }
