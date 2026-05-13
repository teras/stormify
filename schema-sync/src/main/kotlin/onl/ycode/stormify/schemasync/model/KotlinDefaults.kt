package onl.ycode.stormify.schemasync.model

import kotlinx.serialization.Serializable

/**
 * Kotlin-side preferences applied when generating new entity properties or
 * brand-new entity files from a DB-only table. They cover the few mappings
 * where multiple sensible Kotlin types fit a single JDBC type.
 */
@Serializable
data class KotlinDefaults(
    /** Kotlin type for INTEGER primary keys. */
    val pkIntegerType: PkIntegerType = PkIntegerType.LONG,
    /** Kotlin type for JDBC DATE columns. */
    val dateType: DateType = DateType.JAVA_LOCAL_DATE,
    /** Kotlin type for JDBC TIME columns. */
    val timeType: TimeType = TimeType.JAVA_LOCAL_TIME,
    /** Kotlin type for JDBC TIMESTAMP columns. */
    val timestampType: TimestampType = TimestampType.JAVA_INSTANT,
    /** Kotlin type for JDBC NUMERIC/DECIMAL columns. */
    val decimalType: DecimalType = DecimalType.BIG_DECIMAL,
    /** How generated entity classes relate to stormify's `ByDb` base class. */
    val entityBase: EntityBase = EntityBase.BYDB_FOR_NEW,
)

@Serializable
enum class EntityBase {
    /** Generate plain entities; never extend `ByDb`. */
    NONE,
    /** New entities extend `ByDb()`, and entities without a supertype are retrofitted on apply. */
    BYDB_ALWAYS,
    /** New entities extend `ByDb()`; existing entities are left untouched. */
    BYDB_FOR_NEW,
}

/** A Kotlin type name plus the import the generator should add. */
internal interface KotlinTypeChoice {
    val kotlin: String
    val import: String?
}

@Serializable
enum class PkIntegerType(val display: String, override val kotlin: String, override val import: String? = null) : KotlinTypeChoice {
    LONG("Long", "Long"),
    INT("Int", "Int"),
    SHORT("Short", "Short"),
    BIG_INTEGER("java.math.BigInteger", "BigInteger", "java.math.BigInteger"),
    IONSPIN_BIG_INTEGER("com.ionspin.kotlin.bignum.integer.BigInteger", "BigInteger", "com.ionspin.kotlin.bignum.integer.BigInteger"),
    BIG_DECIMAL("java.math.BigDecimal", "BigDecimal", "java.math.BigDecimal"),
    IONSPIN_BIG_DECIMAL("com.ionspin.kotlin.bignum.decimal.BigDecimal", "BigDecimal", "com.ionspin.kotlin.bignum.decimal.BigDecimal"),
    STRING("String", "String"),
    UUID("java.util.UUID", "UUID", "java.util.UUID"),
    KOTLIN_UUID("kotlin.uuid.Uuid", "Uuid", "kotlin.uuid.Uuid"),
}

@Serializable
enum class DateType(val display: String, override val kotlin: String, override val import: String?) : KotlinTypeChoice {
    JAVA_LOCAL_DATE("java.time.LocalDate", "LocalDate", "java.time.LocalDate"),
    KOTLINX_LOCAL_DATE("kotlinx.datetime.LocalDate", "LocalDate", "kotlinx.datetime.LocalDate"),
}

@Serializable
enum class TimeType(val display: String, override val kotlin: String, override val import: String?) : KotlinTypeChoice {
    JAVA_LOCAL_TIME("java.time.LocalTime", "LocalTime", "java.time.LocalTime"),
    JAVA_OFFSET_TIME("java.time.OffsetTime", "OffsetTime", "java.time.OffsetTime"),
    KOTLINX_LOCAL_TIME("kotlinx.datetime.LocalTime", "LocalTime", "kotlinx.datetime.LocalTime"),
}

@Serializable
enum class TimestampType(val display: String, override val kotlin: String, override val import: String?) : KotlinTypeChoice {
    JAVA_INSTANT("java.time.Instant", "Instant", "java.time.Instant"),
    JAVA_LOCAL_DATE_TIME("java.time.LocalDateTime", "LocalDateTime", "java.time.LocalDateTime"),
    JAVA_OFFSET_DATE_TIME("java.time.OffsetDateTime", "OffsetDateTime", "java.time.OffsetDateTime"),
    JAVA_ZONED_DATE_TIME("java.time.ZonedDateTime", "ZonedDateTime", "java.time.ZonedDateTime"),
    KOTLIN_INSTANT("kotlin.time.Instant", "Instant", "kotlin.time.Instant"),
    KOTLINX_INSTANT("kotlinx.datetime.Instant", "Instant", "kotlinx.datetime.Instant"),
    KOTLINX_LOCAL_DATE_TIME("kotlinx.datetime.LocalDateTime", "LocalDateTime", "kotlinx.datetime.LocalDateTime"),
}

@Serializable
enum class DecimalType(val display: String, override val kotlin: String, override val import: String? = null) : KotlinTypeChoice {
    BIG_DECIMAL("java.math.BigDecimal", "BigDecimal", "java.math.BigDecimal"),
    IONSPIN_BIG_DECIMAL("com.ionspin.kotlin.bignum.decimal.BigDecimal", "BigDecimal", "com.ionspin.kotlin.bignum.decimal.BigDecimal"),
    DOUBLE("Double", "Double"),
}
