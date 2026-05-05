package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.model.SqlTypeCode

/**
 * Oracle-specific introspection paths. The Oracle JDBC driver's metadata
 * PL/SQL blocks reference columns absent from older DB versions
 * (ORA-00904); we go straight to the data dictionary instead.
 *
 * `USER_SYNONYMS` is also queried so the table list reflects what an
 * application user actually sees — many enterprise deployments expose
 * tables via synonyms in the user's schema rather than owning them
 * directly. Without synonyms the table list misses everything the
 * entity layer reads/writes.
 */

internal fun oracleListTables(stormify: Stormify): List<DbIntrospector.TableId> {
    val sql = """
        SELECT TABLE_NAME AS NAME, 'TABLE' AS K FROM USER_TABLES
        UNION ALL SELECT VIEW_NAME, 'VIEW' FROM USER_VIEWS
        UNION ALL SELECT SYNONYM_NAME, 'TABLE' FROM USER_SYNONYMS
    """.trimIndent()
    return stormify.read<Row>(sql).map { row ->
        val kind = if (row.str("k") == "VIEW") DbIntrospector.TableId.Kind.VIEW
        else DbIntrospector.TableId.Kind.TABLE
        DbIntrospector.TableId(null, row.str("name")!!.lowercase(), kind)
    }
}

internal fun oracleListColumns(
    stormify: Stormify,
    tables: List<DbIntrospector.TableId>,
    onProgress: ((Int, Int) -> Unit)? = null,
): List<ColumnRef> {
    val userTables = tables.mapTo(HashSet()) { it.name }
    val total = tables.size
    val out = mutableListOf<ColumnRef>()
    val seen = HashSet<String>()
    fun bump(table: String) {
        if (seen.add(table)) onProgress?.invoke(seen.size.coerceAtMost(total), total)
    }
    // Owned tables/views: USER_TAB_COLUMNS — direct, no joins.
    // DATA_DEFAULT is intentionally excluded here: it's a LONG column, and
    // Oracle JDBC fetches LONG values via one network roundtrip per row
    // regardless of fetch size. Pulling it inline turns a single bulk SELECT
    // into N synchronous trips and dominates the introspection time on any
    // schema with hundreds of tables. Defaults are fetched separately below
    // with a `WHERE DATA_DEFAULT IS NOT NULL` filter that drops 80–95% of
    // the rows in a typical schema.
    stormify.read<Row>(
        """
        SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, NULLABLE
        FROM USER_TAB_COLUMNS
        ORDER BY TABLE_NAME, COLUMN_ID
        """.trimIndent(),
    ).forEach { row ->
        val table = row.str("table_name")!!.lowercase()
        if (table !in userTables) return@forEach
        bump(table)
        out += oracleRowToColumn(row, table)
    }
    // Synonyms: resolve to target (table_owner, table_name) and pull columns
    // from ALL_TAB_COLUMNS (visible cross-schema as long as the user has any
    // grant on the target). Reported under the synonym's own name so the
    // slot key matches what the entity layer wrote in code.
    stormify.read<Row>(
        """
        SELECT s.SYNONYM_NAME AS TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE, c.DATA_LENGTH,
               c.DATA_PRECISION, c.DATA_SCALE, c.NULLABLE
        FROM USER_SYNONYMS s
        JOIN ALL_TAB_COLUMNS c
          ON c.OWNER = s.TABLE_OWNER
         AND c.TABLE_NAME = s.TABLE_NAME
        ORDER BY s.SYNONYM_NAME, c.COLUMN_ID
        """.trimIndent(),
    ).forEach { row ->
        val table = row.str("table_name")!!.lowercase()
        if (table !in userTables) return@forEach
        bump(table)
        out += oracleRowToColumn(row, table)
    }
    return overlayDefaults(stormify, out, userTables)
}

/** Fetches DATA_DEFAULT only for columns that have one, then merges those
 *  values into [columns]. The two source queries (owned tables / synonyms)
 *  filter on `WHERE DATA_DEFAULT IS NOT NULL` so the LONG fetch happens for
 *  the small subset that needs it instead of for every column in the schema. */
private fun overlayDefaults(
    stormify: Stormify,
    columns: List<ColumnRef>,
    userTables: Set<String>,
): List<ColumnRef> {
    val defaults = HashMap<Pair<String, String>, String>()
    fun captureRow(row: Row, tableKey: String) {
        val table = row.str(tableKey)?.lowercase() ?: return
        if (table !in userTables) return
        val column = row.str("column_name")?.lowercase() ?: return
        val raw = row.str("data_default")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        defaults[table to column] = raw
    }
    runCatching {
        stormify.read<Row>(
            """
            SELECT TABLE_NAME, COLUMN_NAME, DATA_DEFAULT
            FROM USER_TAB_COLUMNS
            WHERE DATA_DEFAULT IS NOT NULL
            """.trimIndent(),
        ).forEach { captureRow(it, "table_name") }
    }
    runCatching {
        stormify.read<Row>(
            """
            SELECT s.SYNONYM_NAME AS TABLE_NAME, c.COLUMN_NAME, c.DATA_DEFAULT
            FROM USER_SYNONYMS s
            JOIN ALL_TAB_COLUMNS c
              ON c.OWNER = s.TABLE_OWNER
             AND c.TABLE_NAME = s.TABLE_NAME
            WHERE c.DATA_DEFAULT IS NOT NULL
            """.trimIndent(),
        ).forEach { captureRow(it, "table_name") }
    }
    if (defaults.isEmpty()) return columns
    return columns.map { c ->
        val raw = defaults[c.table to c.name] ?: return@map c
        c.copy(defaultValue = raw)
    }
}

private fun oracleRowToColumn(row: Row, table: String): ColumnRef {
    val name = row.str("column_name")!!.lowercase()
    val typeName = row.str("data_type") ?: ""
    val length = row.intOrZero("data_length")
    val precision = row.intOrZero("data_precision")
    val scale = row.intOrNull("data_scale")
    val nullable = row.str("nullable") == "Y"
    val sqlType = oracleTypeNameToSqlType(typeName)
    val size = if (typeName.startsWith("VARCHAR") || typeName.startsWith("CHAR")) length else precision
    return ColumnRef(
        schema = null,
        table = table,
        name = name,
        category = TypeCategoryMapper.categoryFor(sqlType),
        dbType = formatOracleDbType(typeName, size, scale),
        sqlType = sqlType,
        nullable = nullable,
        family = TypeCategoryMapper.familyFor(sqlType, scale),
        precision = precision.takeIf { it > 0 },
    )
}

private fun oracleTypeNameToSqlType(typeName: String): SqlTypeCode = when {
    typeName.startsWith("VARCHAR2") || typeName.startsWith("VARCHAR") -> SqlTypeCode.VARCHAR
    typeName.startsWith("NVARCHAR") -> SqlTypeCode.NVARCHAR
    typeName.startsWith("CHAR") -> SqlTypeCode.CHAR
    typeName.startsWith("NCHAR") -> SqlTypeCode.NCHAR
    typeName == "CLOB" -> SqlTypeCode.CLOB
    typeName == "NCLOB" -> SqlTypeCode.NCLOB
    typeName == "BLOB" || typeName == "RAW" || typeName.startsWith("RAW") -> SqlTypeCode.BLOB
    typeName == "DATE" -> SqlTypeCode.TIMESTAMP
    typeName.startsWith("TIMESTAMP(") || typeName == "TIMESTAMP" -> SqlTypeCode.TIMESTAMP
    typeName.contains("WITH TIME ZONE") -> SqlTypeCode.TIMESTAMP_WITH_TIMEZONE
    typeName == "FLOAT" -> SqlTypeCode.DOUBLE
    typeName == "BINARY_FLOAT" -> SqlTypeCode.FLOAT
    typeName == "BINARY_DOUBLE" -> SqlTypeCode.DOUBLE
    typeName == "NUMBER" -> SqlTypeCode.NUMERIC
    else -> SqlTypeCode.OTHER
}

private val ORACLE_NUMERIC_TYPES = setOf("DECIMAL", "NUMERIC", "NUMBER")
private val ORACLE_SIZED_TYPES = setOf(
    "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "CHAR", "NCHAR",
    "DECIMAL", "NUMERIC", "NUMBER",
)

private fun formatOracleDbType(typeName: String, size: Int, scale: Int?): String {
    val upper = typeName.uppercase()
    return when {
        scale != null && scale > 0 && upper in ORACLE_NUMERIC_TYPES -> "$typeName($size,$scale)"
        size > 0 && upper in ORACLE_SIZED_TYPES -> "$typeName($size)"
        else -> typeName
    }
}
