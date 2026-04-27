package onl.ycode.stormify.schemasync.model

enum class SlotCategory { TEXT, INTEGRAL, DECIMAL }

const val MAX_SLOTS_PER_CATEGORY = 9

data class TextSlot(
    val name: String,
    val length: Int?,
) {
    val ddl: String get() = if (length == null) "TEXT" else "VARCHAR($length)"
}

data class IntegralSlot(
    val name: String,
    val digits: Int,
) {
    val ddl: String get() = "NUMERIC($digits)"
}

data class DecimalSlot(
    val name: String,
    val precision: Int,
    val scale: Int,
) {
    val ddl: String get() = "NUMERIC($precision,$scale)"
}

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

fun defaultSlotProfile(): SlotProfile = SlotProfile(
    text = listOf(
        TextSlot("char", 1),
        TextSlot("short", 10),
        TextSlot("name", 50),
        TextSlot("tweet", 150),
        TextSlot("line", 200),
        TextSlot("page", 1000),
        TextSlot("text", null),
    ),
    integral = listOf(
        IntegralSlot("flag", 1),
        IntegralSlot("percent", 2),
        IntegralSlot("permille", 3),
        IntegralSlot("int", 10),
        IntegralSlot("long", 19),
    ),
    decimal = listOf(
        DecimalSlot("tenth", 19, 1),
        DecimalSlot("money", 19, 2),
        DecimalSlot("volume", 19, 3),
        DecimalSlot("precise", 19, 4),
        DecimalSlot("geo", 19, 6),
        DecimalSlot("rate", 19, 8),
    ),
)
