// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import onl.ycode.kdbc.Connection
import onl.ycode.stormify.Stormify

/**
 * JVM/Android actual: no-op. `ConnectionElement` is a `ThreadContextElement`
 * on this platform and the coroutine runtime handles the registry bookkeeping
 * around every dispatch hop.
 */
internal actual inline fun <R> withSuspendAmbient(
    stormify: Stormify,
    conn: Connection,
    block: () -> R,
): R = block()
