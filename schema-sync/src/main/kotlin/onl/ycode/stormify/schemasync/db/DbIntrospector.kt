package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.schemasync.model.ColumnRef
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** Reads the target schema via JDBC `DatabaseMetaData`. All dialect-specific
 *  branches are routed through [Dialect]; this class never compares dialects. */
class DbIntrospector(private val conn: Connection) {

    private val dialect = Dialect.detect(conn)

    data class TableId(val schema: String?, val name: String, val kind: Kind = Kind.TABLE) {
        val key: String = listOfNotNull(schema, name).joinToString(".")
        enum class Kind { TABLE, VIEW }
    }

    /** All user tables AND views in the database. Views are surfaced too because
     *  many JPA / stormify entities are mapped to views rather than tables — the
     *  diff would otherwise flag every view-mapped entity as `entity-only`. The
     *  dialect's default schema is stripped so keys match entity table names. */
    fun listTables(): List<TableId> {
        dialect.listTablesViaDictionary(conn)?.let { return it }
        val defaultSchema = dialect.defaultSchema(conn)
        val out = mutableListOf<TableId>()
        conn.metaData.getTables(null, defaultSchema, "%", arrayOf("TABLE", "VIEW")).use { rs ->
            while (rs.next()) {
                val name = rs.getString("TABLE_NAME") ?: continue
                if (dialect.isSystemTable(name)) continue
                val rawSchema = rs.getString("TABLE_SCHEM")
                val schema = if (rawSchema != null && rawSchema.equals(defaultSchema, ignoreCase = true)) null else rawSchema
                val kind = if (rs.getString("TABLE_TYPE") == "VIEW") TableId.Kind.VIEW else TableId.Kind.TABLE
                out += TableId(schema, name, kind)
            }
        }
        return out
    }

    /**
     * All columns in user tables. Deterministic-type columns (Boolean, Date, UUID, …)
     * carry `category = null`; classifier UI filters them. Single-roundtrip via
     * one broad `getColumns(null,null,"%","%")` call with per-table fallback.
     */
    fun listColumns(onProgress: ((Int, Int) -> Unit)? = null): List<ColumnRef> {
        val tables = listTables()
        onProgress?.invoke(0, tables.size)
        dialect.listColumnsViaDictionary(conn, tables)?.let {
            onProgress?.invoke(tables.size, tables.size)
            return decorateWithFks(it, tables)
        }
        val defaultSchema = dialect.defaultSchema(conn)
        val tableSet = tables.mapTo(HashSet()) { (it.schema ?: "") to it.name }
        val results = mutableListOf<ColumnRef>()
        val meta = conn.metaData
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
            return decorateWithFks(results, tables)
        } catch (broadFailure: Exception) {
            System.err.println("Broad getColumns failed (${broadFailure.message}); falling back per-table.")
            return decorateWithFks(columnsPerTable(meta, tables, onProgress), tables)
        }
    }

    /** Read all FK edges. Bulk dialect dictionary first; per-table JDBC
     *  `getImportedKeys` only as fallback. */
    private fun readFks(tables: List<TableId>): List<FkEdge> {
        dialect.listForeignKeys(conn)?.takeIf { it.isNotEmpty() }?.let { return it }
        val out = mutableListOf<FkEdge>()
        val meta = conn.metaData
        for (t in tables) {
            try {
                meta.getImportedKeys(null, t.schema, t.name).use { rs ->
                    while (rs.next()) {
                        val column = rs.getString("FKCOLUMN_NAME") ?: continue
                        val refTable = rs.getString("PKTABLE_NAME") ?: continue
                        val refColumn = rs.getString("PKCOLUMN_NAME") ?: continue
                        out += FkEdge(t.schema, t.name, column, refTable, refColumn)
                    }
                }
            } catch (_: Exception) {
                // Some drivers don't support getImportedKeys reliably (e.g. pre-Oracle12); skip.
            }
        }
        return out
    }

    private fun decorateWithFks(cols: List<ColumnRef>, tables: List<TableId>): List<ColumnRef> {
        val edges = readFks(tables)
        if (edges.isEmpty()) return cols
        val byKey: Map<Triple<String, String, String>, FkEdge> = edges
            .associateBy { Triple(it.schema ?: "", it.table.lowercase(), it.column.lowercase()) }
        return cols.map { c ->
            val edge = byKey[Triple(c.schema ?: "", c.table.lowercase(), c.name.lowercase())] ?: return@map c
            c.copy(referencedTable = edge.refTable, referencedColumn = edge.refColumn)
        }
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
            family = JdbcCategoryMapper.familyFor(jdbcType, scale),
            precision = size.takeIf { it > 0 },
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

        /** Drivers we ship in the fatJar; pre-loaded so SPI registration isn't
         *  required (the fatJar's `DuplicatesStrategy.EXCLUDE` flattens
         *  `META-INF/services/java.sql.Driver` to a single entry, dropping the
         *  rest). Each entry is best-effort: a driver missing from the
         *  classpath is silently skipped. */
        private val BUNDLED_DRIVERS = listOf(
            "org.sqlite.JDBC",
            "org.postgresql.Driver",
            "org.mariadb.jdbc.Driver",
            "com.mysql.cj.jdbc.Driver",
            "oracle.jdbc.OracleDriver",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver",
        )

        private val driversInitialized = run {
            for (cls in BUNDLED_DRIVERS) {
                runCatching { Class.forName(cls) }
            }
            true
        }

        /** Convenience constructor: opens connection from [ConnectionConfig]-style coords. */
        fun connect(url: String, user: String?, password: String?): Connection {
            check(driversInitialized)
            return DriverManager.getConnection(url, user, password)
        }
    }
}
