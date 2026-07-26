// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import onl.ycode.kdbc.Connection
import onl.ycode.stormify.Stormify
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Native actual: `kotlinx-coroutines-core` 1.10 does not export
 * `ThreadContextElement` to Native klibs, so this element is a plain marker.
 * The Native `withTxDispatcher` pins the transaction body to a single
 * dedicated thread, and [withSuspendAmbient] pushes/pops the ambient entry
 * once around the whole block — these two together guarantee that every
 * blocking convenience call inside the suspend transaction observes the
 * ambient, regardless of `delay` / `withContext` / `async` usage in the block.
 */
internal actual class ConnectionElement actual constructor(
    actual internal val conn: Connection,
    actual internal val stormify: Stormify,
    actual internal val inTransaction: Boolean,
) : AbstractCoroutineContextElement(Key) {

    actual override val key: CoroutineContext.Key<*> get() = Key

    actual companion object Key : CoroutineContext.Key<ConnectionElement>
}
