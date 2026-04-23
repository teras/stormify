// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import onl.ycode.kdbc.Connection
import onl.ycode.stormify.ActiveTxRegistry
import onl.ycode.stormify.Stormify

/**
 * Native actual: manually push the ambient entry at the entry of the
 * transaction body and pop at the exit. Works correctly as long as the
 * suspend block does not migrate to a different worker thread via an
 * explicit `withContext(Dispatchers.Default)` — that limitation is
 * documented on [ConnectionElement].
 */
internal actual inline fun <R> withSuspendAmbient(
    stormify: Stormify,
    conn: Connection,
    block: () -> R,
): R {
    ActiveTxRegistry.push(stormify, conn)
    try {
        return block()
    } finally {
        ActiveTxRegistry.pop(stormify, conn)
    }
}
