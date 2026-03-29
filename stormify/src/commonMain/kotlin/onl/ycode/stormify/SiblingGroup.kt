// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal class SiblingGroup {
    companion object {
        const val DEFAULT_BATCH_SIZE = 32
    }

    private val lock = SynchronizedObject()
    private val members = mutableListOf<WeakRef<AutoTable>>()

    fun add(member: AutoTable) {
        synchronized(lock) {
            members.add(WeakRef(member))
        }
    }

    fun batchPopulate(trigger: AutoTable) {
        synchronized(lock) {
            if (trigger.`!siblingGroup` !== this) return
            trigger.`!siblingGroup` = null

            val toPopulate = mutableListOf(trigger)
            val alive = mutableListOf<WeakRef<AutoTable>>()
            for (ref in members) {
                val member = ref.get()
                if (member == null) continue // dead ref — skip (prune)
                alive.add(ref)
                if (toPopulate.size >= DEFAULT_BATCH_SIZE) continue
                if (member !== trigger && member.`!siblingGroup` === this) {
                    member.markPopulated()
                    member.`!siblingGroup` = null
                    toPopulate.add(member)
                }
            }
            members.clear()
            members.addAll(alive)

            val stormify = trigger.`!stormify` ?: return
            if (toPopulate.size == 1)
                stormify.populate(trigger)
            else
                stormify.batchPopulate(toPopulate)
        }
    }
}
