package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.schemasync.model.ColumnRef

/**
 * Database dialects supported by schema-sync. Detected from
 * `stormify.sqlDialect`. This is the **single place** where dialect-specific
 * behavior lives — callers should never branch on the dialect identity
 * directly.
 *
 * Each constant carries its own quirks via constructor parameters and
 * overridable methods. Catalog introspection runs through dialect-specific
 * dictionary queries (no JDBC `DatabaseMetaData`), so the same code path
 * works on Kotlin/Native consumers when needed.
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
     * True for dialect-internal bookkeeping tables that the catalog views
     * may surface alongside user tables. Most dialects already scope us via
     * [queryDefaultSchema]; only SQLite leaks `sqlite_*` housekeeping tables.
     */
    fun isSystemTable(table: String): Boolean =
        this == SQLITE && table.startsWith("sqlite_")

    /**
     * Asks the database for the default schema/user this session sees.
     * Mirrors what the JDBC driver historically returned through
     * `Connection.getSchema()` / `metaData.getUserName()`, but expressed
     * as a portable per-dialect SQL query so the same path works through
     * a Stormify-backed consumer with no JDBC metadata access.
     *
     * Returns null for dialects with no schema concept (SQLite) or where
     * the catalog queries already filter by `DATABASE()` server-side
     * (MySQL/MariaDB).
     */
    fun queryDefaultSchema(stormify: Stormify): String? = when (this) {
        POSTGRESQL -> stormify.readOne<String>("SELECT current_schema()")
        MSSQL -> stormify.readOne<String>("SELECT SCHEMA_NAME()")
        ORACLE -> stormify.readOne<String>("SELECT USER FROM dual")
        MYSQL, MARIADB, SQLITE, GENERIC -> null
    }

    /** Catalog-driven table list. Returns null only for [GENERIC], which
     *  schema-sync does not support beyond a clear error from the caller. */
    fun listTablesViaDictionary(stormify: Stormify): List<DbIntrospector.TableId>? = when (this) {
        ORACLE -> oracleListTables(stormify)
        POSTGRESQL -> postgresListTables(stormify)
        MYSQL, MARIADB -> mysqlListTables(stormify)
        SQLITE -> sqliteListTables(stormify)
        MSSQL -> mssqlListTables(stormify)
        GENERIC -> null
    }

    /** Catalog-driven column list, scoped to [tables]. Returns null only for
     *  [GENERIC]. */
    fun listColumnsViaDictionary(
        stormify: Stormify,
        tables: List<DbIntrospector.TableId>,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): List<ColumnRef>? = when (this) {
        ORACLE -> oracleListColumns(stormify, tables, onProgress)
        POSTGRESQL -> postgresListColumns(stormify, tables, onProgress)
        MYSQL, MARIADB -> mysqlListColumns(stormify, tables, onProgress)
        SQLITE -> sqliteListColumns(stormify, tables, onProgress)
        MSSQL -> mssqlListColumns(stormify, tables, onProgress)
        GENERIC -> null
    }

    /** Every foreign-key edge in the schema in **one** roundtrip via the
     *  dialect's catalog views. Returns null only for [GENERIC]. */
    internal fun listForeignKeys(stormify: Stormify): List<FkEdge>? = when (this) {
        ORACLE -> oracleListForeignKeys(stormify)
        POSTGRESQL -> postgresListForeignKeys(stormify)
        MYSQL, MARIADB -> mysqlListForeignKeys(stormify)
        MSSQL -> mssqlListForeignKeys(stormify)
        SQLITE -> sqliteListForeignKeys(stormify)
        GENERIC -> null
    }

    companion object {
        /** Detects the dialect from `stormify.sqlDialect`. */
        fun detect(stormify: Stormify): Dialect = when (stormify.sqlDialect) {
            SqlDialect.POSTGRESQL -> POSTGRESQL
            SqlDialect.MYSQL_OLD, SqlDialect.MYSQL_NEW -> MYSQL
            SqlDialect.MARIA_DB_OLD, SqlDialect.MARIA_DB_NEW -> MARIADB
            SqlDialect.ORACLE_OLD, SqlDialect.ORACLE_NEW -> ORACLE
            SqlDialect.SQLITE -> SQLITE
            SqlDialect.SQL_SERVER_OLD, SqlDialect.SQL_SERVER_NEW -> MSSQL
            else -> GENERIC
        }

        fun fromKey(key: String?): Dialect? =
            key?.let { k -> entries.firstOrNull { it.tomlKey.equals(k, ignoreCase = true) } }
    }
}
