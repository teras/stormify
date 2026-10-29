// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import onl.ycode.kdbc.SQLException

internal class SiblingGroup {
    companion object {
        const val DEFAULT_BATCH_SIZE = 32
    }

    private val lock = reentrantLock()
    private val members = mutableListOf<WeakRef<AutoTable>>()

    fun add(member: AutoTable) {
        lock.withLock {
            members.add(WeakRef(member))
        }
    }

    /**
     * Loads [trigger] together with up to [DEFAULT_BATCH_SIZE] - 1 unloaded siblings,
     * through [stormify]. If the query fails, every member taken into the batch is put
     * back unloaded and in the group, so the next access retries the batch.
     *
     * A member whose row does not exist (a foreign key pointing nowhere) fails alone:
     * the members that were found stay loaded, the missing ones leave the group and
     * stay unloaded, so each access to one of them does its own single load and
     * reports the missing row, while its valid siblings keep working.
     */
    /**
     * Whether [member] is still being loaded by this group's batch, as seen by the calling
     * thread. Taking the group's lock waits for a batch running on another thread; the
     * thread running it re-enters and still sees [member] loading.
     */
    fun isLoadingNow(member: AutoTable): Boolean = lock.withLock { member._loadingGroup === this }

    /**
     * Returns `false` without loading anything when [trigger] is no longer in this group:
     * another thread's batch took it meanwhile, and either loaded it or found its row
     * missing. The caller then checks which, and reports a missing row itself.
     */
    fun batchPopulate(trigger: AutoTable, stormify: Stormify): Boolean {
        lock.withLock {
            if (trigger._siblingGroup !== this) return false
            trigger._siblingGroup = null

            val toPopulate = mutableListOf(trigger)
            val alive = mutableListOf<WeakRef<AutoTable>>()
            for (ref in members) {
                val member = ref.get()
                if (member == null) continue // dead ref — skip (prune)
                alive.add(ref)
                if (toPopulate.size >= DEFAULT_BATCH_SIZE) continue
                if (member !== trigger && member._siblingGroup === this) {
                    // Loading, not loaded: other threads wait on this group's lock.
                    member._loadingGroup = this
                    member._siblingGroup = null
                    toPopulate.add(member)
                }
            }
            members.clear()
            members.addAll(alive)

            val missing = try {
                if (toPopulate.size == 1) {
                    if (stormify.loadIfExists(trigger)) emptyList() else listOf(trigger)
                } else
                    stormify.batchPopulate(toPopulate)
            } catch (e: Throwable) {
                // Nothing loaded: back to the group, unloaded, for the next access to retry.
                // The trigger's own state is kept by its caller.
                for (member in toPopulate) {
                    member._loadingGroup = null
                    member._siblingGroup = this
                }
                throw e
            }
            // Found rows are loaded. Missing rows: with DanglingReferences.NULL they are
            // loaded too, with default values; otherwise they stay unloaded and leave the
            // group, so their next access is a single load that reports the missing row
            // again, without dragging the siblings along.
            val emptyIfMissing = stormify.danglingReferences == DanglingReferences.NULL
            for (member in toPopulate) {
                if (member === trigger) continue
                if (emptyIfMissing || missing.none { it === member }) member.markHydrated()
                member._loadingGroup = null
            }
            if (!emptyIfMissing && missing.any { it === trigger })
                throw SQLException("No data found for ${trigger::class.simpleName} with id " +
                        "${stormify.singleIdOrNull(trigger)}")
            return true
        }
    }
}
