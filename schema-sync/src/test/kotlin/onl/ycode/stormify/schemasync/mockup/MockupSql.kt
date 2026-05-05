package onl.ycode.stormify.schemasync.mockup

import onl.ycode.stormify.schemasync.db.Dialect

/**
 * Per-dialect SQL rendering. Turns a [MockupSpec] into the DDL +
 * INSERT batches required to materialise it inside a target database.
 *
 * Synonyms: only Oracle and MSSQL get true `CREATE SYNONYM`; the
 * other dialects emit duplicate empty tables under the synonym name
 * so the slot/diff machinery still sees a distinct table.
 *
 * Booleans are rendered with the dialect's literal flavour
 * ([Dialect.booleanLiteral]), and date/timestamp defaults use the
 * dialect's "now" expression. INSERTs avoid bind parameters because
 * we hand-craft 1k rows per table and it is faster to inline literal
 * values than to round-trip a prepared statement N times.
 */
class MockupSql(private val dialect: Dialect) {

    fun renderColumnType(type: ColumnType): String = when (dialect) {
        Dialect.ORACLE -> oracleType(type)
        Dialect.POSTGRESQL -> postgresType(type)
        Dialect.MYSQL, Dialect.MARIADB -> mysqlType(type)
        Dialect.MSSQL -> mssqlType(type)
        Dialect.SQLITE, Dialect.GENERIC -> sqliteType(type)
    }

    fun renderDefault(d: ColumnDefault?): String? = when (d) {
        null -> null
        ColumnDefault.Zero -> "0"
        ColumnDefault.EmptyString -> "''"
        ColumnDefault.FalseBool -> dialect.booleanLiteral(false)
        ColumnDefault.NowTs -> nowExpression()
        is ColumnDefault.IntLit -> d.value.toString()
        is ColumnDefault.StrLit -> "'${d.value.replace("'", "''")}'"
    }

    private fun nowExpression(): String = when (dialect) {
        Dialect.ORACLE -> "SYSTIMESTAMP"
        Dialect.MSSQL -> "GETDATE()"
        Dialect.SQLITE, Dialect.GENERIC -> "CURRENT_TIMESTAMP"
        else -> "CURRENT_TIMESTAMP"
    }

    fun createTable(t: TableSpec): String {
        val cols = t.columns.joinToString(",\n  ") { c ->
            val nul = if (c.nullable) "" else " NOT NULL"
            val pk = if (c.type == ColumnType.PK_BIGINT) " PRIMARY KEY" else ""
            val def = renderDefault(c.default)?.let { " DEFAULT $it" } ?: ""
            // Oracle requires `DEFAULT <expr>` BEFORE `NOT NULL`; every other
            // dialect accepts both orderings. Emit DEFAULT-first universally.
            "${dialect.quoteIdentifier(c.name)} ${renderColumnType(c.type)}$def$nul$pk"
        }
        return "CREATE TABLE ${dialect.quoteIdentifier(t.name)} (\n  $cols\n)"
    }

    fun dropTable(name: String): String = when (dialect) {
        Dialect.ORACLE -> "BEGIN EXECUTE IMMEDIATE 'DROP TABLE ${dialect.quoteIdentifier(name)} CASCADE CONSTRAINTS'; EXCEPTION WHEN OTHERS THEN NULL; END;"
        else -> "DROP TABLE IF EXISTS ${dialect.quoteIdentifier(name)}"
    }

    fun dropView(name: String): String = when (dialect) {
        Dialect.ORACLE -> "BEGIN EXECUTE IMMEDIATE 'DROP VIEW ${dialect.quoteIdentifier(name)}'; EXCEPTION WHEN OTHERS THEN NULL; END;"
        else -> "DROP VIEW IF EXISTS ${dialect.quoteIdentifier(name)}"
    }

    fun createView(v: ViewSpec): String =
        "CREATE VIEW ${dialect.quoteIdentifier(v.name)} AS SELECT * FROM ${dialect.quoteIdentifier(v.baseTable)}"

    /** Synonym DDL when the dialect supports it, else null (caller emits a
     *  duplicate empty table instead). */
    fun createSynonym(s: SynonymSpec): String? = when (dialect) {
        Dialect.ORACLE -> "CREATE SYNONYM ${dialect.quoteIdentifier(s.name)} FOR ${dialect.quoteIdentifier(s.baseTable)}"
        Dialect.MSSQL -> "CREATE SYNONYM ${dialect.quoteIdentifier(s.name)} FOR ${dialect.quoteIdentifier(s.baseTable)}"
        else -> null
    }

    fun dropSynonym(name: String): String? = when (dialect) {
        Dialect.ORACLE -> "BEGIN EXECUTE IMMEDIATE 'DROP SYNONYM ${dialect.quoteIdentifier(name)}'; EXCEPTION WHEN OTHERS THEN NULL; END;"
        Dialect.MSSQL -> "DROP SYNONYM IF EXISTS ${dialect.quoteIdentifier(name)}"
        else -> null
    }

    /** Render a multi-row INSERT for [t] containing [rowCount] generated
     *  rows. Returned as separate strings sized to roughly 200 rows each
     *  to stay below per-statement limits across drivers. Oracle uses
     *  `INSERT ALL … SELECT 1 FROM dual` because it does not accept
     *  multi-row `VALUES`. */
    fun insertBatches(t: TableSpec, rowCount: Int, chunk: Int = 200): Sequence<String> = sequence {
        val tableName = dialect.quoteIdentifier(t.name)
        val cols = t.columns.joinToString(", ") { dialect.quoteIdentifier(it.name) }
        var emitted = 0
        while (emitted < rowCount) {
            val take = minOf(chunk, rowCount - emitted)
            val sb = StringBuilder()
            if (dialect == Dialect.ORACLE) {
                sb.append("INSERT ALL")
                for (i in 0 until take) {
                    sb.append("\n  INTO $tableName ($cols) VALUES ")
                    sb.append(renderRow(t, emitted + i))
                }
                sb.append("\nSELECT 1 FROM dual")
            } else {
                sb.append("INSERT INTO $tableName ($cols) VALUES ")
                for (i in 0 until take) {
                    if (i > 0) sb.append(", ")
                    sb.append(renderRow(t, emitted + i))
                }
            }
            yield(sb.toString())
            emitted += take
        }
    }

    private fun renderRow(t: TableSpec, rowIdx: Int): String {
        val sb = StringBuilder("(")
        for ((i, c) in t.columns.withIndex()) {
            if (i > 0) sb.append(", ")
            sb.append(renderValue(c, rowIdx))
        }
        sb.append(")")
        return sb.toString()
    }

    private fun renderValue(c: ColumnSpec, rowIdx: Int): String {
        // A null is fine for a third of nullable non-PK rows; keeps query
        // workloads below honest-but-noisy.
        if (c.nullable && c.type != ColumnType.PK_BIGINT && rowIdx % 3 == 0) return "NULL"
        return when (c.type) {
            ColumnType.PK_BIGINT -> (rowIdx + 1).toString()
            ColumnType.VARCHAR_50 -> "'v50_$rowIdx'"
            ColumnType.VARCHAR_255 -> "'v255_${rowIdx}_${c.name.take(8)}'"
            ColumnType.TEXT -> "'text_$rowIdx'"
            ColumnType.SMALLINT -> (rowIdx % 200).toString()
            ColumnType.INT -> (rowIdx * 7 % 100_000).toString()
            ColumnType.BIGINT -> (rowIdx.toLong() * 13).toString()
            ColumnType.DECIMAL_10_2 -> "${rowIdx}.50"
            ColumnType.DECIMAL_18_4 -> "${rowIdx * 11}.0001"
            ColumnType.DOUBLE_ -> "${rowIdx}.125"
            ColumnType.BOOLEAN_ -> dialect.booleanLiteral(rowIdx % 2 == 0)
            ColumnType.DATE_ -> dateLiteral(rowIdx)
            ColumnType.TIMESTAMP_ -> timestampLiteral(rowIdx)
            ColumnType.BLOB_ -> blobLiteral(rowIdx)
        }
    }

    private fun dateLiteral(rowIdx: Int): String {
        val day = 1 + (rowIdx % 27)
        val s = "2025-01-${day.toString().padStart(2, '0')}"
        return when (dialect) {
            Dialect.ORACLE -> "TO_DATE('$s', 'YYYY-MM-DD')"
            // SQLite has no DATE literal type; the standard ANSI form is rejected.
            Dialect.SQLITE, Dialect.MSSQL -> "'$s'"
            else -> "DATE '$s'"
        }
    }

    private fun timestampLiteral(rowIdx: Int): String {
        val day = 1 + (rowIdx % 27)
        val s = "2025-01-${day.toString().padStart(2, '0')} 12:00:00"
        return when (dialect) {
            Dialect.ORACLE -> "TO_TIMESTAMP('$s', 'YYYY-MM-DD HH24:MI:SS')"
            // SQLite/MSSQL accept plain string literals; ANSI `TIMESTAMP '…'` fails on both.
            Dialect.SQLITE, Dialect.MSSQL -> "'$s'"
            else -> "TIMESTAMP '$s'"
        }
    }

    private fun blobLiteral(rowIdx: Int): String {
        val hex = "DEADBEEF${(rowIdx % 256).toString(16).padStart(2, '0').uppercase()}"
        return when (dialect) {
            Dialect.ORACLE -> "HEXTORAW('$hex')"
            Dialect.POSTGRESQL -> "'\\x$hex'::bytea"
            Dialect.MSSQL -> "0x$hex"
            Dialect.MYSQL, Dialect.MARIADB -> "X'$hex'"
            else -> "X'$hex'"
        }
    }

    // ---- per-dialect type tables ---------------------------------------

    private fun oracleType(t: ColumnType): String = when (t) {
        ColumnType.PK_BIGINT -> "NUMBER(19)"
        ColumnType.VARCHAR_50 -> "VARCHAR2(50)"
        ColumnType.VARCHAR_255 -> "VARCHAR2(255)"
        ColumnType.TEXT -> "CLOB"
        ColumnType.SMALLINT -> "NUMBER(5)"
        ColumnType.INT -> "NUMBER(10)"
        ColumnType.BIGINT -> "NUMBER(19)"
        ColumnType.DECIMAL_10_2 -> "NUMBER(10,2)"
        ColumnType.DECIMAL_18_4 -> "NUMBER(18,4)"
        ColumnType.DOUBLE_ -> "BINARY_DOUBLE"
        ColumnType.BOOLEAN_ -> "NUMBER(1)"
        ColumnType.DATE_ -> "DATE"
        ColumnType.TIMESTAMP_ -> "TIMESTAMP"
        ColumnType.BLOB_ -> "BLOB"
    }

    private fun postgresType(t: ColumnType): String = when (t) {
        ColumnType.PK_BIGINT -> "BIGINT"
        ColumnType.VARCHAR_50 -> "VARCHAR(50)"
        ColumnType.VARCHAR_255 -> "VARCHAR(255)"
        ColumnType.TEXT -> "TEXT"
        ColumnType.SMALLINT -> "SMALLINT"
        ColumnType.INT -> "INTEGER"
        ColumnType.BIGINT -> "BIGINT"
        ColumnType.DECIMAL_10_2 -> "NUMERIC(10,2)"
        ColumnType.DECIMAL_18_4 -> "NUMERIC(18,4)"
        ColumnType.DOUBLE_ -> "DOUBLE PRECISION"
        ColumnType.BOOLEAN_ -> "BOOLEAN"
        ColumnType.DATE_ -> "DATE"
        ColumnType.TIMESTAMP_ -> "TIMESTAMP"
        ColumnType.BLOB_ -> "BYTEA"
    }

    private fun mysqlType(t: ColumnType): String = when (t) {
        ColumnType.PK_BIGINT -> "BIGINT"
        ColumnType.VARCHAR_50 -> "VARCHAR(50)"
        ColumnType.VARCHAR_255 -> "VARCHAR(255)"
        ColumnType.TEXT -> "TEXT"
        ColumnType.SMALLINT -> "SMALLINT"
        ColumnType.INT -> "INT"
        ColumnType.BIGINT -> "BIGINT"
        ColumnType.DECIMAL_10_2 -> "DECIMAL(10,2)"
        ColumnType.DECIMAL_18_4 -> "DECIMAL(18,4)"
        ColumnType.DOUBLE_ -> "DOUBLE"
        ColumnType.BOOLEAN_ -> "TINYINT(1)"
        ColumnType.DATE_ -> "DATE"
        ColumnType.TIMESTAMP_ -> "DATETIME"
        ColumnType.BLOB_ -> "BLOB"
    }

    private fun mssqlType(t: ColumnType): String = when (t) {
        ColumnType.PK_BIGINT -> "BIGINT"
        ColumnType.VARCHAR_50 -> "VARCHAR(50)"
        ColumnType.VARCHAR_255 -> "VARCHAR(255)"
        ColumnType.TEXT -> "VARCHAR(MAX)"
        ColumnType.SMALLINT -> "SMALLINT"
        ColumnType.INT -> "INT"
        ColumnType.BIGINT -> "BIGINT"
        ColumnType.DECIMAL_10_2 -> "DECIMAL(10,2)"
        ColumnType.DECIMAL_18_4 -> "DECIMAL(18,4)"
        ColumnType.DOUBLE_ -> "FLOAT"
        ColumnType.BOOLEAN_ -> "BIT"
        ColumnType.DATE_ -> "DATE"
        ColumnType.TIMESTAMP_ -> "DATETIME2"
        ColumnType.BLOB_ -> "VARBINARY(MAX)"
    }

    private fun sqliteType(t: ColumnType): String = when (t) {
        ColumnType.PK_BIGINT -> "INTEGER"
        ColumnType.VARCHAR_50 -> "VARCHAR(50)"
        ColumnType.VARCHAR_255 -> "VARCHAR(255)"
        ColumnType.TEXT -> "TEXT"
        ColumnType.SMALLINT -> "SMALLINT"
        ColumnType.INT -> "INTEGER"
        ColumnType.BIGINT -> "BIGINT"
        ColumnType.DECIMAL_10_2 -> "NUMERIC(10,2)"
        ColumnType.DECIMAL_18_4 -> "NUMERIC(18,4)"
        ColumnType.DOUBLE_ -> "DOUBLE"
        ColumnType.BOOLEAN_ -> "BOOLEAN"
        ColumnType.DATE_ -> "DATE"
        ColumnType.TIMESTAMP_ -> "DATETIME"
        ColumnType.BLOB_ -> "BLOB"
    }
}
