package onl.ycode.stormify.schemasync.classifier

import onl.ycode.stormify.schemasync.entity.ColumnDelta
import onl.ycode.stormify.schemasync.entity.TableDiff
import onl.ycode.stormify.schemasync.model.SlotCategory
import onl.ycode.stormify.schemasync.model.SlotProfile
import java.io.Closeable

/**
 * Routes classification requests to per-category sub-classifiers. A field of
 * Kotlin type `String` only ever competes against TEXT slots, an `Int` against
 * INTEGRAL slots, and so on — so we keep three independent indexes rather than
 * one shared one. Train via [trainFromSync] from the project's SYNCED columns
 * before calling [classify].
 */
class SchemaClassifier : Closeable {

    private val classifiers: Map<SlotCategory, SlotClassifier> = SlotCategory.entries.associateWith {
        SlotClassifier()
    }

    fun classifierFor(category: SlotCategory): SlotClassifier = classifiers.getValue(category)

    fun train(category: SlotCategory, slotName: String, columnName: String) {
        classifierFor(category).train(slotName, columnName)
    }

    fun classify(
        category: SlotCategory,
        columnName: String,
        topN: Int = 3,
    ): List<SlotClassifier.Suggestion> = classifierFor(category).classify(columnName, topN)

    /**
     * Walks every SYNCED column in [diffs] and trains the classifier with
     * `(category, slot, columnName)` derived from the column's actual DB
     * type. Slots are matched by exact DDL string (case-insensitive); columns
     * whose DB type doesn't correspond to any slot are skipped — they'd just
     * inject noise into the index.
     */
    fun trainFromSync(diffs: Collection<TableDiff>, slots: SlotProfile) {
        for (diff in diffs) {
            for (delta in diff.columnDeltas) {
                if (delta.kind != ColumnDelta.Kind.SYNCED) continue
                val field = delta.entityField ?: continue
                val cat = field.category ?: continue
                val col = delta.dbColumn ?: continue
                val slot = matchingSlot(slots, cat, col.dbType) ?: continue
                train(cat, slot, field.column)
            }
        }
    }

    override fun close() {
        classifiers.values.forEach { it.close() }
    }

    private fun matchingSlot(slots: SlotProfile, category: SlotCategory, dbType: String): String? {
        val list: List<Pair<String, String>> = when (category) {
            SlotCategory.TEXT -> slots.text.map { it.name to it.ddl }
            SlotCategory.INTEGRAL -> slots.integral.map { it.name to it.ddl }
            SlotCategory.DECIMAL -> slots.decimal.map { it.name to it.ddl }
        }
        return list.firstOrNull { it.second.equals(dbType, ignoreCase = true) }?.first
    }
}
