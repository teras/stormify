// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.util.List;

import static onl.ycode.stormify.StormifyManager.stormify;

/**
 * A common interface for all tables that can perform CRUD operations.
 * <p>
 * By implementing this interface, a class can perform basic CRUD operations, such as create,
 * update, delete, directly on the object itself, without the need to call stormify() manually,
 * and reduce the clutter.
 */
public interface CRUDTable {
    /**
     * Create a new object in the database.
     */
    default void create() {
        stormify().create(this);
    }

    /**
     * Update an existing object in the database.
     */
    default void update() {
        stormify().update(this);
    }

    /**
     * Delete an existing object from the database.
     */
    default void delete() {
        stormify().delete(this);
    }

    /**
     * Get the database table name of the object.
     *
     * @return The table name of the object.
     */
    default String tableName() {
        return stormify().getTableInfo(getClass()).getTableName();
    }

    /**
     * Populate the fields of the object from the database manually.
     */
    default void populate() {
        stormify().populate(this);
    }

    /**
     * In a parent-child relationship, get all the children that have as parent the current object.
     *
     * @param detailType The type of the details.
     * @param <T>        The type of the details.
     * @return A list with all the details of the current object.
     */
    default <T> List<T> getDetails(Class<T> detailType) {
        return stormify().getDetails(this, detailType);
    }

    /**
     * In a parent-child relationship, get all the children that have as parent the current object.
     * <p>
     * If more than one parent-child relationship exists, with the same type, use this method to
     * specify the property name that holds the actual relationship.
     *
     * @param detailType   The type of the details.
     * @param propertyName The name of the property that holds the details.
     * @param <T>          The type of the details.
     * @return A list with all the details of the current object.
     */
    default <T> List<T> getDetails(Class<T> detailType, String propertyName) {
        return stormify().getDetails(this, detailType, propertyName);
    }
}
