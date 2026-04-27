package onl.ycode.stormify.schemasync.tui

enum class Action(val unicode: String, val ascii: String) {
    NONE("··", "--"),
    TABLE_TO_ENTITY("▶▶", ">>"),
    ENTITY_TO_TABLE("◀◀", "<<"),
    SYNCED("══", "OK"),
    PROBLEMATIC("╳╳", "XX"),
    ;

    val label: String get() = if (Symbols.ascii) ascii else unicode

    val locked: Boolean get() = this == SYNCED || this == PROBLEMATIC

    fun cycle(): Action = when (this) {
        NONE -> TABLE_TO_ENTITY
        TABLE_TO_ENTITY -> ENTITY_TO_TABLE
        ENTITY_TO_TABLE -> NONE
        SYNCED -> SYNCED
        PROBLEMATIC -> PROBLEMATIC
    }
}
