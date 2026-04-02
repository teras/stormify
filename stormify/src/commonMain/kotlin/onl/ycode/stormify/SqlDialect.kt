package onl.ycode.stormify

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.DatabaseMetaData
import onl.ycode.kdbc.DataSource

/********************************************************************
 * This part defines the sequence dialects for different databases. *
 ********************************************************************/

private val sequenceFromDual = { it: String, count: Int -> "SELECT $it.NEXTVAL FROM dual CONNECT BY level <= $count" }

private val sequenceNextValueFor = { it: String, count: Int -> "SELECT NEXT VALUE FOR $it FROM (SELECT TOP $count 1 x FROM sys.objects) t" }

private val sequenceNextval = { it: String, count: Int -> "SELECT nextval('$it') FROM generate_series(1, $count)" }

private val sequenceMariaDb = { it: String, count: Int -> "SELECT NEXTVAL($it) FROM seq_1_to_$count" }

/**********************************************************************
 * This part defines the pagination dialects for different databases. *
 ********************************************************************/

private val formatterLimitOffset =
    { distinct: String, tableName: String, constraints: String, sorting: String, lowBound: Int, upperBound: Int ->
        "SELECT " + distinct + "* FROM " + tableName + constraints + " ORDER BY " + sorting + " LIMIT " + (upperBound - lowBound) + " OFFSET " + lowBound
    }

private val formatterRowsFetch =
    { distinct: String, tableName: String, constraints: String, sorting: String, lowBound: Int, upperBound: Int ->
        "SELECT " + distinct + "* FROM " + tableName + constraints + " ORDER BY " + sorting + " OFFSET " + lowBound + " ROWS FETCH NEXT " + (upperBound - lowBound) + " ROWS ONLY"
    }

private val formatterRowNumber =
    { distinct: String, tableName: String, constraints: String, sorting: String, lowBound: Int, upperBound: Int ->
        ("SELECT * FROM (SELECT " + distinct + tableName
                + ".*, ROW_NUMBER() OVER (ORDER BY " + sorting + ") rn from " + tableName + constraints
                + ") b WHERE b.rn > " + lowBound + " AND b.rn <= " + upperBound + " ORDER BY rn")
    }

/***********************************************************************
 * This part defines the order by Id dialects for different databases. *
 ********************************************************************/

private val orderById =
    { idColumn: String, idValue: NativeBigInteger? -> if (idValue == null) null else "($idColumn = $idValue) DESC" }


private val orderByCase =
    { idColumn: String, idValue: NativeBigInteger? -> if (idValue == null) null else "CASE WHEN $idColumn = $idValue THEN 0 ELSE 1 END" }


/**
 * SQL dialect definitions for different database systems.
 *
 * This enumeration provides database-specific SQL generation for:
 * - Sequence value retrieval (Oracle, PostgreSQL, SQL Server, H2, HSQLDB, Derby)
 * - Generated key retrieval strategies (IDENTITY, RETURNING, etc.)
 * - Pagination queries (LIMIT/OFFSET, ROWS FETCH, ROW_NUMBER())
 * - Special ORDER BY handling for specific row positioning
 *
 * ## Supported Databases
 *
 * **Full Support:**
 * - MySQL 5.x, 8.x+ (auto-increment, sequences in 8.0+)
 * - MariaDB <10.3, 10.3+ (sequences supported in 10.3+)
 * - PostgreSQL (sequences, RETURNING clause)
 * - Oracle 11g and older (sequences, ROW_NUMBER pagination)
 * - Oracle 12c+ (sequences, OFFSET/FETCH pagination)
 * - SQL Server 2008 and older (sequences, ROW_NUMBER pagination)
 * - SQL Server 2012+ (sequences, OFFSET/FETCH pagination)
 * - SQLite (auto-increment, no sequences)
 * - H2 (sequences, PostgreSQL-compatible)
 * - HSQLDB (sequences)
 * - Apache Derby (sequences)
 *
 * ## Automatic Detection
 *
 * The dialect is automatically detected from JDBC `DatabaseMetaData`:
 * ```kotlin
 * val stormify = Stormify(dataSource)
 * println(stormify.sqlDialect) // e.g., MYSQL_NEW, POSTGRESQL, etc.
 * ```
 *
 * Detection is based on:
 * - Database product name (case-insensitive matching)
 * - Major and minor version numbers
 * - Product version string (for MariaDB detection)
 *
 * ## Dialect Features
 *
 * Each dialect defines:
 * 1. **Sequence SQL**: How to fetch next value from a sequence
 *    - Oracle: `SELECT seq_name.NEXTVAL FROM dual`
 *    - PostgreSQL: `SELECT nextval('seq_name')`
 *    - SQL Server/H2/HSQLDB/Derby: `SELECT NEXT VALUE FOR seq_name`
 *    - MySQL/SQLite: `null` (use auto-increment)
 *
 * 2. **Generated Key Retrieval**: How auto-generated IDs are retrieved
 *    - `BY_INDEX`: Get generated key by index (MySQL, MariaDB, SQLite, H2)
 *    - `BY_NAME`: Get generated key by column name (PostgreSQL, SQL Server, HSQLDB, Derby)
 *    - `NONE`: No automatic retrieval, manual handling (Oracle)
 *
 * 3. **Pagination**: SQL syntax for LIMIT/OFFSET functionality
 *    - LIMIT/OFFSET: MySQL, MariaDB, PostgreSQL, SQLite, H2
 *    - OFFSET/FETCH: Oracle 12c+, SQL Server 2012+, Derby
 *    - ROW_NUMBER(): Oracle 11g, SQL Server 2008
 *
 * ## Fallback Behavior
 *
 * If database is not recognized, uses [UNKNOWN] dialect with:
 * - No sequence support
 * - No generated key retrieval
 * - LIMIT/OFFSET pagination (most compatible)
 *
 * @see findDialect
 */
enum class SqlDialect(
    /**
     * A helper method to ask for sequences on different databases. As input is the name of the
     * sequence and as output the query to get the next value from the sequence.
     */
    val sequenceDialect: (String, Int) -> String?,
    /**
     * A helper method to ask for order by id on different databases. This is used to create a query that,
     * before any other sorting, fetches a specific entity first.
     *
     *
     * The first parameter is the name of the id column, and the second is the value of the id.
     *
     *
     * The result is a string that can be used in the order by part of a query.
     */
    val orderByIdDialect: (String, NativeBigInteger?) -> String?,
    /**
     * A query formatter that generates SQL queries with different pagination methods.
     *
     * @see QueryFormatter
     */
    val queryFormatter: (distinct: String, tableName: String, constraints: String, sorting: String, lowBound: Int, upperBound: Int) -> String,
    /**
     * The method to create the query, how to retrieve the generated key from the database.
     */
    val generatedKeyRetrieval: GeneratedKeyRetrieval,
    /**
     * Whether this dialect supports the JDBC releaseSavepoint() operation.
     * Oracle and SQL Server do not support it.
     */
    val supportsReleaseSavepoint: Boolean = true
) {
    /**
     * The MariaDB dialect for versions older than 10.3.
     */
    MARIA_DB_OLD(
        { _, _ -> null },
        orderById,
        formatterLimitOffset,
        GeneratedKeyRetrieval.BY_INDEX
    ),

    /**
     * The MariaDB dialect for versions 10.3 and newer.
     */
    MARIA_DB_NEW(
        sequenceMariaDb,
        orderById,
        formatterLimitOffset, GeneratedKeyRetrieval.BY_INDEX
    ),

    /**
     * The MySQL dialect for versions older than 8.
     */
    MYSQL_OLD(
        { _, _ -> null },
        orderById,
        formatterLimitOffset, GeneratedKeyRetrieval.BY_INDEX
    ),

    /**
     * The MySQL dialect for versions 8 and newer.
     */
    MYSQL_NEW(
        { _, _ -> null },
        orderById,
        formatterLimitOffset, GeneratedKeyRetrieval.BY_INDEX
    ),

    /**
     * The Oracle dialect for versions 12 and newer.
     */
    ORACLE_NEW(
        sequenceFromDual,
        orderByCase,
        formatterRowsFetch, GeneratedKeyRetrieval.BY_NAME, supportsReleaseSavepoint = false
    ),

    /**
     * The Oracle dialect for versions older than 12.
     */
    ORACLE_OLD(
        sequenceFromDual,
        orderByCase,
        formatterRowNumber, GeneratedKeyRetrieval.NONE, supportsReleaseSavepoint = false
    ),

    /**
     * The PostgreSQL dialect.
     */
    POSTGRESQL(
        sequenceNextval,
        orderById,
        formatterLimitOffset, GeneratedKeyRetrieval.BY_NAME
    ),

    /**
     * The SQL Server dialect for versions 2012 and newer.
     */
    SQL_SERVER_NEW(
        sequenceNextValueFor,
        orderByCase,
        formatterRowsFetch, GeneratedKeyRetrieval.BY_INDEX, supportsReleaseSavepoint = false
    ),

    /**
     * The SQL Server dialect for versions older than 2012.
     */
    SQL_SERVER_OLD(
        sequenceNextValueFor,
        orderByCase,
        formatterRowNumber, GeneratedKeyRetrieval.BY_NAME, supportsReleaseSavepoint = false
    ),

    /**
     * The SQLite dialect.
     */
    SQLITE(
        { _, _ -> null },
        orderById,
        formatterLimitOffset, GeneratedKeyRetrieval.BY_INDEX
    ),

    /**
     * The H2 database dialect.
     */
    H2(
        sequenceNextValueFor,
        orderById,
        formatterLimitOffset, GeneratedKeyRetrieval.BY_INDEX
    ),

    /**
     * The HSQLDB (HyperSQL) database dialect.
     */
    HSQLDB(
        sequenceNextValueFor,
        orderById,
        formatterLimitOffset, GeneratedKeyRetrieval.BY_NAME
    ),

    /**
     * The Apache Derby database dialect.
     */
    DERBY(
        sequenceNextValueFor,
        orderById,
        formatterLimitOffset, GeneratedKeyRetrieval.BY_NAME
    ),

    /**
     * The dialect that is used when the database product name cannot be determined.
     */
    UNKNOWN(
        { _, _ -> null },
        orderByCase,
        formatterLimitOffset, GeneratedKeyRetrieval.NONE
    ),

    /**
     * A failsafe dialect, mostly in case of an error.
     */
    FAILSAFE(
        { _, _ -> null },
        orderByCase,
        formatterLimitOffset, GeneratedKeyRetrieval.NONE
    );

    enum class GeneratedKeyRetrieval {
        BY_INDEX, BY_NAME, NONE
    }

    fun prepareForInsert(conn: Connection, query: String, fetchGeneratedKeys: Boolean, pkColumn: String?): onl.ycode.kdbc.PreparedStatement =
        if (!fetchGeneratedKeys) conn.prepareStatement(query)
        else when (generatedKeyRetrieval) {
            GeneratedKeyRetrieval.BY_NAME -> conn.prepareStatement(query, arrayOf(pkColumn ?: ""))
            GeneratedKeyRetrieval.BY_INDEX -> conn.prepareStatement(query, returnGeneratedKeys = true)
            else -> conn.prepareStatement(query)
        }

    companion object {
        fun findDialect(dataSource: DataSource): SqlDialect {
            dataSource.getConnection().use { conn ->
                val metadata: DatabaseMetaData = conn.metaData
                val productName: String = metadata.databaseProductName.lowercase()
                val productVersion: String = metadata.databaseProductVersion.lowercase()
                val majorVersion: Int = metadata.databaseMajorVersion
                val minorVersion: Int = metadata.databaseMinorVersion
                return when {
                    productName.contains("oracle") -> if (majorVersion >= 12) ORACLE_NEW else ORACLE_OLD
                    productName.contains("sqlserver") || productName.contains("sql server") -> if (majorVersion >= 11) SQL_SERVER_NEW else SQL_SERVER_OLD
                    productName.contains("postgresql") -> POSTGRESQL
                    productName.contains("sqlite") -> SQLITE
                    productName.contains("h2") -> H2
                    productName.contains("hsql") -> HSQLDB
                    productName.contains("derby") -> DERBY
                    productName.contains("mariadb") ->
                        if (majorVersion > 10 || (majorVersion == 10 && minorVersion >= 3)) MARIA_DB_NEW
                        else MARIA_DB_OLD
                    productName.contains("mysql") && productVersion.contains("mariadb") ->
                        if (majorVersion > 10 || (majorVersion == 10 && minorVersion >= 3)) MARIA_DB_NEW
                        else MARIA_DB_OLD
                    productName.contains("mysql") -> if (majorVersion >= 8) MYSQL_NEW else MYSQL_OLD
                    else -> UNKNOWN
                }
            }
        }
    }
}