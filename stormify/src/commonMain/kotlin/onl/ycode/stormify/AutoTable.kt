// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import onl.ycode.logger.LogManager

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
    private val hasRun = atomic(false)
    internal var `!siblingGroup`: SiblingGroup? = null

    /** Loads this entity's data from the database if it has not been populated yet. Thread-safe. */
    fun populate() {
        if (hasRun.value) return
        val ctr = `!stormify` ?: Stormify.defaultInstance ?: return LogManager.getLogger(AutoTable::class)
            .error("Stormify is not set for class ${this::class.qualifiedName}. Use Stormify.asDefault() to set a default instance.")
        synchronized(lock) {
            if (!hasRun.value) {
                hasRun.value = true
                val group = `!siblingGroup`
                if (group != null)
                    group.batchPopulate(this)
                else
                    ctr.populate(this)
            }
        }
    }

    /** Marks this entity as already populated, preventing any future lazy-load. */
    fun markPopulated() {
        hasRun.value = true
    }
}
