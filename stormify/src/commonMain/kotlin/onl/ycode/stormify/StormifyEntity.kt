// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlin.jvm.JvmName
import kotlin.jvm.JvmSynthetic
import kotlin.jvm.Transient

/**
 * Base class for entities that carry a reference to the [Stormify] instance that loaded them.
 * This allows entity-level operations (e.g. [AutoTable.hydrate]) to use the correct
 * Stormify instance without requiring it as an explicit parameter.
 *
 * Entities are [PlatformSerializable]. The [Stormify] instance and the rows the entity
 * was read with are not serialized.
 */
abstract class StormifyEntity : PlatformEntity(), PlatformSerializable {

    // Fixed serialization version, so instances serialized by one release can be read
    // by the next as long as the serialized state is compatible.
    @Suppress("unused")
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
    // The library's bookkeeping is invisible to Java code and to bean-driven frameworks
    // (JAXB, Jackson, bean copiers): the fields are private, and the accessors the library
    // uses are synthetic (not callable from Java source) and named outside the get/set/is
    // bean convention, which java.beans.Introspector would otherwise still report.
    @Transient
    @get:JvmSynthetic @get:JvmName("stormify\$ref") @set:JvmSynthetic @set:JvmName("stormify\$assign")
    internal var _stormify: Stormify? = null

    /**
     * The other rows this one came back with, when it was read as part of a result.
     * [lazyDetails] uses it to load the children of the whole group at once.
     */
    @Transient
    @get:JvmSynthetic @get:JvmName("detailsGroup\$ref") @set:JvmSynthetic @set:JvmName("detailsGroup\$assign")
    internal var _detailsGroup: DetailsGroup? = null

    /**
     * The column values at load time, taken only when [Stormify.dynamicUpdate] is on.
     * Private field, synthetic non-bean accessors, like the fields above.
     */
    @Transient
    @get:JvmSynthetic @get:JvmName("updateSnapshot\$ref") @set:JvmSynthetic @set:JvmName("updateSnapshot\$assign")
    internal var _updateSnapshot: UpdateSnapshot? = null

    /**
     * Foreign keys read as `null` under [DanglingReferences.NULL], by property name: the
     * key the column held, written back by INSERT/UPDATE while the property stays `null`,
     * so a dangling reference is never turned into a `NULL` in the database. Serialized
     * with the entity for the same reason.
     */
    @get:JvmSynthetic @get:JvmName("danglingKeys\$ref") @set:JvmSynthetic @set:JvmName("danglingKeys\$assign")
    internal var _danglingKeys: HashMap<String, Any>? = null
}
