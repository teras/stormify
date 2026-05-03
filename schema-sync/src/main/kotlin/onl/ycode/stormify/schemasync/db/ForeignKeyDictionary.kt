package onl.ycode.stormify.schemasync.db

import java.sql.Connection
import java.sql.ResultSet

/**
 * Bulk foreign-key extraction via each dialect's catalog views. Replaces the
 * per-table `DatabaseMetaData.getImportedKeys` loop with a single roundtrip,
 * which dominates introspection time on large schemas.
 *
 * All four implementations key the FK edge on the **last segment** of the
 * referenced table — schema is treated as opaque qualification, matching
 * how the diff layer collapses identity.
 */

internal fun oracleListForeignKeys(conn: Connection): List<FkEdge> {
    val sql = """
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
    return readEdges(conn, sql) { rs ->
        FkEdge(
            schema = null,
            table = rs.getString("TABLE_NAME").lowercase(),
            column = rs.getString("COLUMN_NAME").lowercase(),
            refTable = rs.getString("REF_TABLE").lowercase(),
            refColumn = rs.getString("REF_COLUMN").lowercase(),
        )
    }
}

internal fun postgresListForeignKeys(conn: Connection): List<FkEdge> {
    val sql = """
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
    val defaultSchema = conn.schema?.lowercase()
    return readEdges(conn, sql) { rs ->
        val rawSchema = rs.getString("table_schema")
        val schema = if (rawSchema != null && rawSchema.equals(defaultSchema, ignoreCase = true)) null else rawSchema
        FkEdge(
            schema = schema,
            table = rs.getString("table_name"),
            column = rs.getString("column_name"),
            refTable = rs.getString("ref_table"),
            refColumn = rs.getString("ref_column"),
        )
    }
}

internal fun mysqlListForeignKeys(conn: Connection): List<FkEdge> {
    val sql = """
        SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
        FROM information_schema.KEY_COLUMN_USAGE
        WHERE TABLE_SCHEMA = DATABASE()
          AND REFERENCED_TABLE_NAME IS NOT NULL
        ORDER BY TABLE_NAME, ORDINAL_POSITION
    """.trimIndent()
    return readEdges(conn, sql) { rs ->
        FkEdge(
            schema = null,
            table = rs.getString("TABLE_NAME"),
            column = rs.getString("COLUMN_NAME"),
            refTable = rs.getString("REFERENCED_TABLE_NAME"),
            refColumn = rs.getString("REFERENCED_COLUMN_NAME"),
        )
    }
}

internal fun mssqlListForeignKeys(conn: Connection): List<FkEdge> {
    val sql = """
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
    val defaultSchema = conn.schema?.lowercase() ?: "dbo"
    return readEdges(conn, sql) { rs ->
        val rawSchema = rs.getString("table_schema")
        val schema = if (rawSchema != null && rawSchema.equals(defaultSchema, ignoreCase = true)) null else rawSchema
        FkEdge(
            schema = schema,
            table = rs.getString("table_name"),
            column = rs.getString("column_name"),
            refTable = rs.getString("ref_table"),
            refColumn = rs.getString("ref_column"),
        )
    }
}

private inline fun readEdges(
    conn: Connection,
    sql: String,
    map: (ResultSet) -> FkEdge,
): List<FkEdge> {
    val out = mutableListOf<FkEdge>()
    runCatching {
        conn.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) out += map(rs)
            }
        }
    }.onFailure {
        // Bulk path failed (driver quirk, missing privileges) — caller will
        // fall back to per-table JDBC. Fail soft, never break introspection.
        System.err.println("Bulk FK lookup failed (${it.message}); falling back to per-table.")
    }
    return out
}
