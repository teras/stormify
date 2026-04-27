package onl.ycode.stormify.schemasync.config

import kotlinx.serialization.Serializable
import onl.ycode.stormify.schemasync.model.SlotProfile

/** Top-level config root, deserialized from `.schema-sync.toml`. */
@Serializable
data class SchemaSyncConfig(
    val slots: SlotProfile,
    val seeds: Seeds,
)

@Serializable
data class SeedEntry(
    val slot: String,
    val words: List<String>,
)

/** Seed vocabularies grouped by slot category. Each entry is `(slot, words)`. */
@Serializable
data class Seeds(
    val text: List<SeedEntry>,
    val integral: List<SeedEntry>,
    val decimal: List<SeedEntry>,
) {
    fun textAsMap(): Map<String, List<String>> = text.associate { it.slot to it.words }
    fun integralAsMap(): Map<String, List<String>> = integral.associate { it.slot to it.words }
    fun decimalAsMap(): Map<String, List<String>> = decimal.associate { it.slot to it.words }
}
