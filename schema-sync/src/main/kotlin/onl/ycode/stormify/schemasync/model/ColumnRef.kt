package onl.ycode.stormify.schemasync.model

/**
 * A column awaiting classification. Sourced either from JDBC introspection or
 * from the bundled fixture for offline demos.
 */
data class ColumnRef(
    val schema: String?,
    val table: String,
    val name: String,
    /** Null when the underlying SQL type is deterministic (Boolean, Date, UUID, …) and needs no slot. */
    val category: SlotCategory?,
    val dbType: String,
    /** Raw `java.sql.Types` constant; `Types.OTHER` for fixture data. */
    val jdbcType: Int = java.sql.Types.OTHER,
    /** True when DB metadata reports the column nullable. */
    val nullable: Boolean = true,
    /** Coarse-grained family used for diff-time mismatch detection; null when [jdbcType] is [java.sql.Types.OTHER]. */
    val family: TypeFamily? = null,
) {
    /** Stable identifier used in saved assignments and as map key. */
    val key: String = listOfNotNull(schema, table, name).joinToString(".")

    /** Compact human-readable form for the UI. */
    val display: String = listOfNotNull(schema, table, name).joinToString(".")
}
