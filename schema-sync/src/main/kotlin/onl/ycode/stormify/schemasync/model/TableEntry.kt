package onl.ycode.stormify.schemasync.model

data class TableEntry(
    val table: String,
    val entity: String?,
    val diffs: Int,
    val status: TableStatus,
)

enum class TableStatus { SYNCED, DIFF, ENTITY_ONLY, DB_ONLY, PROBLEMATIC }
