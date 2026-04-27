package onl.ycode.stormify.schemasync.model

import kotlinx.serialization.Serializable

enum class SlotCategory { TEXT, INTEGRAL, DECIMAL }

const val MAX_SLOTS_PER_CATEGORY = 9

@Serializable
data class TextSlot(
    val name: String,
    val length: Int? = null,
) {
    val ddl: String get() = if (length == null) "TEXT" else "VARCHAR($length)"
}

@Serializable
data class IntegralSlot(
    val name: String,
    val digits: Int,
) {
    val ddl: String get() = "NUMERIC($digits)"
}

@Serializable
data class DecimalSlot(
    val name: String,
    val precision: Int,
    val scale: Int,
) {
    val ddl: String get() = "NUMERIC($precision,$scale)"
}

@Serializable
data class SlotProfile(
    val text: List<TextSlot>,
    val integral: List<IntegralSlot>,
    val decimal: List<DecimalSlot>,
) {
    init {
        require(text.size <= MAX_SLOTS_PER_CATEGORY) { "Too many TEXT slots (${text.size}), max $MAX_SLOTS_PER_CATEGORY" }
        require(integral.size <= MAX_SLOTS_PER_CATEGORY) { "Too many INTEGRAL slots (${integral.size}), max $MAX_SLOTS_PER_CATEGORY" }
        require(decimal.size <= MAX_SLOTS_PER_CATEGORY) { "Too many DECIMAL slots (${decimal.size}), max $MAX_SLOTS_PER_CATEGORY" }
    }
}
