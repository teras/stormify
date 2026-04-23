// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import onl.ycode.kdbc.Connection
import onl.ycode.stormify.ActiveTxRegistry
import onl.ycode.stormify.Stormify

/**
 * Platform-specific bridge that keeps `ActiveTxRegistry` in sync with a
 * suspend transaction block.
 *
 * On **JVM/Android** the [ConnectionElement] is also a `ThreadContextElement`,
 * so the runtime calls `updateThreadContext`/`restoreThreadContext` around
 * every dispatcher hop and the registry stays correct automatically. The
 * JVM/Android actual is a no-op.
 *
 * On **Native** `kotlinx-coroutines-core` 1.10 does not export
 * `ThreadContextElement`. The Native `withTxDispatcher` compensates by pinning
 * the transaction body to a single dedicated thread, and this helper pushes
 * and pops the ambient entry once around the pinned body. Combined, these
 * guarantee every blocking convenience call inside the suspend transaction
 * observes the ambient regardless of `delay` / `withContext` / `async` usage.
 */
internal expect inline fun <R> withSuspendAmbient(
    stormify: Stormify,
    conn: Connection,
    block: () -> R,
): R
