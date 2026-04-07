// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import onl.ycode.logger.LogManager

abstract class AutoTable : StormifyEntity() {
    private val lock = SynchronizedObject()
    private val hasRun = atomic(false)
    internal var `!siblingGroup`: SiblingGroup? = null

    fun populate() {
        if (hasRun.value) return
        val ctr = `!stormify` ?: Stormify.defaultInstance ?: return LogManager.getLogger(AutoTable::class)
            .error("Stormify is not set for class ${this::class.qualifiedName}.")
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

    fun markPopulated() {
        hasRun.value = true
    }
}
