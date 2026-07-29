// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.reflect.KClass

/**
 * The rows one query returned, kept together so their children can be fetched in one go.
 *
 * Reading a `by lazyDetails()` property on every row of a page is the classic N+1: fifty
 * orders become fifty-one queries. The rows came back together, though, so the first one
 * asked can fetch the children for all of them — `WHERE fk IN (…)` — and hand each row
 * its own slice.
 *
 * Only [lazyDetails] uses this. The plain [Stormify.getDetails] re-queries on every call
 * by contract, and batching it would mean caching behind the caller's back.
 */
internal class DetailsGroup {

    private val lock = SynchronizedObject()
    private val members = mutableListOf<WeakRef<Any>>()

    /**
     * Slices already fetched, keyed by which children were asked for and then by parent
     * id. Keyed by id rather than by entity identity because the id is what the grouping
     * query returns anyway, and identity maps are not available on every target.
     */
    private val loaded = mutableMapOf<String, Map<Any, List<Any>>>()

    fun add(member: Any) {
        synchronized(lock) { members.add(WeakRef(member)) }
    }

    /**
     * The children of [owner], fetching the whole group's children on the first call.
     *
     * Returns `null` when the batch cannot be formed — a parent without a usable id, or
     * a group that has been collected down to this one row — and the caller then does
     * its own single-parent query.
     */
    fun <D : Any> detailsFor(
        owner: Any,
        detailsClass: KClass<D>,
        propertyName: String?,
        stormify: Stormify,
    ): List<D>? {
        val key = "${detailsClass.fullName}|${propertyName ?: ""}"
        val ownerId = stormify.singleIdOrNull(owner) ?: return null

        val slices = synchronized(lock) {
            loaded[key]?.let { return@synchronized it }

            val alive = mutableListOf<WeakRef<Any>>()
            val parents = mutableListOf<Any>()
            for (ref in members) {
                val member = ref.get() ?: continue
                alive.add(ref)
                if (parents.size < SiblingGroup.DEFAULT_BATCH_SIZE && member::class == owner::class)
                    parents.add(member)
            }
            members.clear()
            members.addAll(alive)

            // One parent is not a batch; let the caller take the ordinary path so this
            // never turns a single lookup into two.
            if (parents.size < 2) return null

            val fetched = stormify.getDetailsBatch(null, parents, detailsClass, propertyName)
                ?: return null
            loaded[key] = fetched
            fetched
        }

        @Suppress("UNCHECKED_CAST")
        return (slices[ownerId] ?: emptyList<Any>()) as List<D>
    }
}
