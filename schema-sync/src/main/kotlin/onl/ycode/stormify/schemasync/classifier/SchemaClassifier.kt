package onl.ycode.stormify.schemasync.classifier

import onl.ycode.stormify.schemasync.config.Seeds
import onl.ycode.stormify.schemasync.model.SlotCategory
import java.io.Closeable

/**
 * Routes classification requests to per-category sub-classifiers. A field of
 * Kotlin type `String` only ever competes against TEXT slots, an `Int` against
 * INTEGRAL slots, and so on — so we keep three independent indexes rather than
 * one shared one.
 */
class SchemaClassifier : Closeable {

    private val classifiers: Map<SlotCategory, SlotClassifier> = SlotCategory.entries.associateWith {
        SlotClassifier()
    }

    fun classifierFor(category: SlotCategory): SlotClassifier = classifiers.getValue(category)

    /** Loads vocabularies from a config-supplied [Seeds] block into each per-category index. */
    fun seed(seeds: Seeds) {
        seeds.text.forEach { classifierFor(SlotCategory.TEXT).seed(it.slot, it.words) }
        seeds.integral.forEach { classifierFor(SlotCategory.INTEGRAL).seed(it.slot, it.words) }
        seeds.decimal.forEach { classifierFor(SlotCategory.DECIMAL).seed(it.slot, it.words) }
    }

    fun train(category: SlotCategory, slotName: String, columnName: String) {
        classifierFor(category).train(slotName, columnName)
    }

    fun classify(
        category: SlotCategory,
        columnName: String,
        topN: Int = 3,
    ): List<SlotClassifier.Suggestion> = classifierFor(category).classify(columnName, topN)

    override fun close() {
        classifiers.values.forEach { it.close() }
    }
}
