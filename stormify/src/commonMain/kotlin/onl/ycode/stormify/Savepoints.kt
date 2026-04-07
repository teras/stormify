// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlinx.atomicfu.atomic

/**
 * Shared savepoint name generator used by both the blocking [TransactionContext.transaction]
 * nested path and the suspend `SuspendStormify.transaction` nested path.
 *
 * A single process-wide counter ensures that no two savepoints created anywhere in the
 * same JVM/Native process share a name, even if a blocking and a suspend transaction race
 * on the same connection. The format is deliberately opaque — callers must not parse it.
 */
private val savepointCounter = atomic(0L)

internal fun nextSavepointName(): String = "stormify_sp_${savepointCounter.incrementAndGet()}"
