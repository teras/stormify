// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Base class for entities that support lazy auto-population from the database.
 *
 * Properties delegated via [db] will trigger a [populate] call on first access,
 * loading the entity's data from the database based on its primary key.
 * When multiple AutoTable instances share the same [SiblingGroup][onl.ycode.stormify.SiblingGroup],
 * they are batch-populated in a single query for efficiency.
 */
abstract class AutoTable : StormifyEntity() {
    private val lock = SynchronizedObject()
    @Transient internal val _hasRun = atomic(false)
    @Transient internal var _siblingGroup: SiblingGroup? = null

    /**
     * Tracks whether any [db]-delegated property has been written on this entity.
     * Set to `true` by [db.setValue] (both when the user explicitly writes a field and
     * when an internal DB load fills fields via the same setter path). Used by [db.getValue]
     * to distinguish "fresh entity with only ID set" (should attempt lazy-load, and fail
     * loudly if no Stormify is available) from "user-constructed entity with some fields
     * set" (should return in-memory values silently).
     */
    @Transient internal var _userTouched = false

    /**
     * Loads this entity's data from the database if it has not been populated yet. Thread-safe.
     *
     * Returns silently (no-op) when no [Stormify] instance is attached and no
     * [Stormify.defaultInstance] is set — this is a legitimate state for freshly constructed
     * entities that have no database row to load from. The check for missing stormify
     * during unintended lazy-load lives in the [db] property delegate.
     */
    fun populate() {
        if (_hasRun.value) return
        val ctr = _stormify ?: Stormify.defaultInstance ?: return
        synchronized(lock) {
            if (!_hasRun.value) {
                _hasRun.value = true
                val group = _siblingGroup
                if (group != null)
                    group.batchPopulate(this)
                else
                    ctr.populate(this)
            }
        }
    }

    /** Marks this entity as already populated, preventing any future lazy-load. */
    fun markPopulated() {
        _hasRun.value = true
    }
}
