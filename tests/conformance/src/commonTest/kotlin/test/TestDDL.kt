// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify

object TestDDL {
    private lateinit var stormify: Stormify
    var dbMajorVersion: Int = 0
        private set

    fun init(s: Stormify) {
        stormify = s
        dbMajorVersion = try {
            s.dataSource.getConnection().use { it.metaData.databaseMajorVersion }
        } catch (_: Throwable) { 0 }
    }

    val dialect get() = stormify.sqlDialect
    private val isOracle get() = dialect == SqlDialect.ORACLE_NEW || dialect == SqlDialect.ORACLE_OLD
    private val isMssql get() = dialect == SqlDialect.SQL_SERVER_NEW || dialect == SqlDialect.SQL_SERVER_OLD
    private val isSqlite get() = dialect == SqlDialect.SQLITE
    val isMysqlFamily get() = dialect == SqlDialect.MYSQL_OLD || dialect == SqlDialect.MYSQL_NEW ||
            dialect == SqlDialect.MARIA_DB_OLD || dialect == SqlDialect.MARIA_DB_NEW
    val isPostgres get() = dialect == SqlDialect.POSTGRESQL

    /**
     * Whether the current database's default character set can represent the
     * full Unicode repertoire. All of our test targets are set up with
     * Unicode-capable defaults (utf8mb4, UTF8, AL32UTF8, NVARCHAR UCS-2,
     * SQLite UTF-8) EXCEPT the `oracle11` target, which pins its
     * `NLS_CHARACTERSET` to `EL8ISO8859P7` (single-byte Greek). Tests that
     * insert characters outside a legacy single-byte codepage (CJK, emoji,
     * non-Greek Latin supplement, ...) must either skip those buckets or
     * substitute a Greek-compatible alternative when this returns false.
     *
     * For Oracle we probe `NLS_DATABASE_PARAMETERS` at runtime and accept
     * any charset whose name starts with `AL` (AL32UTF8, AL16UTF16) as
     * Unicode-capable. For every other dialect the answer is compile-time
     * true because we don't ship any non-Unicode targets for them.
     */
    val isUnicodeDatabase: Boolean by lazy {
        if (isMysqlFamily) return@lazy try {
            val cs = stormify.readOne<String>("SELECT @@character_set_server")
            cs != null && (cs.startsWith("utf") || cs.startsWith("ucs"))
        } catch (_: Throwable) { true }
        if (isPostgres) return@lazy try {
            val enc = stormify.readOne<String>("SHOW server_encoding")?.uppercase()
            enc != null && enc.startsWith("UTF")
        } catch (_: Throwable) { true }
        if (!isOracle) return@lazy true
        try {
            val charset = stormify.readOne<String>(
                "SELECT value FROM nls_database_parameters WHERE parameter = 'NLS_CHARACTERSET'"
            )
            charset != null && charset.startsWith("AL")
        } catch (_: Throwable) {
            // If the probe fails for any reason, assume Unicode so existing
            // Oracle targets (21c AL32UTF8) stay on their current test path.
            true
        }
    }

    /**
     * Identifier for a server-side text encoding bucket used by
     * [columnTypeFor]. The test layer passes one of these when it wants a
     * VARCHAR/TEXT column that physically stores data in the named encoding
     * (where the dialect supports it — see [supportsPerColumnEncoding]).
     */
    enum class TextEncoding { ISO_8859_1, ISO_8859_7, UTF_8, UTF_16 }

    /**
     * Whether the current dialect lets us pick a different server-side text
     * encoding for each column. Only MySQL/MariaDB (CHARACTER SET) and MSSQL
     * (COLLATE with codepage-backed VARCHAR + NVARCHAR for UTF-16) qualify.
     *
     * On Postgres the encoding is database-wide, on Oracle it is fixed at DB
     * creation (DB charset + national charset), and SQLite is always UTF-8.
     * The test harness still runs on these dialects — it falls back to the
     * DB-wide encoding and verifies that the characters in each bucket
     * survive the pipeline (which is a weaker but still useful check).
     */
    fun supportsPerColumnEncoding(): Boolean = isMysqlFamily || isMssql

    /**
     * Return a column type DDL fragment (without column name) that stores
     * text in [enc] on the current dialect. Caller prepends the column name:
     *
     *   "col_name ${TestDDL.columnTypeFor(TextEncoding.ISO_8859_1)}"
     *
     * On dialects without per-column encoding, all buckets map to the same
     * DB-wide Unicode column type (VARCHAR2 / TEXT / ...). This keeps the
     * test DDL portable.
     */
    fun columnTypeFor(enc: TextEncoding): String = when {
        isMysqlFamily -> when (enc) {
            // MySQL "latin1" is Windows-1252 (ISO-8859-1 + C1 characters) —
            // a strict superset of ISO-8859-1 for bytes 0xA0..0xFF.
            TextEncoding.ISO_8859_1 -> "VARCHAR(200) CHARACTER SET latin1"
            // MySQL "greek" is the ISO-8859-7 single-byte Greek charset.
            TextEncoding.ISO_8859_7 -> "VARCHAR(200) CHARACTER SET greek"
            // utf8mb4 is MySQL's full-Unicode UTF-8 (up to 4 bytes/char).
            TextEncoding.UTF_8      -> "VARCHAR(200) CHARACTER SET utf8mb4"
            // "utf16" in MySQL stores UTF-16 with supplementary characters
            // (per docs, unlike "ucs2" which is BMP-only).
            TextEncoding.UTF_16     -> "VARCHAR(200) CHARACTER SET utf16"
        }
        isMssql -> when (enc) {
            // CP1252 is a superset of ISO-8859-1.
            TextEncoding.ISO_8859_1 -> "VARCHAR(200) COLLATE SQL_Latin1_General_CP1_CI_AS"
            // CP1253 is Windows' Greek codepage; ISO-8859-7 letters map 1:1.
            TextEncoding.ISO_8859_7 -> "VARCHAR(200) COLLATE Greek_CI_AS"
            // SQL Server 2019+ introduced UTF-8 collations for VARCHAR.
            TextEncoding.UTF_8      -> "VARCHAR(200) COLLATE Latin1_General_100_CI_AS_SC_UTF8"
            // NVARCHAR is UTF-16; the _SC suffix enables supplementary
            // character support so surrogate pairs round-trip correctly.
            TextEncoding.UTF_16     -> "NVARCHAR(200) COLLATE Latin1_General_100_CI_AS_SC"
        }
        // Dialects without per-column encoding: fall back to the DB-wide
        // Unicode column. Every bucket's characters are still representable
        // because the DB charset is UTF-8 (PG/SQLite/Oracle AL32UTF8).
        else -> textType()
    }

    fun textType() = when {
        isOracle -> "VARCHAR2(4000)"
        isMssql -> "NVARCHAR(MAX)"
        else -> "TEXT"
    }

    fun blobType() = when (dialect) {
        SqlDialect.POSTGRESQL -> "BYTEA"
        SqlDialect.SQL_SERVER_NEW, SqlDialect.SQL_SERVER_OLD -> "VARBINARY(MAX)"
        // MySQL/MariaDB BLOB caps at 64KB; use LONGBLOB to hold multi-MB values.
        SqlDialect.MYSQL_OLD, SqlDialect.MYSQL_NEW,
        SqlDialect.MARIA_DB_OLD, SqlDialect.MARIA_DB_NEW -> "LONGBLOB"
        else -> "BLOB"
    }

    // --- Portable numeric type aliases ---
    // Some dialects lack SMALLINT/INT/BIGINT/REAL/DOUBLE PRECISION as native types
    // (notably Oracle, which maps everything through NUMBER / BINARY_*).

    fun booleanType() = when {
        isOracle -> "NUMBER(1)"
        isSqlite -> "INTEGER"
        isMssql -> "BIT"
        else -> "BOOLEAN"
    }
    fun smallIntType() = when {
        isOracle -> "NUMBER(5)"
        else -> "SMALLINT"
    }
    fun intType() = when {
        isOracle -> "NUMBER(10)"
        else -> "INT"
    }
    fun bigIntType() = if (isOracle) "NUMBER(19)" else "BIGINT"

    /** Portable fixed-precision decimal type (precision = total digits, scale = digits after decimal).
     *
     * SQLite has no real DECIMAL — NUMERIC column affinity coerces text values
     * to INTEGER/REAL if they "look like" numbers, which loses precision for
     * anything beyond double. TEXT affinity is the only way to preserve full
     * BigDecimal/BigInteger precision on SQLite. */
    fun decimalType(precision: Int, scale: Int) = when {
        isOracle -> "NUMBER($precision, $scale)"
        isSqlite -> "TEXT"
        else -> "DECIMAL($precision, $scale)"
    }
    fun floatType() = when {
        isOracle -> "BINARY_FLOAT"
        isMssql -> "REAL"
        else -> "REAL"
    }
    fun doubleType() = when {
        isOracle -> "BINARY_DOUBLE"
        isMssql -> "FLOAT(53)" // SQL Server's 64-bit float
        else -> "DOUBLE PRECISION"
    }

    /**
     * Large text column type for values that exceed VARCHAR2(4000) on Oracle. Uses CLOB
     * on Oracle/DB2-style databases and plain TEXT/NVARCHAR(MAX) everywhere else.
     */
    fun largeTextType() = when {
        isOracle -> "CLOB"
        isMssql -> "NVARCHAR(MAX)"
        else -> "TEXT"
    }

    fun timestampType() = if (isMssql) "DATETIME2" else "TIMESTAMP"

    fun intPrimaryKey(col: String) = if (isOracle) "$col NUMBER(10) PRIMARY KEY" else "$col INT PRIMARY KEY"

    /** Bounded-VARCHAR column type suitable as a primary key (so MySQL/MariaDB don't
     *  reject a TEXT-affinity PK). Large enough to hold a 36-char UUID plus padding. */
    fun stringPkType(size: Int = 64) = if (isOracle) "VARCHAR2($size)" else "VARCHAR($size)"

    fun intNotNull(col: String) = if (isOracle) "$col NUMBER(10) NOT NULL" else "$col INT NOT NULL"

    fun intColumn(col: String) = if (isOracle) "$col NUMBER(10)" else "$col INT"

    fun autoIncrementPrimaryKey(col: String): String? = when (dialect) {
        SqlDialect.MYSQL_OLD, SqlDialect.MYSQL_NEW,
        SqlDialect.MARIA_DB_OLD, SqlDialect.MARIA_DB_NEW -> "$col INT PRIMARY KEY AUTO_INCREMENT"
        SqlDialect.POSTGRESQL -> "$col SERIAL PRIMARY KEY"
        SqlDialect.ORACLE_NEW -> "$col NUMBER(10) GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY"
        SqlDialect.SQL_SERVER_NEW, SqlDialect.SQL_SERVER_OLD -> "$col INT IDENTITY(1,1) PRIMARY KEY"
        SqlDialect.SQLITE -> "$col INTEGER PRIMARY KEY AUTOINCREMENT"
        else -> null
    }

    fun supportsAutoIncrement() = autoIncrementPrimaryKey("x") != null
    fun supportsHighConcurrency() = !isSqlite

    /** True when the current dialect supports user-defined stored procedures with OUT/INOUT params.
     *  PostgreSQL gained CREATE PROCEDURE / CALL in version 11. */
    fun supportsStoredProcedures() = !isSqlite && !(isPostgres && dbMajorVersion < 11)

    /**
     * DDL for a test stored procedure named [name] with signature
     * `(IN x INT, IN y VARCHAR, OUT z INT, INOUT w INT)` that sets
     * `z := x * 2` and `w := w + x`. Callers are responsible for invoking
     * [dropProcedure] first (most dialects error on existing definitions).
     */
    fun createTestProcedure(name: String): String = when {
        dialect == SqlDialect.POSTGRESQL ->
            // PG 14+ supports OUT/INOUT in CREATE PROCEDURE.
            """
            CREATE PROCEDURE $name(IN x INT, IN y VARCHAR, OUT z INT, INOUT w INT)
            LANGUAGE plpgsql AS $$
            BEGIN
                z := x * 2;
                w := w + x;
            END;
            $$
            """.trimIndent()
        dialect == SqlDialect.MYSQL_OLD || dialect == SqlDialect.MYSQL_NEW ||
                dialect == SqlDialect.MARIA_DB_OLD || dialect == SqlDialect.MARIA_DB_NEW ->
            """
            CREATE PROCEDURE $name(IN x INT, IN y VARCHAR(50), OUT z INT, INOUT w INT)
            BEGIN
                SET z = x * 2;
                SET w = w + x;
            END
            """.trimIndent()
        isOracle ->
            // Oracle uses PL/SQL and prefers `CREATE OR REPLACE` — but we still
            // call dropProcedure first for a clean slate in case the definition
            // changes between test runs.
            """
            CREATE OR REPLACE PROCEDURE $name(
                x IN NUMBER,
                y IN VARCHAR2,
                z OUT NUMBER,
                w IN OUT NUMBER
            ) AS
            BEGIN
                z := x * 2;
                w := w + x;
            END;
            """.trimIndent()
        isMssql ->
            // SQL Server has no IN/OUT/INOUT distinction at declaration — OUTPUT
            // covers both OUT and INOUT. We rely on the caller passing a value
            // for INOUT and NULL (or uninitialized) for pure OUT.
            """
            CREATE PROCEDURE $name
                @x INT,
                @y VARCHAR(50),
                @z INT OUTPUT,
                @w INT OUTPUT
            AS
            BEGIN
                SET @z = @x * 2;
                SET @w = @w + @x;
            END
            """.trimIndent()
        else -> error("Stored procedures not supported for dialect $dialect")
    }

    /** Drops the test procedure if it exists. Safe to call even if absent. */
    fun dropProcedure(name: String) {
        when {
            isOracle -> try {
                stormify.executeUpdate("DROP PROCEDURE $name")
            } catch (_: Exception) { /* PLS-00201: does not exist — ignore */ }
            else -> try {
                stormify.executeUpdate("DROP PROCEDURE IF EXISTS $name")
            } catch (_: Exception) { /* belt-and-braces */ }
        }
    }

    fun foreignKey(column: String, refTable: String, refColumn: String) =
        "FOREIGN KEY($column) REFERENCES $refTable($refColumn)"

    fun createTable(name: String, columns: String) = "CREATE TABLE $name ($columns)"

    fun selectExpr(expr: String) = if (isOracle) "SELECT $expr FROM dual" else "SELECT $expr"

    fun dropTable(name: String) {
        when {
            isOracle -> try {
                // PURGE skips the recycle bin — without it the dropped table can
                // linger as a BIN$… object and a subsequent CREATE TABLE with
                // the same name occasionally races into ORA-00955 on 11g.
                stormify.executeUpdate("DROP TABLE $name CASCADE CONSTRAINTS PURGE")
            } catch (_: Exception) {
                // ORA-00942: table or view does not exist — safe to ignore on Oracle
            }
            dialect == SqlDialect.POSTGRESQL ->
                stormify.executeUpdate("DROP TABLE IF EXISTS $name CASCADE")
            dialect == SqlDialect.MYSQL_OLD || dialect == SqlDialect.MYSQL_NEW ||
                    dialect == SqlDialect.MARIA_DB_OLD || dialect == SqlDialect.MARIA_DB_NEW -> {
                // MySQL/MariaDB have no `DROP TABLE ... CASCADE` syntax; bypass FK checks
                // for the duration of a single transaction so SET and DROP share a connection.
                stormify.transaction {
                    stormify.executeUpdate("SET FOREIGN_KEY_CHECKS = 0")
                    stormify.executeUpdate("DROP TABLE IF EXISTS $name")
                    stormify.executeUpdate("SET FOREIGN_KEY_CHECKS = 1")
                }
            }
            isMssql -> {
                // SQL Server has no `DROP TABLE ... CASCADE`; drop referencing FK
                // constraints first via sys.foreign_keys, then drop the table.
                try {
                    val dropFks = """
                        DECLARE @sql NVARCHAR(MAX) = N'';
                        SELECT @sql = @sql + N'ALTER TABLE ' + QUOTENAME(OBJECT_SCHEMA_NAME(fk.parent_object_id))
                            + N'.' + QUOTENAME(OBJECT_NAME(fk.parent_object_id))
                            + N' DROP CONSTRAINT ' + QUOTENAME(fk.name) + N';'
                        FROM sys.foreign_keys fk
                        WHERE fk.referenced_object_id = OBJECT_ID('$name');
                        IF LEN(@sql) > 0 EXEC sp_executesql @sql;
                    """.trimIndent()
                    stormify.executeUpdate(dropFks)
                } catch (_: Exception) { /* best-effort */ }
                stormify.executeUpdate(
                    "IF OBJECT_ID('$name', 'U') IS NOT NULL DROP TABLE $name"
                )
            }
            else -> stormify.executeUpdate("DROP TABLE IF EXISTS $name")
        }
    }
}
