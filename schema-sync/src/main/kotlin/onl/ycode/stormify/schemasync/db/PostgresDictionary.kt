package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.model.SqlTypeCode

/**
 * PostgreSQL-specific introspection paths via `information_schema`. Bypasses
 * `DatabaseMetaData.getTables/getColumns` so the introspection works through
 * a Stormify connection (no JDBC metadata access needed) and surfaces
 * information that JDBC metadata hides (e.g. raw `column_default`).
 */

internal fun postgresListTables(stormify: Stormify): List<DbIntrospector.TableId> {
    val schema = Dialect.POSTGRESQL.queryDefaultSchema(stormify) ?: "public"
    return stormify.read<Row>(
        """
        SELECT table_name, table_type
        FROM information_schema.tables
        WHERE table_schema = ?
          AND table_type IN ('BASE TABLE', 'VIEW')
        ORDER BY table_name
        """.trimIndent(),
        schema,
    ).map { row ->
        val kind = if (row.str("table_type") == "VIEW") DbIntrospector.TableId.Kind.VIEW
        else DbIntrospector.TableId.Kind.TABLE
        DbIntrospector.TableId(null, row.str("table_name")!!, kind)
    }
}

internal fun postgresListColumns(
    stormify: Stormify,
    tables: List<DbIntrospector.TableId>,
    onProgress: ((Int, Int) -> Unit)? = null,
): List<ColumnRef> {
    val schema = Dialect.POSTGRESQL.queryDefaultSchema(stormify) ?: "public"
    val tableSet = tables.mapTo(HashSet()) { it.name }
    val out = mutableListOf<ColumnRef>()
    val seen = HashSet<String>()
    stormify.read<Row>(
        """
        SELECT table_name, column_name, data_type, udt_name,
               character_maximum_length, numeric_precision, numeric_scale,
               is_nullable, column_default
        FROM information_schema.columns
        WHERE table_schema = ?
        ORDER BY table_name, ordinal_position
        """.trimIndent(),
        schema,
    ).forEach { row ->
        val table = row.str("table_name") ?: return@forEach
        if (table !in tableSet) return@forEach
        if (seen.add(table)) onProgress?.invoke(seen.size.coerceAtMost(tables.size), tables.size)
        out += postgresRowToColumn(row, table)
    }
    return out
}

private fun postgresRowToColumn(row: Row, table: String): ColumnRef {
    val name = row.str("column_name")!!
    val dataType = row.str("data_type") ?: ""
    val udtName = row.str("udt_name") ?: ""
    val charLen = row.intOrZero("character_maximum_length")
    val numPrec = row.intOrZero("numeric_precision")
    val numScale = row.intOrNull("numeric_scale")
    val nullable = row.boolFromYesNo("is_nullable")
    val rawDefault = row.str("column_default")?.trim()?.takeIf { it.isNotEmpty() }
    val sqlType = postgresTypeToSqlType(dataType, udtName)
    val size = if (charLen > 0) charLen else numPrec
    return ColumnRef(
        schema = null,
        table = table,
        name = name,
        category = TypeCategoryMapper.categoryFor(sqlType),
        dbType = formatPostgresDbType(dataType, udtName, size, numScale),
        sqlType = sqlType,
        nullable = nullable,
        family = TypeCategoryMapper.familyFor(sqlType, numScale),
        precision = size.takeIf { it > 0 },
        defaultValue = rawDefault,
    )
}

private fun postgresTypeToSqlType(dataType: String, udtName: String): SqlTypeCode = when (dataType.lowercase()) {
    "character varying" -> SqlTypeCode.VARCHAR
    "character" -> SqlTypeCode.CHAR
    "text" -> SqlTypeCode.LONGVARCHAR
    "smallint", "smallserial" -> SqlTypeCode.SMALLINT
    "integer", "serial" -> SqlTypeCode.INTEGER
    "bigint", "bigserial" -> SqlTypeCode.BIGINT
    "numeric", "decimal" -> SqlTypeCode.NUMERIC
    "real" -> SqlTypeCode.REAL
    "double precision" -> SqlTypeCode.DOUBLE
    "boolean" -> SqlTypeCode.BOOLEAN
    "bit", "bit varying" -> SqlTypeCode.BIT
    "date" -> SqlTypeCode.DATE
    "time without time zone" -> SqlTypeCode.TIME
    "time with time zone" -> SqlTypeCode.TIME_WITH_TIMEZONE
    "timestamp without time zone" -> SqlTypeCode.TIMESTAMP
    "timestamp with time zone" -> SqlTypeCode.TIMESTAMP_WITH_TIMEZONE
    "bytea" -> SqlTypeCode.VARBINARY
    "xml" -> SqlTypeCode.SQLXML
    "json", "jsonb" -> SqlTypeCode.LONGVARCHAR
    "uuid" -> SqlTypeCode.OTHER
    "user-defined" -> when (udtName.lowercase()) {
        "citext" -> SqlTypeCode.VARCHAR
        else -> SqlTypeCode.OTHER
    }
    else -> SqlTypeCode.OTHER
}

private val POSTGRES_NUMERIC_TYPES = setOf("numeric", "decimal")
private val POSTGRES_SIZED_TYPES = setOf("character varying", "character")

private fun formatPostgresDbType(dataType: String, udtName: String, size: Int, scale: Int?): String {
    val lower = dataType.lowercase()
    val baseName = if (lower == "user-defined") udtName else dataType
    return when {
        scale != null && scale > 0 && lower in POSTGRES_NUMERIC_TYPES -> "$baseName($size,$scale)"
        size > 0 && lower in POSTGRES_SIZED_TYPES -> "$baseName($size)"
        else -> baseName
    }
}
