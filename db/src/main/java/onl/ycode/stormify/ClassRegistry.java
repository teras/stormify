// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiPredicate;

import static onl.ycode.stormify.BeanHelper.createTableInfo;

class ClassRegistry {
    private final Map<Class<?>, TableInfo> registry = new HashMap<>();
    private final Map<Integer, BiPredicate<String, String>> idResolver = new TreeMap<>((a, b) -> b - a);
    private NamingPolicy namingPolicy = NamingPolicy.lowerCaseWithUnderscores;
    private final LinkedHashSet<String> blacklist = new LinkedHashSet<>();

    {
        blacklist.add("serialVersionUID");
        blacklist.add("idFieldValue");
        blacklist.add("transientId");
    }

    <T> TableInfo getTableInfo(Class<T> clazz) {
        return registry.computeIfAbsent(clazz, it -> createTableInfo(it, namingPolicy, idResolver, blacklist));
    }

    void registerPrimaryKeyResolver(int priority, BiPredicate<String, String> resolver) {
        idResolver.put(priority, resolver);
    }

    void setNamingPolicy(NamingPolicy namingPolicy) {
        this.namingPolicy = namingPolicy;
    }

    NamingPolicy getNamingPolicy() {
        return namingPolicy;
    }

    void addBlacklistField(String fieldName) {
        blacklist.add(fieldName);
    }

    void removeBlacklistField(String fieldName) {
        blacklist.remove(fieldName);
    }
}
