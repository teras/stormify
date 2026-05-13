package onl.ycode.stormify.schemasync.entity

import onl.ycode.stormify.schemasync.model.SlotCategory
import onl.ycode.stormify.schemasync.model.TypeFamily

/**
 * Maps a Kotlin type name (as it appears in entity JSON) to the [SlotCategory]
 * its column should be classified under, or null when the type is deterministic
 * (boolean, dates, blobs) and needs no slot.
 */
object KotlinTypeMapper {
    fun categoryFor(type: String): SlotCategory? = when (simpleName(type)) {
        "String", "CharSequence" -> SlotCategory.TEXT
        "Int", "Integer", "Long", "Short", "Byte", "BigInteger" -> SlotCategory.INTEGRAL
        "Float", "Double", "BigDecimal" -> SlotCategory.DECIMAL
        else -> null
    }

    /** Broader bucket used for diff-time mismatch detection (covers booleans / dates / blobs / UUID). */
    fun familyFor(type: String): TypeFamily? = when (simpleName(type)) {
        "String", "CharSequence" -> TypeFamily.TEXT
        "Int", "Integer", "Long", "Short", "Byte", "BigInteger" -> TypeFamily.INTEGRAL
        "Float", "Double", "BigDecimal" -> TypeFamily.DECIMAL
        "Boolean" -> TypeFamily.BOOLEAN
        "LocalDate" -> TypeFamily.DATE
        "LocalTime", "OffsetTime", "Time" -> TypeFamily.TIME
        "LocalDateTime", "Instant", "OffsetDateTime", "ZonedDateTime", "Date", "Timestamp" -> TypeFamily.TIMESTAMP
        "ByteArray", "CharArray" -> TypeFamily.BINARY
        "UUID", "Uuid" -> TypeFamily.UUID
        else -> null
    }

    /** Returns true if a Kotlin type maps to a deterministic DB type that needs no classification. */
    fun isDeterministic(type: String): Boolean =
        categoryFor(type) == null && familyFor(type) != null

    /** Strips package prefix, trailing `?`, and whitespace — matches by simple name only. */
    private fun simpleName(type: String): String =
        type.removeSuffix("?").trim().substringAfterLast('.')
}
