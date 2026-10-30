// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import onl.ycode.stormify.biglist.ReferencePath

/**
 * Gives Java entities the loading of `by lazyDetails()`: through the instance that
 * read the entity, and for every row of the result it came with at once.
 *
 * A row reads the children as they were when the query of its batch ran, as every
 * loaded entity holds what the database had when it was read, until a refresh. Where
 * the children must be current on every read, call `StormifyJ.getDetails(parent, type)`,
 * which queries each time.
 *
 * ```java
 * public class Order extends AutoTable {
 *     private List<OrderItem> items;
 *
 *     public List<OrderItem> getItems() {
 *         if (items == null) items = details(OrderItem.class);
 *         return items;
 *     }
 * }
 * ```
 */
actual abstract class PlatformEntity actual constructor() {
    /** Loads the entities of type [type] that reference this one. */
    protected fun <D : Any> details(type: Class<D>): List<D> = loadLazyDetails(this, type.kotlin, "")

    /** Loads the entities of type [type] that reference this one through their property [propertyName]. */
    protected fun <D : Any> details(type: Class<D>, propertyName: String): List<D> =
        loadLazyDetails(this, type.kotlin, propertyName)

    /** Loads the entities of type [type] that reference this one through the generated path [referenceField]. */
    protected fun <D : Any> details(type: Class<D>, referenceField: ReferencePath): List<D> =
        loadLazyDetails(this, type.kotlin, referenceField.toString().trimEnd('.'))
}
