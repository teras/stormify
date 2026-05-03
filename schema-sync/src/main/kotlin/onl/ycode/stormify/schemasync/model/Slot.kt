package onl.ycode.stormify.schemasync.model

import kotlinx.serialization.Serializable

enum class SlotCategory { TEXT, INTEGRAL, DECIMAL }

const val MAX_SLOTS_PER_CATEGORY = 9

/**
 * One slot definition in a category. The `default = true` flag picks the
 * fallback slot the classifier returns when nothing it has seen so far
 * matches a new column name. Exactly one slot per category should carry the
 * flag; the slot editor enforces this when the user toggles it.
 */
@Serializable
data class TextSlot(
    val name: String,
    val length: Int? = null,
    val default: Boolean = false,
) {
    val ddl: String get() = if (length == null) "TEXT" else "VARCHAR($length)"
}

@Serializable
data class IntegralSlot(
    val name: String,
    val digits: Int? = null,
    val default: Boolean = false,
) {
    val ddl: String get() = if (digits == null) "NUMERIC" else "NUMERIC($digits)"
}

@Serializable
data class DecimalSlot(
    val name: String,
    val precision: Int? = null,
    val scale: Int? = null,
    val default: Boolean = false,
) {
    val ddl: String get() = when {
        precision == null -> "NUMERIC"
        scale == null -> "NUMERIC($precision)"
        else -> "NUMERIC($precision,$scale)"
    }
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

    /** The slot marked `default` in [category], or null when none is marked. */
    fun defaultName(category: SlotCategory): String? = when (category) {
        SlotCategory.TEXT -> text.firstOrNull { it.default }?.name
        SlotCategory.INTEGRAL -> integral.firstOrNull { it.default }?.name
        SlotCategory.DECIMAL -> decimal.firstOrNull { it.default }?.name
    }

    /** Resolves a slot name to its DDL string, scoped to a category. */
    fun ddlFor(category: SlotCategory, slotName: String): String? = when (category) {
        SlotCategory.TEXT -> text.firstOrNull { it.name == slotName }?.ddl
        SlotCategory.INTEGRAL -> integral.firstOrNull { it.name == slotName }?.ddl
        SlotCategory.DECIMAL -> decimal.firstOrNull { it.name == slotName }?.ddl
    }
}
