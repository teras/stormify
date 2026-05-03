package onl.ycode.stormify.schemasync.entity

import onl.ycode.stormify.schemasync.model.DateType
import onl.ycode.stormify.schemasync.model.DecimalType
import onl.ycode.stormify.schemasync.model.KotlinDefaults
import onl.ycode.stormify.schemasync.model.PkIntegerType
import onl.ycode.stormify.schemasync.model.TimeType
import onl.ycode.stormify.schemasync.model.TimestampType

/**
 * Inspects the user's already-scanned entities and infers the dominant choice
 * for each preference in [KotlinDefaults]. Where the corpus carries no signal
 * (no PK fields, no date/time fields, etc.), the supplied [fallback] value is
 * kept untouched.
 *
 * Java.time vs kotlinx.datetime is disambiguated via the import list on each
 * entity's source file.
 */
object KotlinDefaultsDetector {

    fun detect(entities: List<KotlinEntity>, fallback: KotlinDefaults): KotlinDefaults {
        if (entities.isEmpty()) return fallback

        val pkVotes = mutableMapOf<PkIntegerType, Int>()
        val dateVotes = mutableMapOf<DateType, Int>()
        val timeVotes = mutableMapOf<TimeType, Int>()
        val timestampVotes = mutableMapOf<TimestampType, Int>()
        val decimalVotes = mutableMapOf<DecimalType, Int>()

        for (entity in entities) {
            val importsByLeaf: Map<String, String> = entity.imports.associateBy { it.substringAfterLast('.') }
            for (field in entity.fields) {
                val simple = field.type.substringAfterLast('.').substringBefore('<').trim()
                val fqn = importsByLeaf[simple]

                if (field.primaryKey) {
                    classifyPk(simple, fqn)?.let { pkVotes.merge(it, 1, Int::plus) }
                }
                classifyDate(simple, fqn)?.let { dateVotes.merge(it, 1, Int::plus) }
                classifyTime(simple, fqn)?.let { timeVotes.merge(it, 1, Int::plus) }
                classifyTimestamp(simple, fqn)?.let { timestampVotes.merge(it, 1, Int::plus) }
                classifyDecimal(simple, fqn)?.let { decimalVotes.merge(it, 1, Int::plus) }
            }
        }

        return KotlinDefaults(
            pkIntegerType = pkVotes.maxByOrNull { it.value }?.key ?: fallback.pkIntegerType,
            dateType = dateVotes.maxByOrNull { it.value }?.key ?: fallback.dateType,
            timeType = timeVotes.maxByOrNull { it.value }?.key ?: fallback.timeType,
            timestampType = timestampVotes.maxByOrNull { it.value }?.key ?: fallback.timestampType,
            decimalType = decimalVotes.maxByOrNull { it.value }?.key ?: fallback.decimalType,
        )
    }

    private fun classifyPk(simple: String, fqn: String?): PkIntegerType? = when (simple) {
        "Long" -> PkIntegerType.LONG
        "Int", "Integer" -> PkIntegerType.INT
        "Short" -> PkIntegerType.SHORT
        "BigInteger" ->
            if (fqn?.startsWith("com.ionspin.kotlin.bignum") == true) PkIntegerType.IONSPIN_BIG_INTEGER
            else PkIntegerType.BIG_INTEGER
        "BigDecimal" ->
            if (fqn?.startsWith("com.ionspin.kotlin.bignum") == true) PkIntegerType.IONSPIN_BIG_DECIMAL
            else PkIntegerType.BIG_DECIMAL
        "String" -> PkIntegerType.STRING
        "UUID" -> PkIntegerType.UUID
        "Uuid" -> PkIntegerType.KOTLIN_UUID
        else -> null
    }

    private fun classifyDate(simple: String, fqn: String?): DateType? {
        if (simple != "LocalDate") return null
        return if (fqn?.startsWith("kotlinx.datetime") == true) DateType.KOTLINX_LOCAL_DATE
        else DateType.JAVA_LOCAL_DATE
    }

    private fun classifyTime(simple: String, fqn: String?): TimeType? {
        if (simple != "LocalTime") return null
        return if (fqn?.startsWith("kotlinx.datetime") == true) TimeType.KOTLINX_LOCAL_TIME
        else TimeType.JAVA_LOCAL_TIME
    }

    private fun classifyTimestamp(simple: String, fqn: String?): TimestampType? = when (simple) {
        "Instant" -> when {
            fqn?.startsWith("kotlinx.datetime") == true -> TimestampType.KOTLINX_INSTANT
            fqn?.startsWith("kotlin.time") == true -> TimestampType.KOTLIN_INSTANT
            else -> TimestampType.JAVA_INSTANT
        }
        "LocalDateTime" -> if (fqn?.startsWith("kotlinx.datetime") == true) TimestampType.KOTLINX_LOCAL_DATE_TIME
                           else TimestampType.JAVA_LOCAL_DATE_TIME
        "OffsetDateTime" -> TimestampType.JAVA_OFFSET_DATE_TIME
        "ZonedDateTime" -> TimestampType.JAVA_ZONED_DATE_TIME
        else -> null
    }

    private fun classifyDecimal(simple: String, fqn: String?): DecimalType? = when (simple) {
        "BigDecimal" ->
            if (fqn?.startsWith("com.ionspin.kotlin.bignum") == true) DecimalType.IONSPIN_BIG_DECIMAL
            else DecimalType.BIG_DECIMAL
        "Double" -> DecimalType.DOUBLE
        else -> null
    }
}
