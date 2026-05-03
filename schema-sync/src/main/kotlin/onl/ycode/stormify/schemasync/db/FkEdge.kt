package onl.ycode.stormify.schemasync.db

/** A single foreign-key edge: `(schema, table, column) → (refTable, refColumn)`.
 *  Used by [DbIntrospector] to decorate columns with FK metadata. The
 *  reference side is captured as last-segment-only since that's how the diff
 *  layer keys tables. */
internal data class FkEdge(
    val schema: String?,
    val table: String,
    val column: String,
    val refTable: String,
    val refColumn: String,
)
