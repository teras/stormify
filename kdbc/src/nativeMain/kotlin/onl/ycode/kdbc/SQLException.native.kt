// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

/**
 * Native implementation of [extractDriverMetadata]. Native drivers raise their
 * own [SQLException] with explicit `sqlState` / `errorCode` taken from the C
 * layer at throw time, so there is no foreign exception type to unwrap from
 * the cause chain. Returns `(null, null)`.
 */
internal actual fun extractDriverMetadata(cause: Throwable?): Pair<String?, Int?> = null to null
