// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.util.HashMap;
import java.util.Map;

class PopulationContext {
    private final Map<String, AutoTable> dedupMap = new HashMap<>();
    private final Map<Class<?>, SiblingGroup> siblingGroups = new HashMap<>();

    AutoTable getOrCreateReference(Class<?> type, Object idValue, ClassRegistry registry) {
        String key = type.getName() + ":" + idValue;
        AutoTable existing = dedupMap.get(key);
        if (existing != null)
            return existing;
        try {
            AutoTable wrapper = (AutoTable) type.getDeclaredConstructor().newInstance();
            registry.getTableInfo(type).getPrimaryKey().setValue(wrapper, idValue);
            dedupMap.put(key, wrapper);
            SiblingGroup group = siblingGroups.computeIfAbsent(type, k -> new SiblingGroup());
            group.add(wrapper);
            wrapper.siblingGroup = group;
            return wrapper;
        } catch (Exception e) {
            throw new QueryException("Failed to create reference for " + type.getSimpleName() + " with id " + idValue, e);
        }
    }
}
