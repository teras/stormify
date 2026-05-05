package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.model.SqlTypeCode

/**
 * Microsoft SQL Server introspection paths via `INFORMATION_SCHEMA`. Scoped
 * to the dialect's default schema (`dbo` unless the connection URL overrides
 * it). MSSQL-specific types like `datetime2` and `datetimeoffset` are mapped
 * onto the platform-independent [SqlTypeCode] codes.
 */

internal fun mssqlListTables(stormify: Stormify): List<DbIntrospector.TableId> {
    val schema = Dialect.MSSQL.queryDefaultSchema(stormify) ?: "dbo"
    val out = mutableListOf<DbIntrospector.TableId>()
    stormify.read<Row>(
        """
        SELECT table_name, table_type
        FROM information_schema.tables
        WHERE table_schema = ?
          AND table_type IN ('BASE TABLE', 'VIEW')
        ORDER BY table_name
        """.trimIndent(),
        schema,
    ).forEach { row ->
        val kind = if (row.str("table_type") == "VIEW") DbIntrospector.TableId.Kind.VIEW
        else DbIntrospector.TableId.Kind.TABLE
        out += DbIntrospector.TableId(null, row.str("table_name")!!, kind)
    }
    // Synonyms aren't visible through information_schema.tables; they
    // live in sys.synonyms. Surfaced as TABLEs so the diff layer treats
    // them like any other entity-mapped object.
    stormify.read<Row>(
        """
        SELECT s.name AS synonym_name
        FROM sys.synonyms s
        JOIN sys.schemas sch ON sch.schema_id = s.schema_id
        WHERE sch.name = ?
        ORDER BY s.name
        """.trimIndent(),
        schema,
    ).forEach { row ->
        out += DbIntrospector.TableId(null, row.str("synonym_name")!!, DbIntrospector.TableId.Kind.TABLE)
    }
    return out
}

internal fun mssqlListColumns(
    stormify: Stormify,
    tables: List<DbIntrospector.TableId>,
    onProgress: ((Int, Int) -> Unit)? = null,
): List<ColumnRef> {
    val schema = Dialect.MSSQL.queryDefaultSchema(stormify) ?: "dbo"
    val tableSet = tables.mapTo(HashSet()) { it.name }
    val out = mutableListOf<ColumnRef>()
    val seen = HashSet<String>()
    stormify.read<Row>(
        """
        SELECT table_name, column_name, data_type,
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
        out += mssqlRowToColumn(row, table)
    }
    // Synonyms: resolve each synonym to its base object and emit the
    // base's columns under the synonym's name. base_object_name is the
    // bracketed `[schema].[table]` form; PARSENAME extracts segments.
    stormify.read<Row>(
        """
        SELECT s.name AS synonym_name,
               c.name AS column_name,
               t.name AS data_type,
               c.max_length AS character_maximum_length,
               c.precision AS numeric_precision,
               c.scale AS numeric_scale,
               c.is_nullable AS is_nullable,
               OBJECT_DEFINITION(c.default_object_id) AS column_default
        FROM sys.synonyms s
        JOIN sys.schemas sch ON sch.schema_id = s.schema_id
        JOIN sys.objects o ON o.object_id = OBJECT_ID(s.base_object_name)
        JOIN sys.columns c ON c.object_id = o.object_id
        JOIN sys.types t ON t.user_type_id = c.user_type_id
        WHERE sch.name = ?
        ORDER BY s.name, c.column_id
        """.trimIndent(),
        schema,
    ).forEach { row ->
        val table = row.str("synonym_name") ?: return@forEach
        if (table !in tableSet) return@forEach
        if (seen.add(table)) onProgress?.invoke(seen.size.coerceAtMost(tables.size), tables.size)
        out += mssqlSynonymRowToColumn(row, table)
    }
    return out
}

private fun mssqlSynonymRowToColumn(row: Row, table: String): ColumnRef {
    val name = row.str("column_name")!!
    val dataType = row.str("data_type") ?: ""
    // sys.columns reports max_length in bytes (×2 for nvarchar/nchar),
    // and `-1` for MAX. We only need precision-grade fidelity for the
    // diff layer, so we collapse `-1` to 0 and divide unicode types
    // back to character length.
    val rawLen = row.intOrZero("character_maximum_length")
    val charLen = when {
        rawLen <= 0 -> 0
        dataType.lowercase() in setOf("nvarchar", "nchar", "ntext") -> rawLen / 2
        else -> rawLen
    }
    val numPrec = row.intOrZero("numeric_precision")
    val numScale = row.intOrNull("numeric_scale")
    // sys.columns.is_nullable is a Boolean (BIT) — Stormify presents it
    // as Number/Boolean depending on driver; reuse the Yes/No helper.
    val nullable = row.boolFromYesNo("is_nullable", default = true)
    val rawDefault = row.str("column_default")?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { stripMssqlDefaultParens(it) }
    val sqlType = mssqlTypeToSqlType(dataType)
    val size = if (charLen > 0) charLen else numPrec
    return ColumnRef(
        schema = null,
        table = table,
        name = name,
        category = TypeCategoryMapper.categoryFor(sqlType),
        dbType = formatMssqlDbType(dataType, size, numScale),
        sqlType = sqlType,
        nullable = nullable,
        family = TypeCategoryMapper.familyFor(sqlType, numScale),
        precision = size.takeIf { it > 0 },
        defaultValue = rawDefault,
    )
}

private fun mssqlRowToColumn(row: Row, table: String): ColumnRef {
    val name = row.str("column_name")!!
    val dataType = row.str("data_type") ?: ""
    val charLen = row.intOrZero("character_maximum_length")
    val numPrec = row.intOrZero("numeric_precision")
    val numScale = row.intOrNull("numeric_scale")
    val nullable = row.boolFromYesNo("is_nullable")
    val rawDefault = row.str("column_default")?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { stripMssqlDefaultParens(it) }
    val sqlType = mssqlTypeToSqlType(dataType)
    val size = if (charLen > 0) charLen else numPrec
    return ColumnRef(
        schema = null,
        table = table,
        name = name,
        category = TypeCategoryMapper.categoryFor(sqlType),
        dbType = formatMssqlDbType(dataType, size, numScale),
        sqlType = sqlType,
        nullable = nullable,
        family = TypeCategoryMapper.familyFor(sqlType, numScale),
        precision = size.takeIf { it > 0 },
        defaultValue = rawDefault,
    )
}

private fun mssqlTypeToSqlType(dataType: String): SqlTypeCode = when (dataType.lowercase()) {
    "varchar" -> SqlTypeCode.VARCHAR
    "nvarchar" -> SqlTypeCode.NVARCHAR
    "char" -> SqlTypeCode.CHAR
    "nchar" -> SqlTypeCode.NCHAR
    "text" -> SqlTypeCode.LONGVARCHAR
    "ntext" -> SqlTypeCode.LONGNVARCHAR
    "xml" -> SqlTypeCode.SQLXML
    "tinyint" -> SqlTypeCode.TINYINT
    "smallint" -> SqlTypeCode.SMALLINT
    "int" -> SqlTypeCode.INTEGER
    "bigint" -> SqlTypeCode.BIGINT
    "decimal", "numeric" -> SqlTypeCode.DECIMAL
    "money", "smallmoney" -> SqlTypeCode.DECIMAL
    "float" -> SqlTypeCode.DOUBLE
    "real" -> SqlTypeCode.REAL
    "bit" -> SqlTypeCode.BOOLEAN
    "date" -> SqlTypeCode.DATE
    "time" -> SqlTypeCode.TIME
    "datetime", "datetime2", "smalldatetime" -> SqlTypeCode.TIMESTAMP
    "datetimeoffset" -> SqlTypeCode.TIMESTAMP_WITH_TIMEZONE
    "binary" -> SqlTypeCode.BINARY
    "varbinary" -> SqlTypeCode.VARBINARY
    "image" -> SqlTypeCode.BLOB
    "uniqueidentifier" -> SqlTypeCode.OTHER
    else -> SqlTypeCode.OTHER
}

/** MSSQL wraps `COLUMN_DEFAULT` in one or more pairs of parentheses
 *  (e.g. `((0))` for `0`). Strip matched outer pairs so the value
 *  matches what users wrote in their `DEFAULT` clause. */
private fun stripMssqlDefaultParens(raw: String): String {
    var s = raw
    while (s.length >= 2 && s.first() == '(' && s.last() == ')') {
        var depth = 0
        var matchedOuter = true
        for ((i, ch) in s.withIndex()) {
            if (ch == '(') depth++
            else if (ch == ')') {
                depth--
                if (depth == 0 && i != s.length - 1) {
                    matchedOuter = false
                    break
                }
            }
        }
        if (!matchedOuter) break
        s = s.substring(1, s.length - 1)
    }
    return s
}

private val MSSQL_NUMERIC_TYPES = setOf("decimal", "numeric")
private val MSSQL_SIZED_TYPES = setOf("varchar", "nvarchar", "char", "nchar", "varbinary", "binary")

private fun formatMssqlDbType(dataType: String, size: Int, scale: Int?): String {
    val lower = dataType.lowercase()
    return when {
        scale != null && scale > 0 && lower in MSSQL_NUMERIC_TYPES -> "$dataType($size,$scale)"
        size > 0 && lower in MSSQL_SIZED_TYPES -> "$dataType($size)"
        else -> dataType
    }
}
