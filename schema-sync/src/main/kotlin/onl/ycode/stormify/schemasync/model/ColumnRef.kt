package onl.ycode.stormify.schemasync.model

/** A column awaiting classification, populated from JDBC introspection. */
data class ColumnRef(
    val schema: String?,
    val table: String,
    val name: String,
    /** Null when the underlying SQL type is deterministic (Boolean, Date, UUID, …) and needs no slot. */
    val category: SlotCategory?,
    val dbType: String,
    /** Raw `java.sql.Types` constant; defaults to `Types.OTHER` when not available. */
    val jdbcType: Int = java.sql.Types.OTHER,
    /** True when DB metadata reports the column nullable. */
    val nullable: Boolean = true,
    /** Coarse-grained family used for diff-time mismatch detection; null when [jdbcType] is [java.sql.Types.OTHER]. */
    val family: TypeFamily? = null,
    /** When this column is a foreign key, the referenced table key (`schema.table` or just `table`). */
    val referencedTable: String? = null,
    /** When this column is a foreign key, the referenced column name. */
    val referencedColumn: String? = null,
    /** Total digits for NUMERIC/DECIMAL columns (Oracle's `NUMBER(p, s)` p);
     *  null when the database doesn't report it. */
    val precision: Int? = null,
) {
    /** Stable identifier used in saved assignments and as map key. */
    val key: String = listOfNotNull(schema, table, name).joinToString(".")

    /** Compact human-readable form for the UI. */
    val display: String = listOfNotNull(schema, table, name).joinToString(".")
}
