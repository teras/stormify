package onl.ycode.stormify

/********************************************************************
 * This part defines the sequence dialects for different databases. *
 ********************************************************************/

private val sequenceFromDual = { it: String -> "SELECT $it.NEXTVAL FROM dual" }

private val sequenceNextValueFor = { it: String -> "SELECT NEXT VALUE FOR $it" }

private val sequenceNextval = { it: String -> "SELECT nextval('$it')" }

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
 * An enumeration of SQL dialects for different databases.
 *
 *
 * This enumeration is used to determine the current SQL dialect and provide helper
 * functions to generate SQL queries. The dialect is determined by the database product name.
 *
 *
 * If the database product name is not recognized, the dialect is set to [SqlDialect.UNKNOWN].
 */
enum class SqlDialect(
    /**
     * A helper method to ask for sequences on different databases. As input is the name of the
     * sequence and as output the query to get the next value from the sequence.
     */
    val sequenceDialect: (String) -> String?,
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
    val queryFormatter: (String, String, String, String, Int, Int) -> String,
    val generatedKeyRetrieval: GeneratedKeyRetrieval
) {
    /**
     * The MariaDB dialect for versions older than 10.3.
     */
    MARIA_DB_OLD(
        { _ -> null },
        orderById,
        formatterLimitOffset,
        GeneratedKeyRetrieval.BY_INDEX
    ),

    /**
     * The MariaDB dialect for versions 10.3 and newer.
     */
    MARIA_DB_NEW(
        sequenceNextValueFor,
        orderById,
        formatterLimitOffset, GeneratedKeyRetrieval.BY_INDEX
    ),

    /**
     * The MySQL dialect for versions older than 8.
     */
    MYSQL_OLD(
        { _ -> null },
        orderById,
        formatterLimitOffset, GeneratedKeyRetrieval.BY_INDEX
    ),

    /**
     * The MySQL dialect for versions 8 and newer.
     */
    MYSQL_NEW(
        sequenceNextValueFor,
        orderById,
        formatterLimitOffset, GeneratedKeyRetrieval.BY_INDEX
    ),

    /**
     * The Oracle dialect for versions 12 and newer.
     */
    ORACLE_NEW(
        sequenceFromDual,
        orderByCase,
        formatterRowsFetch, GeneratedKeyRetrieval.NONE
    ),

    /**
     * The Oracle dialect for versions older than 12.
     */
    ORACLE_OLD(
        sequenceFromDual,
        orderByCase,
        formatterRowNumber, GeneratedKeyRetrieval.NONE
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
        formatterRowsFetch, GeneratedKeyRetrieval.BY_NAME
    ),

    /**
     * The SQL Server dialect for versions older than 2012.
     */
    SQL_SERVER_OLD(
        sequenceNextValueFor,
        orderByCase,
        formatterRowNumber, GeneratedKeyRetrieval.BY_NAME
    ),

    /**
     * The SQLite dialect.
     */
    SQLITE(
        { _ -> null },
        orderById,
        formatterLimitOffset, GeneratedKeyRetrieval.BY_INDEX
    ),

    /**
     * The dialect that is used when the database product name cannot be determined.
     */
    UNKNOWN(
        { _ -> null },
        orderByCase,
        formatterLimitOffset, GeneratedKeyRetrieval.NONE
    ),

    /**
     * A failsafe dialect, mostly in case of an error.
     */
    FAILSAFE(
        { _ -> null },
        orderByCase,
        formatterLimitOffset, GeneratedKeyRetrieval.NONE
    );

    enum class GeneratedKeyRetrieval {
        BY_INDEX, BY_NAME, NONE
    }

    companion object {
        fun findDialect(dataSource: DataSource): SqlDialect {
            dataSource._connection.use { conn ->
                val metadata: DatabaseMetaData = conn._metaData
                val productName: String = metadata._databaseProductName.lowercase()
                val productVersion: String = metadata._databaseProductVersion.lowercase()
                val majorVersion: Int = metadata._databaseMajorVersion
                val minorVersion: Int = metadata._databaseMinorVersion
                return when {
                    productName.contains("oracle") -> if (majorVersion >= 12) ORACLE_NEW else ORACLE_OLD
                    productName.contains("sqlserver") || productName.contains("sql server") -> if (majorVersion >= 11) SQL_SERVER_NEW else SQL_SERVER_OLD
                    productName.contains("postgresql") -> POSTGRESQL
                    productName.contains("sqlite") -> SQLITE // SQLite does not support sequences natively
                    productName.contains("mysql") && productVersion.contains("mariadb") ->
                        if (majorVersion > 10 || (majorVersion == 10 && minorVersion >= 3)) MARIA_DB_NEW
                        else MARIA_DB_OLD

                    productName.contains("mysql") -> if (majorVersion >= 8) MYSQL_OLD else MYSQL_NEW
                    else -> UNKNOWN
                }
            }
        }
    }
}