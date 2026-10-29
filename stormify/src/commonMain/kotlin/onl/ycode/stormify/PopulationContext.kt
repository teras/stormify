// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import onl.ycode.stormify.TypeUtils.castTo
import kotlin.reflect.KClass

internal class PopulationContext {
    private val dedupMap = mutableMapOf<String, Any>()
    private val siblingGroups = mutableMapOf<KClass<*>, SiblingGroup>()

    /** For [DanglingReferences.NULL]: who refers to each reference, by its dedup key. */
    private class Referrers(val type: KClass<*>, val keyValue: Any) {
        val owners = mutableListOf<Pair<Any, List<ResolvedProperty<*>>>>()
    }
    private val referrers = LinkedHashMap<String, Referrers>()

    fun getOrCreateReference(
        type: KClass<*>,
        idValue: Any,
        stormify: Stormify,
        owner: Any? = null,
        setters: List<ResolvedProperty<*>> = emptyList(),
    ): Any {
        // Key on the value as the referenced key type: FK columns of different
        // types (an INT and a DECIMAL column pointing at one DECIMAL key) can
        // hand back different classes and scales for the same row.
        val idType = stormify.resolveTableInfo(type).idTypes.singleOrNull()
        val keyValue = idType?.let { castTo(it, idValue, stormify) } ?: idValue
        val key = "${type.fullName}:${identityKey(keyValue)}"
        val wrapper = dedupMap[key] ?: stormify.createReferenceStub(type, idValue).also { created ->
            dedupMap[key] = created
            if (created is AutoTable) {
                val group = siblingGroups.getOrPut(type) { SiblingGroup() }
                group.add(created)
                created._siblingGroup = group
            }
        }
        if (owner != null && idType != null && stormify.danglingReferences == DanglingReferences.NULL)
            referrers.getOrPut(key) { Referrers(type, keyValue) }.owners += owner to setters.filter { it.isReference }
        return wrapper
    }

    /**
     * For [DanglingReferences.NULL], once the result is complete: checks which referenced
     * rows exist, one query per referenced type (in chunks), and sets every property that
     * points at a missing row to `null`. The existing references stay lazy.
     */
    fun resolveDanglingReferences(stormify: Stormify, conn: onl.ycode.kdbc.Connection?) {
        if (referrers.isEmpty() || stormify.danglingReferences != DanglingReferences.NULL) return
        for ((type, refs) in referrers.values.groupBy { it.type }) {
            val info = stormify.resolveTableInfo(type)
            val idType = info.idTypes.singleOrNull() ?: continue
            val found = HashSet<String>()
            for (chunk in refs.chunked(DANGLING_CHECK_CHUNK)) {
                val sql = "SELECT ${info.idDbNames[0]} FROM ${info.tableName} WHERE ${info.idDbNames[0]} IN (" +
                        chunk.joinToString(", ") { "?" } + ")"
                @Suppress("UNCHECKED_CAST")
                stormify.readCursor(conn, idType as KClass<Any>, sql, *chunk.map { it.keyValue }.toTypedArray(), fetchSize = 0) {
                    found += identityKey(it)
                }
            }
            for (ref in refs) {
                if (identityKey(ref.keyValue) in found) continue
                for ((owner, setters) in ref.owners) {
                    // Only an entity that can remember the key is set to null: it writes the
                    // key back on update. A plain object keeps the reference, which holds
                    // the key, so an update never turns the column into NULL.
                    if (owner !is StormifyEntity) continue
                    for (setter in setters) {
                        @Suppress("UNCHECKED_CAST")
                        try {
                            (setter as ResolvedProperty<Any>).setter(owner, null, stormify)
                            val keys = owner._danglingKeys ?: HashMap<String, Any>().also { owner._danglingKeys = it }
                            keys[setter.name] = ref.keyValue
                        } catch (_: Exception) {
                            // A non-nullable property keeps the reference, which loads empty.
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** Keys per existence query; well below the 1000 Oracle allows in one IN list. */
        const val DANGLING_CHECK_CHUNK = 500
    }
}
