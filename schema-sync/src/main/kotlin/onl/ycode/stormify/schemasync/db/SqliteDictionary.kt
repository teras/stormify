package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.schemasync.model.ColumnRef
import onl.ycode.stormify.schemasync.model.SqlTypeCode

/**
 * SQLite introspection paths via `sqlite_master` joined with the
 * table-valued PRAGMA functions (SQLite 3.16+). One round-trip per
 * concept (tables, columns, foreign keys) instead of N+1 PRAGMA calls.
 *
 * SQLite uses dynamic typing — `pragma_table_info.type` is the declared
 * type as written in `CREATE TABLE`, not a normalized form. We match it
 * against well-known prefixes; unrecognized declarations fall back to
 * SQLite's affinity rules (TEXT, NUMERIC, INTEGER, REAL, BLOB).
 */

internal fun sqliteListTables(stormify: Stormify): List<DbIntrospector.TableId> =
    stormify.read<Row>(
        """
        SELECT name, type FROM sqlite_master
        WHERE type IN ('table', 'view')
          AND name NOT LIKE 'sqlite_%'
        ORDER BY name
        """.trimIndent(),
    ).map { row ->
        val kind = if (row.str("type") == "view") DbIntrospector.TableId.Kind.VIEW
        else DbIntrospector.TableId.Kind.TABLE
        DbIntrospector.TableId(null, row.str("name")!!, kind)
    }

internal fun sqliteListColumns(
    stormify: Stormify,
    tables: List<DbIntrospector.TableId>,
    onProgress: ((Int, Int) -> Unit)? = null,
): List<ColumnRef> {
    val tableSet = tables.mapTo(HashSet()) { it.name }
    val out = mutableListOf<ColumnRef>()
    val seen = HashSet<String>()
    stormify.readCursor<Row>(
        """
        SELECT m.name AS table_name, p.name AS column_name, p.type AS column_type,
               p."notnull" AS not_null, p.dflt_value AS default_value
        FROM sqlite_master m, pragma_table_info(m.name) p
        WHERE m.type IN ('table', 'view')
          AND m.name NOT LIKE 'sqlite_%'
        ORDER BY m.name, p.cid
        """.trimIndent(),
    ) { row ->
        val table = row.str("table_name") ?: return@readCursor
        if (table !in tableSet) return@readCursor
        if (seen.add(table)) onProgress?.invoke(seen.size.coerceAtMost(tables.size), tables.size)
        val name = row.str("column_name")!!
        val rawType = row.str("column_type")?.trim().orEmpty()
        val notNull = row.intOrZero("not_null") != 0
        val rawDefault = row.str("default_value")?.trim()?.takeIf { it.isNotEmpty() }
        val (sqlType, size, scale) = parseSqliteDeclaredType(rawType)
        out += ColumnRef(
            schema = null,
            table = table,
            name = name,
            category = TypeCategoryMapper.categoryFor(sqlType),
            dbType = if (rawType.isEmpty()) sqlType.name else rawType,
            sqlType = sqlType,
            nullable = !notNull,
            family = TypeCategoryMapper.familyFor(sqlType, scale),
            precision = size,
            defaultValue = rawDefault,
        )
    }
    return out
}

internal fun sqliteListForeignKeys(stormify: Stormify): List<FkEdge> =
    stormify.read<Row>(
        """
        SELECT m.name AS table_name, fk."from" AS column_name,
               fk."table" AS ref_table, fk."to" AS ref_column
        FROM sqlite_master m, pragma_foreign_key_list(m.name) fk
        WHERE m.type = 'table'
          AND m.name NOT LIKE 'sqlite_%'
        """.trimIndent(),
    ).mapNotNull { row ->
        val refColumn = row.str("ref_column") ?: return@mapNotNull null
        FkEdge(
            schema = null,
            table = row.str("table_name")!!,
            column = row.str("column_name")!!,
            refTable = row.str("ref_table")!!,
            refColumn = refColumn,
        )
    }

/** Parse a SQLite declared type like `VARCHAR(64)` or `NUMERIC(10,2)` into
 *  (sql type, size, scale). Falls back to SQLite affinity rules for
 *  unparseable declarations. */
private fun parseSqliteDeclaredType(raw: String): Triple<SqlTypeCode, Int?, Int?> {
    if (raw.isEmpty()) return Triple(SqlTypeCode.OTHER, null, null)
    val upper = raw.uppercase()
    val base: String
    var size: Int? = null
    var scale: Int? = null
    val parenStart = upper.indexOf('(')
    if (parenStart > 0 && upper.endsWith(")")) {
        base = upper.substring(0, parenStart).trim()
        val args = upper.substring(parenStart + 1, upper.length - 1).split(',').map { it.trim() }
        size = args.getOrNull(0)?.toIntOrNull()
        scale = args.getOrNull(1)?.toIntOrNull()
    } else {
        base = upper.trim()
    }
    val type = sqliteAffinityFor(base)
    return Triple(type, size, scale)
}

/** SQLite type affinity rules (https://sqlite.org/datatype3.html). */
private fun sqliteAffinityFor(declared: String): SqlTypeCode {
    val u = declared.uppercase()
    return when {
        // Specific mappings first
        u == "BOOLEAN" || u == "BOOL" -> SqlTypeCode.BOOLEAN
        u == "DATE" -> SqlTypeCode.DATE
        u == "TIME" -> SqlTypeCode.TIME
        u == "DATETIME" || u == "TIMESTAMP" -> SqlTypeCode.TIMESTAMP
        u == "TINYINT" -> SqlTypeCode.TINYINT
        u == "SMALLINT" -> SqlTypeCode.SMALLINT
        u == "BIGINT" || u == "INT8" -> SqlTypeCode.BIGINT
        // Affinity: if name contains "INT" → INTEGER
        "INT" in u -> SqlTypeCode.INTEGER
        // Affinity: TEXT / CHAR / CLOB → text
        "CLOB" in u -> SqlTypeCode.CLOB
        "CHAR" in u || "TEXT" in u -> SqlTypeCode.VARCHAR
        // Affinity: BLOB or empty → BLOB
        "BLOB" in u -> SqlTypeCode.BLOB
        // Affinity: REAL/FLOA/DOUB → REAL/DOUBLE
        "DOUB" in u -> SqlTypeCode.DOUBLE
        "REAL" in u || "FLOA" in u -> SqlTypeCode.REAL
        // Affinity: NUMERIC fallback
        "DECIMAL" in u || "NUMERIC" in u -> SqlTypeCode.NUMERIC
        else -> SqlTypeCode.OTHER
    }
}
