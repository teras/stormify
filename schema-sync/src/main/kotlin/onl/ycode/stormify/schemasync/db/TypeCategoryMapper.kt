package onl.ycode.stormify.schemasync.db

import onl.ycode.stormify.schemasync.model.KotlinDefaults
import onl.ycode.stormify.schemasync.model.SlotCategory
import onl.ycode.stormify.schemasync.model.SqlTypeCode
import onl.ycode.stormify.schemasync.model.TypeFamily

/**
 * Maps a [SqlTypeCode] to the schema-sync classification primitives:
 * - [SlotCategory] for slot picker assignment (null = deterministic, no slot needed).
 * - [TypeFamily] for diff-time mismatch detection.
 * - [KotlinType] for entity code generation.
 *
 * Operates on [SqlTypeCode] rather than `java.sql.Types`, so dialect catalog
 * paths on Kotlin/Native (no JDBC) can feed this mapper directly.
 */
object TypeCategoryMapper {

    fun categoryFor(type: SqlTypeCode): SlotCategory? = when (type) {
        SqlTypeCode.CHAR, SqlTypeCode.VARCHAR, SqlTypeCode.LONGVARCHAR,
        SqlTypeCode.NCHAR, SqlTypeCode.NVARCHAR, SqlTypeCode.LONGNVARCHAR,
        SqlTypeCode.CLOB, SqlTypeCode.NCLOB,
        SqlTypeCode.SQLXML -> SlotCategory.TEXT

        SqlTypeCode.TINYINT, SqlTypeCode.SMALLINT,
        SqlTypeCode.INTEGER, SqlTypeCode.BIGINT -> SlotCategory.INTEGRAL

        SqlTypeCode.DECIMAL, SqlTypeCode.NUMERIC,
        SqlTypeCode.REAL, SqlTypeCode.FLOAT, SqlTypeCode.DOUBLE -> SlotCategory.DECIMAL

        else -> null
    }

    /** Broader bucket used for diff-time mismatch detection (covers booleans / dates / blobs).
     *  When [scale] is provided and zero, NUMERIC/DECIMAL collapses into INTEGRAL — Oracle's
     *  `NUMBER(p)` (no scale) is functionally an integer column even though it surfaces as
     *  [SqlTypeCode.NUMERIC], so it must not be flagged as a mismatch against `Int`/`Long`. */
    fun familyFor(type: SqlTypeCode, scale: Int? = null): TypeFamily? = when (type) {
        SqlTypeCode.CHAR, SqlTypeCode.VARCHAR, SqlTypeCode.LONGVARCHAR,
        SqlTypeCode.NCHAR, SqlTypeCode.NVARCHAR, SqlTypeCode.LONGNVARCHAR,
        SqlTypeCode.CLOB, SqlTypeCode.NCLOB, SqlTypeCode.SQLXML -> TypeFamily.TEXT

        SqlTypeCode.TINYINT, SqlTypeCode.SMALLINT, SqlTypeCode.INTEGER, SqlTypeCode.BIGINT -> TypeFamily.INTEGRAL

        SqlTypeCode.DECIMAL, SqlTypeCode.NUMERIC ->
            // Oracle reports NUMBER(p) with a null scale; treat that as
            // scale 0 (the Oracle default) rather than DECIMAL so it matches
            // INTEGRAL Kotlin types like Int/Long/Short.
            if (scale == null || scale == 0) TypeFamily.INTEGRAL else TypeFamily.DECIMAL
        SqlTypeCode.REAL, SqlTypeCode.FLOAT, SqlTypeCode.DOUBLE -> TypeFamily.DECIMAL

        SqlTypeCode.BOOLEAN, SqlTypeCode.BIT -> TypeFamily.BOOLEAN
        SqlTypeCode.DATE -> TypeFamily.DATE
        SqlTypeCode.TIME, SqlTypeCode.TIME_WITH_TIMEZONE -> TypeFamily.TIME
        SqlTypeCode.TIMESTAMP, SqlTypeCode.TIMESTAMP_WITH_TIMEZONE -> TypeFamily.TIMESTAMP
        SqlTypeCode.BINARY, SqlTypeCode.VARBINARY, SqlTypeCode.LONGVARBINARY, SqlTypeCode.BLOB -> TypeFamily.BINARY
        SqlTypeCode.OTHER -> null
    }

    /**
     * Maps a SQL type to a Kotlin type expression suitable for inserting into
     * an entity. Used by the writer's "INSERT into entity" path. Result includes
     * no `?` suffix; nullability is decided by the caller.
     */
    fun kotlinTypeFor(
        type: SqlTypeCode,
        primaryKey: Boolean = false,
        kotlinDefaults: KotlinDefaults = KotlinDefaults(),
    ): String = kotlinTypeChoice(type, primaryKey, kotlinDefaults).kotlin

    /** Like [kotlinTypeFor] but also returns the import the generator should add. */
    fun kotlinTypeChoice(
        type: SqlTypeCode,
        primaryKey: Boolean,
        kotlinDefaults: KotlinDefaults,
    ): KotlinType = when (type) {
        SqlTypeCode.CHAR, SqlTypeCode.VARCHAR, SqlTypeCode.LONGVARCHAR,
        SqlTypeCode.NCHAR, SqlTypeCode.NVARCHAR, SqlTypeCode.LONGNVARCHAR,
        SqlTypeCode.CLOB, SqlTypeCode.NCLOB, SqlTypeCode.SQLXML -> KotlinType("String")

        SqlTypeCode.TINYINT, SqlTypeCode.SMALLINT -> KotlinType("Int")
        SqlTypeCode.INTEGER ->
            if (primaryKey) KotlinType(kotlinDefaults.pkIntegerType.kotlin, kotlinDefaults.pkIntegerType.import)
            else KotlinType("Int")
        SqlTypeCode.BIGINT ->
            if (primaryKey) KotlinType(kotlinDefaults.pkIntegerType.kotlin, kotlinDefaults.pkIntegerType.import)
            else KotlinType("Long")

        SqlTypeCode.DECIMAL, SqlTypeCode.NUMERIC ->
            KotlinType(kotlinDefaults.decimalType.kotlin, kotlinDefaults.decimalType.import)
        SqlTypeCode.REAL, SqlTypeCode.FLOAT -> KotlinType("Float")
        SqlTypeCode.DOUBLE -> KotlinType("Double")

        SqlTypeCode.BOOLEAN, SqlTypeCode.BIT -> KotlinType("Boolean")
        SqlTypeCode.DATE -> KotlinType(kotlinDefaults.dateType.kotlin, kotlinDefaults.dateType.import)
        SqlTypeCode.TIME -> KotlinType(kotlinDefaults.timeType.kotlin, kotlinDefaults.timeType.import)
        SqlTypeCode.TIMESTAMP ->
            KotlinType(kotlinDefaults.timestampType.kotlin, kotlinDefaults.timestampType.import)
        SqlTypeCode.TIMESTAMP_WITH_TIMEZONE, SqlTypeCode.TIME_WITH_TIMEZONE ->
            KotlinType(kotlinDefaults.timestampType.kotlin, kotlinDefaults.timestampType.import)
        SqlTypeCode.BINARY, SqlTypeCode.VARBINARY, SqlTypeCode.LONGVARBINARY, SqlTypeCode.BLOB ->
            KotlinType("ByteArray")
        SqlTypeCode.OTHER -> KotlinType("String")
    }

    data class KotlinType(val kotlin: String, val import: String? = null)
}
