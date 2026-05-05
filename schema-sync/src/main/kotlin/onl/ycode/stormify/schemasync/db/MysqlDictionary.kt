package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.model.SqlTypeCode

/**
 * MySQL/MariaDB introspection paths via `information_schema`. Scoped to the
 * current connection's database (`DATABASE()`), so URL parameters like
 * `?database=...` are honoured. `tinyint(1)` is surfaced as BOOLEAN to match
 * the MySQL/MariaDB convention.
 */

internal fun mysqlListTables(stormify: Stormify): List<DbIntrospector.TableId> =
    stormify.read<Row>(
        """
        SELECT table_name, table_type
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_type IN ('BASE TABLE', 'VIEW')
        ORDER BY table_name
        """.trimIndent(),
    ).map { row ->
        val kind = if (row.str("table_type") == "VIEW") DbIntrospector.TableId.Kind.VIEW
        else DbIntrospector.TableId.Kind.TABLE
        DbIntrospector.TableId(null, row.str("table_name")!!, kind)
    }

internal fun mysqlListColumns(
    stormify: Stormify,
    tables: List<DbIntrospector.TableId>,
    onProgress: ((Int, Int) -> Unit)? = null,
): List<ColumnRef> {
    val tableSet = tables.mapTo(HashSet()) { it.name }
    val out = mutableListOf<ColumnRef>()
    val seen = HashSet<String>()
    stormify.read<Row>(
        """
        SELECT table_name, column_name, data_type, column_type,
               character_maximum_length, numeric_precision, numeric_scale,
               is_nullable, column_default
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
        ORDER BY table_name, ordinal_position
        """.trimIndent(),
    ).forEach { row ->
        val table = row.str("table_name") ?: return@forEach
        if (table !in tableSet) return@forEach
        if (seen.add(table)) onProgress?.invoke(seen.size.coerceAtMost(tables.size), tables.size)
        out += mysqlRowToColumn(row, table)
    }
    return out
}

private fun mysqlRowToColumn(row: Row, table: String): ColumnRef {
    val name = row.str("column_name")!!
    val dataType = row.str("data_type") ?: ""
    val columnType = row.str("column_type") ?: ""
    val charLen = (row["character_maximum_length"] as? Number)?.toLong() ?: 0L
    val numPrec = row.intOrZero("numeric_precision")
    val numScale = row.intOrNull("numeric_scale")
    val nullable = row.boolFromYesNo("is_nullable")
    val rawDefault = row.str("column_default")?.trim()?.takeIf { it.isNotEmpty() }
    val sqlType = mysqlTypeToSqlType(dataType, columnType)
    val size = if (charLen in 1..Int.MAX_VALUE) charLen.toInt() else numPrec
    return ColumnRef(
        schema = null,
        table = table,
        name = name,
        category = TypeCategoryMapper.categoryFor(sqlType),
        dbType = formatMysqlDbType(dataType, size, numScale),
        sqlType = sqlType,
        nullable = nullable,
        family = TypeCategoryMapper.familyFor(sqlType, numScale),
        precision = size.takeIf { it > 0 },
        defaultValue = rawDefault,
    )
}

private fun mysqlTypeToSqlType(dataType: String, columnType: String): SqlTypeCode = when (dataType.lowercase()) {
    "varchar" -> SqlTypeCode.VARCHAR
    "char" -> SqlTypeCode.CHAR
    "text", "tinytext" -> SqlTypeCode.LONGVARCHAR
    "mediumtext", "longtext" -> SqlTypeCode.CLOB
    "tinyint" ->
        // MySQL/MariaDB convention: tinyint(1) is the canonical Boolean column type.
        if (columnType.equals("tinyint(1)", ignoreCase = true)) SqlTypeCode.BOOLEAN
        else SqlTypeCode.TINYINT
    "smallint" -> SqlTypeCode.SMALLINT
    "mediumint", "int", "integer" -> SqlTypeCode.INTEGER
    "bigint" -> SqlTypeCode.BIGINT
    "decimal", "numeric" -> SqlTypeCode.DECIMAL
    "float" -> SqlTypeCode.REAL
    "double", "real" -> SqlTypeCode.DOUBLE
    "bit", "boolean", "bool" -> SqlTypeCode.BOOLEAN
    "date", "year" -> SqlTypeCode.DATE
    "time" -> SqlTypeCode.TIME
    "datetime", "timestamp" -> SqlTypeCode.TIMESTAMP
    "binary" -> SqlTypeCode.BINARY
    "varbinary" -> SqlTypeCode.VARBINARY
    "tinyblob", "blob" -> SqlTypeCode.BLOB
    "mediumblob", "longblob" -> SqlTypeCode.BLOB
    "json" -> SqlTypeCode.LONGVARCHAR
    "enum", "set" -> SqlTypeCode.VARCHAR
    else -> SqlTypeCode.OTHER
}

private val MYSQL_NUMERIC_TYPES = setOf("decimal", "numeric")
private val MYSQL_SIZED_TYPES = setOf("varchar", "char", "varbinary", "binary")

private fun formatMysqlDbType(dataType: String, size: Int, scale: Int?): String {
    val lower = dataType.lowercase()
    return when {
        scale != null && scale > 0 && lower in MYSQL_NUMERIC_TYPES -> "$dataType($size,$scale)"
        size > 0 && lower in MYSQL_SIZED_TYPES -> "$dataType($size)"
        else -> dataType
    }
}
