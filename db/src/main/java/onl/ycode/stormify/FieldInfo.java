// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;


import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static onl.ycode.stormify.TypeUtils.castTo;
import static onl.ycode.stormify.Utils.isBaseClass;

/**
 * Information about a field in a class.
 */
public class FieldInfo {

    private final String name;
    private final String dbName;
    private final Class<?> type;
    private final Method getter;
    private final Method setter;
    private final boolean isReference;
    private final String sequence;
    private final boolean creatable;
    private final boolean updatable;
    boolean primaryKey;

    FieldInfo(String name, String dbName, Class<?> type, Method getter, Method setter, String sequence, boolean primaryKey, boolean creatable, boolean updatable) {
        this.name = name;
        this.dbName = dbName;
        this.type = type;
        this.getter = getter;
        this.setter = setter;
        this.primaryKey = primaryKey;
        this.isReference = !isBaseClass(type);
        this.sequence = sequence;
        this.creatable = creatable;
        this.updatable = updatable;
    }

    /**
     * Get the type of the field.
     *
     * @return The type of the field.
     */
    public Class<?> getType() {
        return type;
    }

    /**
     * Get the value of the field in a given object.
     *
     * @param item The object to read the field from.
     * @return The value of the field.
     */
    public Object getValue(Object item) {
        try {
            return getter.invoke(item);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new QueryException("Failed to get value for field " + name, e);
        }
    }

    /**
     * Set the value of the field in a given object.
     *
     * @param item  The object to set the field in.
     * @param value The value to set.
     */
    public void setValue(Object item, Object value) {
        setValue(item, value, null);
    }

    void setValue(Object item, Object value, ClassRegistry registry) {
        if (setter != null) {
            try {
                if (value == null && type.isPrimitive())
                    throw new QueryException("Cannot set null value for primitive field " + name);
                if (value == null || type.isAssignableFrom(value.getClass())) {
                    setter.invoke(item, value);
                    return;
                }
                if (isReference && registry != null) {
                    Object wrapper = type.getDeclaredConstructor().newInstance();
                    registry.getTableInfo(type).getPrimaryKey().setValue(wrapper, value, registry);
                    value = wrapper;
                }
                setter.invoke(item, castTo(type, value));
            } catch (Exception e) {
                throw new QueryException("Failed to set value for field '" + name + "' (value=" + value + ")", e);
            }
        } else {
            throw new QueryException("Field " + name + " is read-only");
        }
    }

    /**
     * Get the name of the field, as used for the database queries.
     *
     * @return The database name of the field.
     */
    public String getDbName() {
        return dbName;
    }

    /**
     * Get the name of the field in the class. This name is used to perform queries based on field name.
     * static
     *
     * @return The name of the field in the class.
     */
    public String getName() {
        return name;
    }

    /**
     * Check if the field is a primary key.
     *
     * @return true if the field is a primary key, false otherwise.
     */
    public boolean isPrimaryKey() {
        return primaryKey;
    }

    /**
     * Check if the field is a reference to another table.
     *
     * @return true if the field is a reference, false otherwise.
     */
    public boolean isReference() {
        return isReference;
    }

    /**
     * Get the name of the sequence used for the primary key.
     *
     * @return The name of the sequence used for the primary key, or null if no sequence is found.
     */
    public String getSequence() {
        return sequence;
    }

    /**
     * Check if the field is used when creating a new database entry.
     *
     * @return true if the field is creatable, false otherwise.
     */
    public boolean isInsertable() {
        return creatable;
    }

    /**
     * Check if the field is used when updating a database entry.
     *
     * @return true if the field is updatable, false otherwise.
     */
    public boolean isUpdatable() {
        return updatable;
    }

    @Override
    public String toString() {
        return "{" +
                (setter == null ? "\uD83D\uDCE4" : "\uD83D\uDD04") +
                (creatable && updatable ? "" : "\uD83D\uDEB7") +
                name +
                (dbName.equals(name) ? "" : " \uD83D\uDCBE" + dbName) +
                " : " + type.getSimpleName() +
                (primaryKey ? ", primary" : "") +
                (sequence == null ? "" : ", \uD83E\uDDEE='" + sequence + '\'') +
                '}';
    }

    enum FieldContext {
        CREATE, UPDATE
    }
}
