package onl.ycode.stormify.schemasync.db

import java.sql.Connection

/**
 * Database dialects supported by schema-sync. Detected from JDBC
 * `DatabaseMetaData.getDatabaseProductName()`. Used to (a) select the right
 * defaults overrides and (b) emit correct ALTER TABLE / column syntax.
 */
enum class Dialect(val tomlKey: String) {
    POSTGRESQL("postgresql"),
    MYSQL("mysql"),
    MARIADB("mariadb"),
    ORACLE("oracle"),
    MSSQL("mssql"),
    SQLITE("sqlite"),
    GENERIC("generic");

    /**
     * True for dialect-internal bookkeeping tables that JDBC's `getTables`
     * may surface alongside user tables. Most dialects already scope us via
     * `defaultSchema` (PostgreSQL → `public`, MSSQL → `dbo`, Oracle →
     * `USER_TABLES`); only SQLite leaks `sqlite_*` housekeeping tables.
     */
    fun isSystemTable(table: String): Boolean = when (this) {
        SQLITE -> table.startsWith("sqlite_")
        else -> false
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
