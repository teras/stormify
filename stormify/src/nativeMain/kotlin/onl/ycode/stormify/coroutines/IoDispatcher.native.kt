// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// See IoDispatcher.kt in commonMain for the rationale behind using Default on Native.
internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
