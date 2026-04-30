package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.schemasync.model.SlotCategory
import onl.ycode.stormify.schemasync.model.TypeFamily
import java.sql.Types

/**
 * Maps a JDBC type code to a [SlotCategory], or null when the column should
 * skip slot classification (booleans, dates, blobs are deterministic and need
 * no slot picker).
 */
object JdbcCategoryMapper {
    fun categoryFor(jdbcType: Int): SlotCategory? = when (jdbcType) {
        Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
        Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
        Types.CLOB, Types.NCLOB,
        Types.SQLXML -> SlotCategory.TEXT

        Types.TINYINT, Types.SMALLINT,
        Types.INTEGER, Types.BIGINT -> SlotCategory.INTEGRAL

        Types.DECIMAL, Types.NUMERIC,
        Types.REAL, Types.FLOAT, Types.DOUBLE -> SlotCategory.DECIMAL

        else -> null
    }

    /** Broader bucket used for diff-time mismatch detection (covers booleans / dates / blobs). */
    fun familyFor(jdbcType: Int): TypeFamily? = when (jdbcType) {
        Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
        Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
        Types.CLOB, Types.NCLOB, Types.SQLXML -> TypeFamily.TEXT

        Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> TypeFamily.INTEGRAL

        Types.DECIMAL, Types.NUMERIC,
        Types.REAL, Types.FLOAT, Types.DOUBLE -> TypeFamily.DECIMAL

        Types.BOOLEAN, Types.BIT -> TypeFamily.BOOLEAN
        Types.DATE -> TypeFamily.DATE
        Types.TIME, Types.TIME_WITH_TIMEZONE -> TypeFamily.TIME
        Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> TypeFamily.TIMESTAMP
        Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> TypeFamily.BINARY
        else -> null
    }

    /**
     * Maps a JDBC type to a Kotlin type expression suitable for inserting into
     * an entity. Used by the writer's "INSERT into entity" path. Result includes
     * no `?` suffix; nullability is decided by the caller.
     */
    fun kotlinTypeFor(jdbcType: Int): String = when (jdbcType) {
        Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
        Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
        Types.CLOB, Types.NCLOB, Types.SQLXML -> "String"

        Types.TINYINT, Types.SMALLINT, Types.INTEGER -> "Int"
        Types.BIGINT -> "Long"

        Types.DECIMAL, Types.NUMERIC -> "BigDecimal"
        Types.REAL, Types.FLOAT -> "Float"
        Types.DOUBLE -> "Double"

        Types.BOOLEAN, Types.BIT -> "Boolean"
        Types.DATE -> "LocalDate"
        Types.TIME -> "LocalTime"
        Types.TIMESTAMP -> "LocalDateTime"
        Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME_WITH_TIMEZONE -> "Instant"
        Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "ByteArray"
        Types.OTHER -> "String"      // catch-all (e.g. Postgres UUID, Oracle ROWID)
        else -> "String"
    }
}
