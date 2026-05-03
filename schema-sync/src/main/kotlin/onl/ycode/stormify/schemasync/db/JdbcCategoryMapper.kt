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

    /** Broader bucket used for diff-time mismatch detection (covers booleans / dates / blobs).
     *  When [scale] is provided and zero, NUMERIC/DECIMAL collapses into INTEGRAL — Oracle's
     *  `NUMBER(p)` (no scale) is functionally an integer column even though JDBC reports
     *  it as `Types.NUMERIC`, so it must not be flagged as a mismatch against `Int`/`Long`. */
    fun familyFor(jdbcType: Int, scale: Int? = null): TypeFamily? = when (jdbcType) {
        Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
        Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
        Types.CLOB, Types.NCLOB, Types.SQLXML -> TypeFamily.TEXT

        Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> TypeFamily.INTEGRAL

        Types.DECIMAL, Types.NUMERIC ->
            // Oracle reports NUMBER(p) with a null scale; treat that as
            // scale 0 (the Oracle default) rather than DECIMAL so it matches
            // INTEGRAL Kotlin types like Int/Long/Short.
            if (scale == null || scale == 0) TypeFamily.INTEGRAL else TypeFamily.DECIMAL
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
    fun kotlinTypeFor(
        jdbcType: Int,
        primaryKey: Boolean = false,
        kotlinDefaults: onl.ycode.stormify.schemasync.model.KotlinDefaults = onl.ycode.stormify.schemasync.model.KotlinDefaults(),
    ): String = kotlinTypeChoice(jdbcType, primaryKey, kotlinDefaults).kotlin

    /** Like [kotlinTypeFor] but also returns the import the generator should add. */
    fun kotlinTypeChoice(
        jdbcType: Int,
        primaryKey: Boolean,
        kotlinDefaults: onl.ycode.stormify.schemasync.model.KotlinDefaults,
    ): KotlinType = when (jdbcType) {
        Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
        Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
        Types.CLOB, Types.NCLOB, Types.SQLXML -> KotlinType("String")

        Types.TINYINT, Types.SMALLINT -> KotlinType("Int")
        Types.INTEGER ->
            if (primaryKey) KotlinType(kotlinDefaults.pkIntegerType.kotlin, kotlinDefaults.pkIntegerType.import)
            else KotlinType("Int")
        Types.BIGINT ->
            if (primaryKey) KotlinType(kotlinDefaults.pkIntegerType.kotlin, kotlinDefaults.pkIntegerType.import)
            else KotlinType("Long")

        Types.DECIMAL, Types.NUMERIC -> KotlinType(kotlinDefaults.decimalType.kotlin, kotlinDefaults.decimalType.import)
        Types.REAL, Types.FLOAT -> KotlinType("Float")
        Types.DOUBLE -> KotlinType("Double")

        Types.BOOLEAN, Types.BIT -> KotlinType("Boolean")
        Types.DATE -> KotlinType(kotlinDefaults.dateType.kotlin, kotlinDefaults.dateType.import)
        Types.TIME -> KotlinType(kotlinDefaults.timeType.kotlin, kotlinDefaults.timeType.import)
        Types.TIMESTAMP -> KotlinType(kotlinDefaults.timestampType.kotlin, kotlinDefaults.timestampType.import)
        Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME_WITH_TIMEZONE ->
            KotlinType(kotlinDefaults.timestampType.kotlin, kotlinDefaults.timestampType.import)
        Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> KotlinType("ByteArray")
        Types.OTHER -> KotlinType("String")
        else -> KotlinType("String")
    }

    data class KotlinType(val kotlin: String, val import: String? = null)
}
