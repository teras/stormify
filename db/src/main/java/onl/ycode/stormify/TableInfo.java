// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import onl.ycode.stormify.FieldInfo.FieldContext;

import java.util.*;

import static onl.ycode.stormify.StormifyManager.stormify;
import static onl.ycode.stormify.Utils.*;

/**
 * Parse a class and handle it as a database table.
 */
public class TableInfo {
    private final Class<?> classType;
    private final String tableName;
    private final List<FieldInfo> fields;
    private final List<FieldInfo> primaryKeys;

    // Lazy cached data
    private final LazyProperty<Map<String, Collection<FieldInfo>>> dbFields;
    private final LazyProperty<Map<String, FieldInfo>> objFields;
    private final LazyProperty<List<FieldInfo>> createFields;
    private final LazyProperty<List<FieldInfo>> updateFields;
    final LazyProperty<String> createFieldNames;
    final LazyProperty<String> updateFieldNames;
    final LazyProperty<String> createPlaceholders;

    TableInfo(Class<?> classType, String tableName, Collection<FieldInfo> fields) {
        this.classType = classType;
        this.tableName = tableName;
        this.fields = fields instanceof List ? (List<FieldInfo>) fields : new ArrayList<>(fields);
        this.primaryKeys = filter(fields, FieldInfo::isPrimaryKey);
        this.dbFields = new LazyProperty<>(() -> {
            Map<String, Collection<FieldInfo>> result = new LinkedHashMap<>();
            for (FieldInfo field : fields)
                result.computeIfAbsent(field.getDbName(), k -> new ArrayList<>()).add(field);
            return result;
        });
        this.objFields = new LazyProperty<>(() -> {
            Map<String, FieldInfo> result = new LinkedHashMap<>();
            for (FieldInfo field : fields)
                result.put(field.getName(), field);
            return result;
        });
        this.createFields = new LazyProperty<>(() -> filter(fields, FieldInfo::isInsertable));
        this.updateFields = new LazyProperty<>(() -> filter(fields, FieldInfo::isUpdatable));
        this.createFieldNames = new LazyProperty<>(() -> String.join(", ", map(createFields.get(), FieldInfo::getDbName)));
        this.updateFieldNames = new LazyProperty<>(() -> String.join(", ", map(updateFields.get(), fieldInfo -> fieldInfo.getDbName() + " = ?")));
        this.createPlaceholders = new LazyProperty<>(() -> nCopies("?", ", ", updateFields.get().size()));
    }

    /**
     * Get the class type of the table.
     *
     * @return The class type of the table.
     */
    public Class<?> getClassType() {
        return classType;
    }

    /**
     * Get the name of the table.
     *
     * @return The name of the table.
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Get a list of all primary keys of the table.
     *
     * @return The primary keys of the table.
     */
    public List<FieldInfo> getPrimaryKeys() {
        return primaryKeys;
    }

    /**
     * Get the primary key of the table. If the table has more than one primary key, or
     * no primary key, then an exception is thrown.
     *
     * @return The primary key of the table.
     */
    public FieldInfo getPrimaryKey() {
        if (primaryKeys.size() == 1)
            return primaryKeys.get(0);
        else
            throw new QueryException("Table " + tableName + " needs to have exactly one primary key");
    }

    /**
     * Get a reference of the fields of the table.
     *
     * @return The fields of the table.
     */
    public Collection<FieldInfo> getFields() {
        return getFields(null);
    }

    List<FieldInfo> getFields(FieldContext ctx) {
        if (ctx == FieldContext.CREATE)
            return createFields.get();
        else if (ctx == FieldContext.UPDATE)
            return updateFields.get();
        else
            return fields;
    }

    /**
     * Get a reference of the field, given a specific database column name.
     * <p>
     * Note that there may be more than one FieldInfo, to support cases where the same column is used in multiple
     * fields. One such common scenario is when a foreign key is used both as a reference and as a numeric id value.
     *
     * @param name The name of the column in the database.
     * @return The fields corresponding to the specific column name.
     */
    public Collection<FieldInfo> getDbField(String name) {
        return dbFields.get().getOrDefault(name, Collections.emptyList());
    }

    /**
     * Get a reference of the field, given a specific field name.
     *
     * @param name The name of the field, as a Java property.
     * @return The field corresponding to the specific field name.
     */
    public FieldInfo getField(String name) {
        return objFields.get().get(name);
    }

    @Override
    public String toString() {
        return tableName + "{" +
                String.join(" ", map(fields, it -> "\uD83C\uDFF7" + it.toString())) +
                (primaryKeys.isEmpty() ? "" : " " + map(primaryKeys, it -> "\uD83C\uDFF7" + it.toString())) +
                '}';
    }

    /**
     * Check the consistency of the table. For every database column, there should be at most one field that is
     * updatable and at most one field that is creatable. If there are more than one fields, then the
     * table is considered inconsistent.
     *
     * @return True if the table is consistent, false otherwise.
     */
    public boolean checkConsistency() {
        Map<String, Collection<FieldInfo>> fieldConsistency = new LinkedHashMap<>();
        boolean foundError = false;
        for (FieldInfo field : fields)
            fieldConsistency.computeIfAbsent(field.getDbName(), k -> new ArrayList<>()).add(field);
        for (Map.Entry<String, Collection<FieldInfo>> entry : fieldConsistency.entrySet()) {
            Collection<FieldInfo> commonFields = entry.getValue();
            if (commonFields.size() > 1) {
                Collection<FieldInfo> createFields = filter(commonFields, FieldInfo::isInsertable);
                Collection<FieldInfo> updateFields = filter(commonFields, FieldInfo::isUpdatable);
                if (createFields.size() > 1)
                    stormify().getLogger().error("Table " + tableName + " has more than one field that is creatable for column " + entry.getKey() + ": " + createFields);
                if (updateFields.size() > 1)
                    stormify().getLogger().error("Table " + tableName + " has more than one field that is updatable for column " + entry.getKey() + " : " + updateFields);
                foundError |= createFields.size() > 1 || updateFields.size() > 1;
            }
        }
        if (foundError) {
            stormify().getLogger().error("Please use the appropriate annotations to mark fields as non-creatable or non-updatable.");
            stormify().getLogger().error("The relevant annotations are @" + DbField.class.getName() + ",  @javax.persistence.Column, and @javax.persistence.JoinColumn");
        }
        return !foundError;
    }
}

