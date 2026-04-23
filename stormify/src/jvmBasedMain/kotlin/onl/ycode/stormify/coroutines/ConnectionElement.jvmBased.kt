// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import kotlinx.coroutines.ThreadContextElement
import onl.ycode.kdbc.Connection
import onl.ycode.stormify.ActiveTxRegistry
import onl.ycode.stormify.Stormify
import kotlin.coroutines.CoroutineContext

/**
 * JVM / Android actual: also a [ThreadContextElement], so the coroutine runtime
 * re-populates [ActiveTxRegistry] on whichever thread runs the block after a
 * dispatcher hop. See the common expect-class doc for rationale.
 */
internal actual class ConnectionElement actual constructor(
    actual internal val conn: Connection,
    actual internal val stormify: Stormify,
) : ThreadContextElement<Unit>, CoroutineContext.Element {

    actual override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext) {
        ActiveTxRegistry.push(stormify, conn)
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {
        ActiveTxRegistry.pop(stormify, conn)
    }

    actual companion object Key : CoroutineContext.Key<ConnectionElement>
}
