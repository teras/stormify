package onl.ycode.stormify.schemasync.model

data class ColumnDiff(
    val kind: DiffKind,
    val name: String,
    val type: String,
    val note: String = "",
)

enum class DiffKind { ADD_TO_ENTITY, MARK_TRANSIENT, TYPE_CHANGE }
