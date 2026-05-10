// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

/**
 * Marker exception used by [SuspendStormify] to tell [SuspendConnectionPool.use] that the
 * caller's block threw an exception but the underlying connection is still in a healthy
 * state — typically because the user threw to trigger a clean ROLLBACK and the rollback
 * itself completed successfully.
 *
 * Without this signal the pool's blunt rule would be "any throw inside use() means this
 * connection is suspect, evict it." For an application that throws-to-rollback as a normal
 * control-flow pattern, that policy turns each rollback into a TCP+auth handshake to refill
 * the pool. With this signal the pool keeps the connection and the caller still observes
 * the original exception.
 *
 * The original cause is exposed via [cause]; [SuspendConnectionPool.use] re-throws that
 * `cause` to the caller so [HealthyConnectionException] never escapes the pool boundary.
 */
internal class HealthyConnectionException(cause: Throwable) : RuntimeException(cause)
