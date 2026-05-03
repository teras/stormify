package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.schemasync.model.ColumnRef
import java.sql.Connection

/**
 * Oracle-specific introspection paths. The Oracle JDBC driver ships
 * metadata PL/SQL blocks that reference columns absent from older DB
 * versions (ORA-00904); for both tables and columns we go straight to
 * the data dictionary instead.
 *
 * `USER_SYNONYMS` is also queried so the table list reflects what an
 * application user actually sees — many enterprise deployments expose
 * tables via synonyms in the user's schema rather than owning them
 * directly. Without synonyms the table list misses everything the
 * entity layer reads/writes.
 */

internal fun oracleListTables(conn: Connection): List<DbIntrospector.TableId> {
    val out = mutableListOf<DbIntrospector.TableId>()
    val sql = """
        SELECT TABLE_NAME, 'TABLE' AS K FROM USER_TABLES
        UNION ALL SELECT VIEW_NAME, 'VIEW' FROM USER_VIEWS
        UNION ALL SELECT SYNONYM_NAME, 'TABLE' FROM USER_SYNONYMS
    """.trimIndent()
    conn.prepareStatement(sql).use { ps ->
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                val kind = if (rs.getString(2) == "VIEW") DbIntrospector.TableId.Kind.VIEW
                else DbIntrospector.TableId.Kind.TABLE
                out += DbIntrospector.TableId(null, rs.getString(1).lowercase(), kind)
            }
        }
    }
    return out
}

internal fun oracleListColumns(
    conn: Connection,
    tables: List<DbIntrospector.TableId>,
): List<ColumnRef> {
    val userTables = tables.mapTo(HashSet()) { it.name }
    val out = mutableListOf<ColumnRef>()
    // Owned tables/views: USER_TAB_COLUMNS — direct, no joins.
    conn.prepareStatement(
        """
        SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, NULLABLE
        FROM USER_TAB_COLUMNS
        ORDER BY TABLE_NAME, COLUMN_ID
        """.trimIndent(),
    ).use { ps ->
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                val table = rs.getString("TABLE_NAME").lowercase()
                if (table !in userTables) continue
                out += oracleRowToColumn(rs, table)
            }
        }
    }
    // Synonyms: resolve to target (table_owner, table_name) and pull columns
    // from ALL_TAB_COLUMNS (visible cross-schema as long as the user has any
    // grant on the target). Reported under the synonym's own name so the
    // slot key matches what the entity layer wrote in code.
    conn.prepareStatement(
        """
        SELECT s.SYNONYM_NAME, c.COLUMN_NAME, c.DATA_TYPE, c.DATA_LENGTH,
               c.DATA_PRECISION, c.DATA_SCALE, c.NULLABLE
        FROM USER_SYNONYMS s
        JOIN ALL_TAB_COLUMNS c
          ON c.OWNER = s.TABLE_OWNER
         AND c.TABLE_NAME = s.TABLE_NAME
        ORDER BY s.SYNONYM_NAME, c.COLUMN_ID
        """.trimIndent(),
    ).use { ps ->
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                val table = rs.getString("SYNONYM_NAME").lowercase()
                if (table !in userTables) continue
                out += oracleRowToColumn(rs, table)
            }
        }
    }
    return out
}

private fun oracleRowToColumn(rs: java.sql.ResultSet, table: String): ColumnRef {
    val name = rs.getString("COLUMN_NAME").lowercase()
    val typeName = rs.getString("DATA_TYPE")
    val length = rs.getInt("DATA_LENGTH")
    val precision = rs.getInt("DATA_PRECISION").let { if (rs.wasNull()) 0 else it }
    val scale = rs.getInt("DATA_SCALE").let { if (rs.wasNull()) null else it }
    val nullable = rs.getString("NULLABLE") == "Y"
    val jdbcType = oracleTypeNameToJdbc(typeName)
    val size = if (typeName.startsWith("VARCHAR") || typeName.startsWith("CHAR")) length else precision
    return ColumnRef(
        schema = null,
        table = table,
        name = name,
        category = JdbcCategoryMapper.categoryFor(jdbcType),
        dbType = formatOracleDbType(typeName, size, scale),
        jdbcType = jdbcType,
        nullable = nullable,
        family = JdbcCategoryMapper.familyFor(jdbcType, scale),
        precision = precision.takeIf { it > 0 },
    )
}

private fun oracleTypeNameToJdbc(typeName: String): Int = when {
    typeName.startsWith("VARCHAR2") || typeName.startsWith("VARCHAR") -> java.sql.Types.VARCHAR
    typeName.startsWith("NVARCHAR") -> java.sql.Types.NVARCHAR
    typeName.startsWith("CHAR") -> java.sql.Types.CHAR
    typeName.startsWith("NCHAR") -> java.sql.Types.NCHAR
    typeName == "CLOB" -> java.sql.Types.CLOB
    typeName == "NCLOB" -> java.sql.Types.NCLOB
    typeName == "BLOB" || typeName == "RAW" || typeName.startsWith("RAW") -> java.sql.Types.BLOB
    typeName == "DATE" -> java.sql.Types.TIMESTAMP
    typeName.startsWith("TIMESTAMP(") || typeName == "TIMESTAMP" -> java.sql.Types.TIMESTAMP
    typeName.contains("WITH TIME ZONE") -> java.sql.Types.TIMESTAMP_WITH_TIMEZONE
    typeName == "FLOAT" -> java.sql.Types.DOUBLE
    typeName == "BINARY_FLOAT" -> java.sql.Types.FLOAT
    typeName == "BINARY_DOUBLE" -> java.sql.Types.DOUBLE
    typeName == "NUMBER" -> java.sql.Types.NUMERIC
    else -> java.sql.Types.OTHER
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
