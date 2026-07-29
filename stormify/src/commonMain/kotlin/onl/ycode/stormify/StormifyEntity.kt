// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlin.jvm.Transient

/**
 * Base class for entities that carry a reference to the [Stormify] instance that loaded them.
 * This allows entity-level operations (e.g. [AutoTable.hydrate]) to use the correct
 * Stormify instance without requiring it as an explicit parameter.
 */
abstract class StormifyEntity : StormifyAware {
    @Transient internal var _stormify: Stormify? = null

    /**
     * The other rows this one came back with, when it was read as part of a result.
     * [lazyDetails] uses it to load the children of the whole group at once.
     */
    @Transient internal var _detailsGroup: DetailsGroup? = null

    override fun attachTo(stormify: Stormify) {
        _stormify = stormify
    }
}
