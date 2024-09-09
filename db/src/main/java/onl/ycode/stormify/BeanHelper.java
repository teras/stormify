// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.beans.Transient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.BiPredicate;

import static onl.ycode.stormify.AnnotationUtils.*;
import static onl.ycode.stormify.Utils.extract;


class BeanHelper {
    private BeanHelper() {
    }

    static TableInfo createTableInfo(Class<?> givenClass, NamingPolicy namingPolicy, Map<Integer, BiPredicate<String, String>> idResolver, Collection<String> blacklist) {
        Class<?> clazz = TypeUtils.normalizeClass(givenClass);
        if (clazz == null)
            throw new QueryException("Cannot create table info for class " + givenClass.getName());
        boolean hasPrimaryKey = false;
        Collection<BeanInfo> beanInfos = findBeanInfos(clazz, namingPolicy);
        if (beanInfos.isEmpty())
            return new TableInfo(clazz, namingPolicy.convert(clazz.getSimpleName()), Collections.emptyList());
        Collection<BeanInfo> foundInFields = new ArrayList<>();

        for (Field field : getFields(clazz)) {
            String name = field.getName();
            if (name.endsWith("$delegate")) {
                if (name.length() == 9) continue;
                name = name.substring(0, name.length() - 9);
            }
            BeanInfo beanInfo = findBeanInfo(name, beanInfos);
            if (field.isAnnotationPresent(Transient.class)) {
                if (beanInfo == null)
                    throw new QueryException("Missing bean methods for Transient field " + field.getName() + " in class " + clazz.getName());
            } else if (beanInfo != null) {
                foundInFields.add(beanInfo);
                beanInfo.fieldAnnotation = field.getAnnotation(DbField.class);
                beanInfo.sequence = beanInfo.fieldAnnotation != null && !beanInfo.fieldAnnotation.primarySequence().trim().isEmpty()
                        ? beanInfo.fieldAnnotation.primarySequence().trim()
                        : getAnnotationValue(field, sequenceGeneratorClass, sequenceGeneratorNameMethod);
                if (hasAnnotation(field, columnClass)) {
                    beanInfo.dbName = getAnnotationValue(field, columnClass, columnNameMethod);
                    beanInfo.creatable = getAnnotationValue(field, columnClass, insertableColumnMethod);
                    beanInfo.updatable = getAnnotationValue(field, columnClass, updateableColumnMethod);
                } else if (hasAnnotation(field, joinColumnClass)) {
                    beanInfo.dbName = getAnnotationValue(field, joinColumnClass, joinColumnNameMethod);
                    beanInfo.creatable = getAnnotationValue(field, joinColumnClass, insertableJoinColumnMethod);
                    beanInfo.updatable = getAnnotationValue(field, joinColumnClass, updatableJoinColumnMethod);
                }
                beanInfo.primaryByIdAnnotation = true;
                String annName = getAnnotationValue(field, columnClass, columnNameMethod);
                if (annName == null)
                    annName = getAnnotationValue(field, joinColumnClass, joinColumnNameMethod);
                if (annName != null)
                    beanInfo.dbName = annName;
                beanInfo.primaryByIdAnnotation = hasAnnotation(field, idClass);
            }
        }
        beanInfos.addAll(foundInFields);
        Collection<FieldInfo> fieldInfo = new LinkedHashSet<>();
        for (BeanInfo bInfo : beanInfos) {
            if (blacklist.contains(bInfo.propertyName))
                continue;
            boolean fieldPrimaryKey = bInfo.primaryByIdAnnotation || primaryKey(bInfo.getterAnnotation, bInfo.setterAnnotation, bInfo.fieldAnnotation);
            String dbName = dbName(bInfo.getterAnnotation, bInfo.setterAnnotation, bInfo.fieldAnnotation, bInfo.dbName);
            bInfo.creatable &= dbInsertable(bInfo.getterAnnotation, bInfo.setterAnnotation, bInfo.fieldAnnotation);
            bInfo.updatable &= dbUpdatable(bInfo.getterAnnotation, bInfo.setterAnnotation, bInfo.fieldAnnotation);
            hasPrimaryKey |= fieldPrimaryKey;
            fieldInfo.add(new FieldInfo(bInfo.propertyName, dbName, bInfo.type, bInfo.getter, bInfo.setter, bInfo.sequence, fieldPrimaryKey, bInfo.creatable, bInfo.updatable));
        }

        DbTable dbTable = clazz.getAnnotation(DbTable.class);
        String name;
        if (dbTable != null && !dbTable.name().trim().isEmpty())
            name = dbTable.name().trim();
        else {
            String tAnName = getAnnotationValue(clazz, tableClass, tableNameMethod);
            if (tAnName != null)
                name = tAnName;
            else
                name = namingPolicy.convert(clazz.getSimpleName());
        }
        if (!hasPrimaryKey)
            for (BiPredicate<String, String> resolver : idResolver.values())
                for (FieldInfo field : fieldInfo)
                    if (resolver.test(name, field.getName()))
                        field.primaryKey = true;
        return new TableInfo(clazz, name, fieldInfo);
    }

    private static Collection<BeanInfo> findBeanInfos(Class<?> container, NamingPolicy policy) {
        // Maps to store getters and setters by their types and names
        Map<Class<?>, Map<String, Method>> getters = new HashMap<>();
        Map<Class<?>, Map<String, Method>> setters = new HashMap<>();

        // Iterate through all methods of the provided class
        for (Method m : container.getMethods()) {
            // Check if the method is a setter by its name (starts with "set")
            String set = cleanBeanName("set", m.getName());
            if (set != null) {
                // Ensure the method has a void return type and exactly one parameter
                if (m.getReturnType().equals(void.class) && m.getParameterCount() == 1) {
                    // Add the setter method to the setters map, indexed by the parameter type
                    setters.computeIfAbsent(m.getParameters()[0].getType(), k -> new HashMap<>()).put(set, m);
                }
            }
            // Check if the method is a getter, when it has no parameters and a non-void return type
            else if (m.getParameterCount() == 0 && !m.getReturnType().equals(void.class)) {
                // Skip methods returning collections or maps on getters is enough, since setters depend on getters
                if (Collection.class.isAssignableFrom(m.getReturnType()) || Map.class.isAssignableFrom(m.getReturnType()))
                    continue;
                String get = cleanBeanName("get", m.getName());
                if (get != null && !get.equals("class")) {
                    // Add the getter method to the getters map, indexed by the return type
                    getters.computeIfAbsent(m.getReturnType(), k -> new HashMap<>()).put(get, m);
                } else {
                    String is = cleanBeanName("is", m.getName());
                    if (is != null) {
                        // Add the getter method (with "is" prefix) to the getters map, indexed by the return type
                        getters.computeIfAbsent(m.getReturnType(), k -> new HashMap<>()).put(is, m);
                    }
                }
            }
        }
        // Collection to store the resulting BeanInfo objects
        Collection<BeanInfo> result = new ArrayList<>();
        // Iterate through the getters map to find corresponding setters
        for (Map.Entry<Class<?>, Map<String, Method>> getterEntry : getters.entrySet()) {
            // Get the type of the current getter methods, which will be used to find corresponding setters
            Class<?> type = getterEntry.getKey();
            // Get the named setters for the current type
            Map<String, Method> namedSetters = setters.get(type);
            // If no setters are found for the current type, continue the procedure with getters only
            if (namedSetters == null) namedSetters = Collections.emptyMap();
            // Iterate through each getter method and look for a corresponding setter method
            for (Map.Entry<String, Method> methodEntry : getterEntry.getValue().entrySet()) {
                // Get the name of the getter method
                String name = methodEntry.getKey();
                // Get the getter method, it might be missing
                Method getterMethod = methodEntry.getValue();
                Method setterMethod = namedSetters.get(name);
                result.add(new BeanInfo(name, getterMethod.getName(), getterMethod, setterMethod,
                        type, getMethodAnnotation(container, getterMethod, true), getMethodAnnotation(container, setterMethod, false), policy));
            }
        }
        return result;
    }

    private static String cleanBeanName(String prefix, String name) {
        if (name.startsWith(prefix) && name.length() > prefix.length() && Character.isUpperCase(name.charAt(prefix.length()))) {
            name = name.substring(prefix.length());
            return name.substring(0, 1).toLowerCase() + name.substring(1);
        }
        return null;
    }

    private static List<Field> getFields(Class<?> clazz) {
        List<Field> fields = Arrays.asList(clazz.getDeclaredFields());
        fields.sort((f1, f2) -> {
            String name1 = f1.getName();
            String name2 = f2.getName();
            if (name1.startsWith("is")) return -1;
            else if (name2.startsWith("is")) return 1;
            else return name1.compareTo(name2);
        });
        return fields;
    }

    private static BeanInfo findBeanInfo(String name, Collection<BeanInfo> beanInfos) {
        // Handle special "is" case
        if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) {
            BeanInfo beanInfo = extract(beanInfos, it -> it.getterName.equals(name));
            if (beanInfo != null) {
                beanInfo.propertyName = name;
                return beanInfo;
            }
        }
        return extract(beanInfos, it -> it.propertyName.equals(name));
    }

    private static boolean primaryKey(DbField getter, DbField setter, DbField field) {
        if (getter != null && getter.primaryKey())
            return true;
        if (setter != null && setter.primaryKey())
            return true;
        return field != null && field.primaryKey();
    }

    private static String dbName(DbField getter, DbField setter, DbField field, String name) {
        if (getter != null && !getter.name().trim().isEmpty())
            return getter.name().trim();
        if (setter != null && !setter.name().trim().isEmpty())
            return setter.name().trim();
        if (field != null && !field.name().trim().isEmpty())
            return field.name().trim();
        return name;
    }

    private static boolean dbInsertable(DbField getter, DbField setter, DbField field) {
        return (getter == null || getter.creatable())
                && (setter == null || setter.creatable())
                && (field == null || field.creatable());
    }

    private static boolean dbUpdatable(DbField getter, DbField setter, DbField field) {
        return (getter == null || getter.updatable())
                && (setter == null || setter.updatable())
                && (field == null || field.updatable());
    }
}
