package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.schemasync.model.ColumnRef
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** Reads the target schema via JDBC `DatabaseMetaData`. */
class DbIntrospector(private val conn: Connection) {

    data class TableId(val schema: String?, val name: String) {
        val key: String = listOfNotNull(schema, name).joinToString(".")
    }

    /** All user tables in the database. The dialect's default schema (`public`, `dbo`, …) is stripped so keys match entity table names. */
    fun listTables(): List<TableId> {
        val defaultSchema = defaultSchemaFor(conn)
        val dialect = Dialect.detect(conn)
        val out = mutableListOf<TableId>()
        // Oracle: bypass JDBC metadata to avoid driver/server PL/SQL incompatibility.
        if (dialect == Dialect.ORACLE) {
            conn.prepareStatement("SELECT TABLE_NAME FROM USER_TABLES").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) out += TableId(null, rs.getString(1).lowercase())
                }
            }
            return out
        }
        conn.metaData.getTables(null, defaultSchema, "%", arrayOf("TABLE")).use { rs ->
            while (rs.next()) {
                val name = rs.getString("TABLE_NAME") ?: continue
                if (dialect.isSystemTable(name)) continue
                val rawSchema = rs.getString("TABLE_SCHEM")
                val schema = if (rawSchema != null && rawSchema.equals(defaultSchema, ignoreCase = true)) null else rawSchema
                out += TableId(schema, name)
            }
        }
        return out
    }

    /**
     * The schema that JDBC `getTables` / `getColumns` should be scoped to.
     * For dialects that have first-class schemas (PostgreSQL, MSSQL), we trust
     * `Connection.getSchema()` so URL parameters like `?currentSchema=…` are
     * honoured; we only fall back to the dialect's conventional default when
     * the driver returns nothing. MySQL/MariaDB use catalogs (== databases)
     * rather than schemas, so we leave the schema pattern null and let the
     * URL's database segment scope the query.
     */
    private fun defaultSchemaFor(conn: java.sql.Connection): String? {
        return when (Dialect.detect(conn)) {
            Dialect.POSTGRESQL -> conn.connectionSchemaOrNull() ?: "public"
            Dialect.MSSQL -> conn.connectionSchemaOrNull() ?: "dbo"
            Dialect.ORACLE -> conn.metaData.userName
            else -> null
        }
    }

    private fun java.sql.Connection.connectionSchemaOrNull(): String? =
        runCatching { schema?.takeIf { it.isNotBlank() } }.getOrNull()

    /**
     * All columns in user tables. Deterministic-type columns (Boolean, Date, UUID, …)
     * carry `category = null`; classifier UI filters them. Single-roundtrip via
     * one broad `getColumns(null,null,"%","%")` call with per-table fallback.
     */
    fun listColumns(onProgress: ((Int, Int) -> Unit)? = null): List<ColumnRef> {
        val defaultSchema = defaultSchemaFor(conn)
        val dialect = Dialect.detect(conn)
        val tables = listTables()
        val tableSet = tables.mapTo(HashSet()) { (it.schema ?: "") to it.name }
        onProgress?.invoke(0, tables.size)

        val results = mutableListOf<ColumnRef>()
        val meta = conn.metaData

        // Oracle JDBC driver versions sometimes ship a metadata PL/SQL block that
        // references columns absent from older DB versions (ORA-00904). Bypass
        // the driver and query USER_TAB_COLUMNS directly.
        if (dialect == Dialect.ORACLE) return columnsViaOracleDictionary(onProgress, tables)

        try {
            val seenTables = HashSet<Pair<String, String>>()
            meta.getColumns(null, defaultSchema, "%", "%").use { rs ->
                while (rs.next()) {
                    val rawSchema = rs.getString("TABLE_SCHEM")
                    val schema = if (rawSchema != null && rawSchema.equals(defaultSchema, ignoreCase = true)) null else rawSchema
                    val table = rs.getString("TABLE_NAME") ?: continue
                    val key = (schema ?: "") to table
                    if (key !in tableSet) continue
                    if (seenTables.add(key)) onProgress?.invoke(seenTables.size, tables.size)
                    results += rowToColumn(rs, schema, table)
                }
            }
            onProgress?.invoke(tables.size, tables.size)
            return results
        } catch (broadFailure: Exception) {
            System.err.println("Broad getColumns failed (${broadFailure.message}); falling back per-table.")
            return columnsPerTable(meta, tables, onProgress)
        }
    }

    /** Bypasses Oracle JDBC metadata and queries USER_TAB_COLUMNS directly. */
    private fun columnsViaOracleDictionary(
        onProgress: ((Int, Int) -> Unit)?,
        tables: List<TableId>,
    ): List<ColumnRef> {
        val userTables = tables.mapTo(HashSet()) { it.name }
        val out = mutableListOf<ColumnRef>()
        val sql = """
            SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, NULLABLE
            FROM USER_TAB_COLUMNS
            ORDER BY TABLE_NAME, COLUMN_ID
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val table = rs.getString("TABLE_NAME").lowercase()
                    if (table !in userTables) continue
                    val name = rs.getString("COLUMN_NAME").lowercase()
                    val typeName = rs.getString("DATA_TYPE")
                    val length = rs.getInt("DATA_LENGTH")
                    val precision = rs.getInt("DATA_PRECISION").let { if (rs.wasNull()) 0 else it }
                    val scale = rs.getInt("DATA_SCALE").let { if (rs.wasNull()) null else it }
                    val nullable = rs.getString("NULLABLE") == "Y"
                    val jdbcType = oracleTypeNameToJdbc(typeName)
                    val size = if (typeName.startsWith("VARCHAR") || typeName.startsWith("CHAR")) length else precision
                    out += ColumnRef(
                        schema = null,
                        table = table,
                        name = name,
                        category = JdbcCategoryMapper.categoryFor(jdbcType),
                        dbType = formatDbType(typeName, size, scale),
                        jdbcType = jdbcType,
                        nullable = nullable,
                        family = JdbcCategoryMapper.familyFor(jdbcType),
                    )
                }
            }
        }
        onProgress?.invoke(tables.size, tables.size)
        return out
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

    private fun columnsPerTable(
        meta: java.sql.DatabaseMetaData,
        tables: List<TableId>,
        onProgress: ((Int, Int) -> Unit)?,
    ): List<ColumnRef> {
        val out = mutableListOf<ColumnRef>()
        tables.forEachIndexed { i, t ->
            onProgress?.invoke(i, tables.size)
            meta.getColumns(null, t.schema, t.name, "%").use { rs ->
                while (rs.next()) out += rowToColumn(rs, t.schema, t.name)
            }
        }
        onProgress?.invoke(tables.size, tables.size)
        return out
    }

    private fun rowToColumn(rs: ResultSet, schema: String?, table: String): ColumnRef {
        val name = rs.getString("COLUMN_NAME")
        val jdbcType = rs.getInt("DATA_TYPE")
        val typeName = rs.getString("TYPE_NAME")
        val size = rs.getInt("COLUMN_SIZE")
        val scale = readScale(rs)
        val nullable = rs.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls
        return ColumnRef(
            schema = schema,
            table = table,
            name = name,
            category = JdbcCategoryMapper.categoryFor(jdbcType),
            dbType = formatDbType(typeName, size, scale),
            jdbcType = jdbcType,
            nullable = nullable,
            family = JdbcCategoryMapper.familyFor(jdbcType),
        )
    }

    private fun readScale(rs: ResultSet): Int? {
        val s = rs.getInt("DECIMAL_DIGITS")
        return if (rs.wasNull()) null else s
    }

    private fun formatDbType(typeName: String, size: Int, scale: Int?): String {
        val upper = typeName.uppercase()
        return when {
            scale != null && scale > 0 && upper in NUMERIC_TYPES -> "$typeName($size,$scale)"
            size > 0 && upper in SIZED_TYPES -> "$typeName($size)"
            else -> typeName
        }
    }

    companion object {
        private val NUMERIC_TYPES = setOf("DECIMAL", "NUMERIC", "NUMBER")
        private val SIZED_TYPES = setOf(
            "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "CHAR", "NCHAR",
            "DECIMAL", "NUMERIC", "NUMBER",
        )

        /** Convenience constructor: opens connection from [ConnectionConfig]-style coords. */
        fun connect(url: String, user: String?, password: String?): Connection =
            DriverManager.getConnection(url, user, password)
    }
}
