package onl.ycode.stormify.schemasync.model

/**
 * Platform-independent SQL type code. Mirrors the subset of
 * `java.sql.Types` that schema-sync actually classifies on, but lives in
 * the `model` package so non-JVM dialect paths (catalog queries on
 * Kotlin/Native) can produce these values without depending on `java.sql`.
 *
 * The JVM JDBC introspection path converts `java.sql.Types` integers to
 * this enum once, at row-read time; every consumer (category/family
 * mapper, entity generator, diff engine) takes [SqlTypeCode] as input.
 */
enum class SqlTypeCode {
    CHAR, VARCHAR, LONGVARCHAR,
    NCHAR, NVARCHAR, LONGNVARCHAR,
    CLOB, NCLOB, SQLXML,
    TINYINT, SMALLINT, INTEGER, BIGINT,
    DECIMAL, NUMERIC,
    REAL, FLOAT, DOUBLE,
    BOOLEAN, BIT,
    DATE, TIME, TIME_WITH_TIMEZONE,
    TIMESTAMP, TIMESTAMP_WITH_TIMEZONE,
    BINARY, VARBINARY, LONGVARBINARY, BLOB,
    OTHER,
}
