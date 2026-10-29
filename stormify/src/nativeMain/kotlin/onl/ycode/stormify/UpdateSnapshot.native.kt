// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

// The native scalar types (kotlinx-datetime, bignum, String, numbers) are immutable.
internal actual fun platformSnapshotCopy(value: Any): Any = value
