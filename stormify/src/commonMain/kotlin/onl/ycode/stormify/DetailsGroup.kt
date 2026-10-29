// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
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

    private val lock = reentrantLock()
    private val members = mutableListOf<WeakRef<Any>>()

    /**
     * Per kind of children (parent class, child class and property): the slices fetched but not yet
     * handed to their parent, keyed by parent id, and the ids of every parent already
     * covered by a grouped query. A slice is dropped once its parent takes it — the
     * parent's delegate keeps it from then on — so the group does not hold the children
     * of the whole result alive for as long as any one row lives.
     *
     * Keyed by id (as [identityKey], so a decimal key matches whatever its scale) rather
     * than by entity identity because the id is what the grouping query returns anyway,
     * and identity maps are not available on every target.
     */
    private class Fetched(members: List<WeakRef<Any>>) {
        val pending = HashMap<String, List<Any>>()
        val covered = HashSet<String>()
        /**
         * Members not yet examined for a batch. Each batch takes from the front what it
         * looks at, so a result is walked once in total rather than once per batch.
         */
        val queue = ArrayDeque(members)
    }

    private val fetched = mutableMapOf<String, Fetched>()

    fun add(member: Any) {
        lock.withLock {
            val ref = WeakRef(member)
            members.add(ref)
            for (state in fetched.values) state.queue.addLast(ref)
        }
    }

    /**
     * The children of [owner]. The first request from a parent not yet covered fetches
     * the children of that parent together with up to [SiblingGroup.DEFAULT_BATCH_SIZE]
     * minus one other uncovered rows of the group, so a result of any size is served in
     * batches and every parent gets its own children.
     *
     * Returns `null` when the batch cannot be formed — a parent without a usable id, or
     * no other uncovered row of the same class — and the caller then does its own
     * single-parent query.
     */
    fun <D : Any> detailsFor(
        owner: Any,
        detailsClass: KClass<D>,
        propertyName: String?,
        stormify: Stormify,
    ): List<D>? {
        val key = "${owner::class.fullName}|${detailsClass.fullName}|${propertyName ?: ""}"
        val ownerId = identityKey(stormify.singleIdOrNull(owner) ?: return null)

        val slice = lock.withLock {
            val state = fetched.getOrPut(key) { Fetched(members) }
            if (ownerId in state.covered) {
                // Covered by an earlier batch: hand over its slice once. A parent asking
                // again (its delegate failed or was reset) gets a fresh single query.
                return@withLock state.pending.remove(ownerId) ?: return null
            }

            val parents = mutableListOf(owner)
            val batchIds = mutableSetOf(ownerId)
            val queue = state.queue
            while (parents.size < SiblingGroup.DEFAULT_BATCH_SIZE && queue.isNotEmpty()) {
                val member = queue.removeFirst().get() ?: continue
                if (member === owner || member::class != owner::class) continue
                val id = identityKey(stormify.singleIdOrNull(member) ?: continue)
                if (id in state.covered || !batchIds.add(id)) continue
                parents.add(member)
            }

            // One parent is not a batch; let the caller take the ordinary path so this
            // never turns a single lookup into two.
            if (parents.size < 2) return null

            // Not fetched (a failed query, or no batch possible): the other parents go back
            // to the front of the queue, so a later request can still batch them.
            fun requeue() {
                for (i in parents.size - 1 downTo 1) queue.addFirst(WeakRef(parents[i]))
            }
            val slices = try {
                stormify.getDetailsBatch(null, parents, detailsClass, propertyName)
            } catch (e: Throwable) {
                requeue()
                throw e
            } ?: run {
                requeue()
                return null
            }
            for ((id, children) in slices) {
                state.covered.add(id)
                if (id != ownerId) state.pending[id] = children
            }
            slices[ownerId] ?: emptyList()
        }

        @Suppress("UNCHECKED_CAST")
        return slice as List<D>
    }
}
