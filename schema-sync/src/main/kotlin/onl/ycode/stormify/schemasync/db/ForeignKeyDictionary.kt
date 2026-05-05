package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.Stormify

/**
 * Bulk foreign-key extraction via each dialect's catalog views. Replaces the
 * per-table `DatabaseMetaData.getImportedKeys` loop with a single roundtrip,
 * which dominates introspection time on large schemas.
 *
 * All four implementations key the FK edge on the **last segment** of the
 * referenced table — schema is treated as opaque qualification, matching
 * how the diff layer collapses identity.
 */

internal fun oracleListForeignKeys(stormify: Stormify): List<FkEdge> = readEdges(stormify) {
    """
    SELECT c.TABLE_NAME, cc.COLUMN_NAME,
           rc.TABLE_NAME AS REF_TABLE, rcc.COLUMN_NAME AS REF_COLUMN
    FROM USER_CONSTRAINTS c
    JOIN USER_CONS_COLUMNS cc
      ON cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME
     AND cc.OWNER = c.OWNER
    JOIN USER_CONSTRAINTS rc
      ON rc.CONSTRAINT_NAME = c.R_CONSTRAINT_NAME
     AND rc.OWNER = c.R_OWNER
    JOIN USER_CONS_COLUMNS rcc
      ON rcc.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
     AND rcc.OWNER = rc.OWNER
     AND rcc.POSITION = cc.POSITION
    WHERE c.CONSTRAINT_TYPE = 'R'
    ORDER BY c.TABLE_NAME, cc.POSITION
    """.trimIndent()
}.map { row ->
    FkEdge(
        schema = null,
        table = row.str("table_name")!!.lowercase(),
        column = row.str("column_name")!!.lowercase(),
        refTable = row.str("ref_table")!!.lowercase(),
        refColumn = row.str("ref_column")!!.lowercase(),
    )
}

internal fun postgresListForeignKeys(stormify: Stormify): List<FkEdge> {
    val defaultSchema = Dialect.POSTGRESQL.queryDefaultSchema(stormify)?.lowercase()
    return readEdges(stormify) {
        """
        SELECT tc.table_schema, tc.table_name, kcu.column_name,
               ccu.table_name AS ref_table, ccu.column_name AS ref_column
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name = kcu.constraint_name
         AND tc.table_schema = kcu.table_schema
        JOIN information_schema.constraint_column_usage ccu
          ON ccu.constraint_name = tc.constraint_name
         AND ccu.table_schema = tc.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY'
          AND tc.table_schema NOT IN ('pg_catalog', 'information_schema')
        ORDER BY tc.table_name, kcu.ordinal_position
        """.trimIndent()
    }.map { row ->
        val rawSchema = row.str("table_schema")
        val schema = if (rawSchema != null && rawSchema.equals(defaultSchema, ignoreCase = true)) null else rawSchema
        FkEdge(
            schema = schema,
            table = row.str("table_name")!!,
            column = row.str("column_name")!!,
            refTable = row.str("ref_table")!!,
            refColumn = row.str("ref_column")!!,
        )
    }
}

internal fun mysqlListForeignKeys(stormify: Stormify): List<FkEdge> = readEdges(stormify) {
    """
    SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
    FROM information_schema.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA = DATABASE()
      AND REFERENCED_TABLE_NAME IS NOT NULL
    ORDER BY TABLE_NAME, ORDINAL_POSITION
    """.trimIndent()
}.map { row ->
    FkEdge(
        schema = null,
        table = row.str("table_name")!!,
        column = row.str("column_name")!!,
        refTable = row.str("referenced_table_name")!!,
        refColumn = row.str("referenced_column_name")!!,
    )
}

internal fun mssqlListForeignKeys(stormify: Stormify): List<FkEdge> {
    val defaultSchema = Dialect.MSSQL.queryDefaultSchema(stormify)?.lowercase() ?: "dbo"
    return readEdges(stormify) {
        """
        SELECT
          OBJECT_SCHEMA_NAME(fk.parent_object_id) AS table_schema,
          OBJECT_NAME(fk.parent_object_id) AS table_name,
          cp.name AS column_name,
          OBJECT_NAME(fk.referenced_object_id) AS ref_table,
          cr.name AS ref_column
        FROM sys.foreign_keys fk
        JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
        JOIN sys.columns cp
          ON cp.object_id = fk.parent_object_id AND cp.column_id = fkc.parent_column_id
        JOIN sys.columns cr
          ON cr.object_id = fk.referenced_object_id AND cr.column_id = fkc.referenced_column_id
        ORDER BY table_name, fkc.constraint_column_id
        """.trimIndent()
    }.map { row ->
        val rawSchema = row.str("table_schema")
        val schema = if (rawSchema != null && rawSchema.equals(defaultSchema, ignoreCase = true)) null else rawSchema
        FkEdge(
            schema = schema,
            table = row.str("table_name")!!,
            column = row.str("column_name")!!,
            refTable = row.str("ref_table")!!,
            refColumn = row.str("ref_column")!!,
        )
    }
}

private inline fun readEdges(stormify: Stormify, sql: () -> String): List<Row> =
    runCatching { stormify.read<Row>(sql()) }
        .onFailure {
            // Bulk path failed (driver quirk, missing privileges) — caller falls
            // back to per-table JDBC. Fail soft, never break introspection.
            System.err.println("Bulk FK lookup failed (${it.message}); falling back to per-table.")
        }.getOrDefault(emptyList())
