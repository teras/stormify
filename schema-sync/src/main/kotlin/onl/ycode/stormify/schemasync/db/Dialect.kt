package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.schemasync.model.ColumnRef
import java.sql.Connection

/**
 * Database dialects supported by schema-sync. Detected from JDBC
 * `DatabaseMetaData.getDatabaseProductName()`. This is the **single place**
 * where dialect-specific behavior lives — callers should never branch on the
 * dialect identity directly.
 *
 * Each constant carries its own quirks via constructor parameters and
 * overridable methods; the JDBC-metadata-friendly defaults handle most
 * dialects, while Oracle bypasses JDBC entirely via dictionary queries.
 */
enum class Dialect(
    val tomlKey: String,
    /** ALTER TABLE … <keyword> for column addition. MSSQL/Oracle drop COLUMN. */
    val alterAddKeyword: String = "ADD COLUMN",
    private val quoteOpen: String = "\"",
    private val quoteClose: String = "\"",
    /** SQLite cannot add a PRIMARY KEY column via ALTER. */
    val supportsAlterAddPrimaryKey: Boolean = true,
    /** SQLite cannot add a NOT NULL column without a DEFAULT via ALTER. */
    val supportsAlterAddNotNullWithoutDefault: Boolean = true,
    /** TRUE/FALSE literal flavour. Most engines accept TRUE/FALSE; legacy
     *  ones (MySQL/MariaDB/MSSQL/SQLite/Oracle) only understand 1/0. */
    private val booleanAsBit: Boolean = false,
) {
    POSTGRESQL("postgresql"),
    MYSQL("mysql", quoteOpen = "`", quoteClose = "`", booleanAsBit = true),
    MARIADB("mariadb", quoteOpen = "`", quoteClose = "`", booleanAsBit = true),
    ORACLE("oracle", alterAddKeyword = "ADD", booleanAsBit = true),
    MSSQL("mssql", alterAddKeyword = "ADD", quoteOpen = "[", quoteClose = "]", booleanAsBit = true),
    SQLITE(
        "sqlite",
        supportsAlterAddPrimaryKey = false,
        supportsAlterAddNotNullWithoutDefault = false,
        booleanAsBit = true,
    ),
    GENERIC("generic");

    /** Wrap [name] for safe use as a SQL identifier in this dialect. */
    fun quoteIdentifier(name: String): String = "$quoteOpen$name$quoteClose"

    /** SQL literal for [v]. */
    fun booleanLiteral(v: Boolean): String = when {
        booleanAsBit -> if (v) "1" else "0"
        else -> if (v) "TRUE" else "FALSE"
    }

    /**
     * True for dialect-internal bookkeeping tables that JDBC's `getTables`
     * may surface alongside user tables. Most dialects already scope us via
     * `defaultSchema`; only SQLite leaks `sqlite_*` housekeeping tables.
     */
    fun isSystemTable(table: String): Boolean =
        this == SQLITE && table.startsWith("sqlite_")

    /**
     * The schema that JDBC `getTables` / `getColumns` should be scoped to.
     * For dialects with first-class schemas we trust `Connection.getSchema()`
     * so URL parameters like `?currentSchema=…` are honoured, falling back
     * to the conventional default. MySQL/MariaDB use catalogs (== databases)
     * rather than schemas, so we leave the schema pattern null.
     */
    fun defaultSchema(conn: Connection): String? = when (this) {
        POSTGRESQL -> conn.connectionSchemaOrNull() ?: "public"
        MSSQL -> conn.connectionSchemaOrNull() ?: "dbo"
        ORACLE -> conn.metaData.userName
        else -> null
    }

    /**
     * Returns a dialect-specific table list when the JDBC metadata path
     * needs to be bypassed (Oracle PL/SQL incompatibility), otherwise null
     * — the caller falls back to `DatabaseMetaData.getTables`.
     */
    fun listTablesViaDictionary(conn: Connection): List<DbIntrospector.TableId>? = when (this) {
        ORACLE -> oracleListTables(conn)
        else -> null
    }

    /**
     * Returns a dialect-specific column list when the dialect has its own
     * dictionary path (Oracle: USER_TAB_COLUMNS), otherwise null — the
     * caller falls back to `DatabaseMetaData.getColumns`.
     */
    fun listColumnsViaDictionary(
        conn: Connection,
        tables: List<DbIntrospector.TableId>,
    ): List<ColumnRef>? = when (this) {
        ORACLE -> oracleListColumns(conn, tables)
        else -> null
    }

    /**
     * Returns every foreign-key edge in the schema in **one** roundtrip
     * via the dialect's catalog views. Replaces the per-table
     * `getImportedKeys` loop, which is the dominant cost on large
     * schemas. Returns null when no bulk dictionary path exists; the
     * caller falls back to the per-table loop.
     */
    internal fun listForeignKeys(conn: Connection): List<FkEdge>? = when (this) {
        ORACLE -> oracleListForeignKeys(conn)
        POSTGRESQL -> postgresListForeignKeys(conn)
        MYSQL, MARIADB -> mysqlListForeignKeys(conn)
        MSSQL -> mssqlListForeignKeys(conn)
        SQLITE, GENERIC -> null
    }

    companion object {
        fun detect(conn: Connection): Dialect {
            val name = conn.metaData.databaseProductName.lowercase()
            return when {
                "postgres" in name -> POSTGRESQL
                "mariadb" in name -> MARIADB
                "mysql" in name -> MYSQL
                "oracle" in name -> ORACLE
                "microsoft" in name || "sql server" in name -> MSSQL
                "sqlite" in name -> SQLITE
                else -> GENERIC
            }
        }

        fun fromKey(key: String?): Dialect? =
            key?.let { k -> entries.firstOrNull { it.tomlKey.equals(k, ignoreCase = true) } }
    }
}

private fun Connection.connectionSchemaOrNull(): String? =
    runCatching { schema?.takeIf { it.isNotBlank() } }.getOrNull()
