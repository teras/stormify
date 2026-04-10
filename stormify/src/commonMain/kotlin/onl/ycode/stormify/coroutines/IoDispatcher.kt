// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Platform-specific dispatcher used by the suspend transaction API to run blocking
 * kdbc calls off the calling coroutine's main dispatcher.
 *
 * - **JVM / Android**: `kotlinx.coroutines.Dispatchers.IO` — a dedicated, large thread pool
 *   designed for blocking I/O. Scales up to 64 threads (or `kotlinx.coroutines.io.parallelism`
 *   if set).
 * - **Native**: `kotlinx.coroutines.Dispatchers.Default` (as of kotlinx.coroutines 1.10.x the
 *   `IO` dispatcher is still marked `internal` on Native targets). This is suboptimal for
 *   long-running blocking work because it shares workers with CPU-bound coroutines — if
 *   kotlinx.coroutines exposes `Dispatchers.IO` on Native in a future release this declaration
 *   should be updated to use it.
 *
 * The dispatcher is `internal` because it is an implementation detail. Users who need a
 * different dispatcher for their blocking DB work can pass it explicitly to the
 * transaction entry points (a future enhancement) or provide their own
 * [SuspendConnectionPool] implementation.
 */
internal expect val ioDispatcher: CoroutineDispatcher
