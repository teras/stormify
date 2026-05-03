package onl.ycode.stormify.schemasync.classifier

import onl.ycode.stormify.schemasync.entity.ColumnDelta
import onl.ycode.stormify.schemasync.entity.TableDiff
import onl.ycode.stormify.schemasync.model.SlotCategory
import onl.ycode.stormify.schemasync.model.SlotProfile

/**
 * In-memory map of column key → (category, slot) for the active session.
 * Holds both classifier auto-fills and user picks; queried by the diff
 * preview and the migration generator when emitting SQL.
 */
class ClassificationCache {
    private val map = mutableMapOf<String, Pair<SlotCategory, String>>()

    /** Resolve the slot picked (or auto-suggested) for `tableKey.column`. */
    fun get(columnKey: String): Pair<SlotCategory, String>? = map[columnKey]

    fun slotFor(columnKey: String): String? = map[columnKey]?.second

    /** Record a decision, replacing any prior entry for the same column. */
    fun put(columnKey: String, category: SlotCategory, slot: String) {
        map[columnKey] = category to slot
    }

    /** True when [columnKey] has a recorded decision. */
    operator fun contains(columnKey: String): Boolean = columnKey in map

    /** Read-only snapshot of all entries. */
    fun snapshot(): Map<String, Pair<SlotCategory, String>> = map.toMap()
}

/**
 * Populates [cache] with a slot for every classifiable ENTITY_ONLY column —
 * the [classifier]'s top suggestion when one exists, otherwise the
 * per-category default slot from [slots]. Entries already present in the
 * cache are left as-is.
 */
fun autoFillCache(
    cache: ClassificationCache,
    classifier: SchemaClassifier,
    diffs: Collection<TableDiff>,
    slots: SlotProfile,
) {
    for (diff in diffs) {
        for (delta in diff.columnDeltas) {
            if (delta.kind != ColumnDelta.Kind.ENTITY_ONLY) continue
            val field = delta.entityField ?: continue
            val cat = field.category ?: continue
            val key = "${diff.tableKey}.${field.column}"
            if (key in cache) continue
            val predicted = classifier.classify(cat, field.column, topN = 1).firstOrNull()
            val slotName = predicted?.takeIf { it.score > 0f }?.slotKey
                ?: slots.defaultName(cat)
                ?: continue
            cache.put(key, cat, slotName)
        }
    }
}
