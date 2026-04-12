// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlin.reflect.KClass

internal class PopulationContext {
    private val dedupMap = mutableMapOf<String, Any>()
    private val siblingGroups = mutableMapOf<KClass<*>, SiblingGroup>()

    fun getOrCreateReference(
        type: KClass<*>,
        idValue: Any,
        stormify: Stormify
    ): Any {
        val key = "${type.fullName}:$idValue"
        dedupMap[key]?.let { return it }

        val wrapper = stormify.createReferenceStub(type, idValue)
        dedupMap[key] = wrapper

        if (wrapper is AutoTable) {
            val group = siblingGroups.getOrPut(type) { SiblingGroup() }
            group.add(wrapper)
            wrapper._siblingGroup = group
        }

        return wrapper
    }
}
