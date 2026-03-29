// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlin.reflect.KClass

internal class PopulationContext {
    private val dedupMap = mutableMapOf<String, AutoTable>()
    private val siblingGroups = mutableMapOf<KClass<*>, SiblingGroup>()

    @Suppress("UNCHECKED_CAST")
    fun getOrCreateReference(
        type: KClass<*>,
        idValue: Any,
        stormify: Stormify
    ): AutoTable {
        val key = "${type.fullName}:$idValue"
        dedupMap[key]?.let { return it }

        val info = stormify.resolveTableInfo(type) as TableInfo<AutoTable>
        val wrapper = info.create()
        wrapper.`!stormify` = stormify
        info.setField(wrapper, info.idDbNames[0], idValue, stormify)
        dedupMap[key] = wrapper

        val group = siblingGroups.getOrPut(type) { SiblingGroup() }
        group.add(wrapper)
        wrapper.`!siblingGroup` = group

        return wrapper
    }
}
