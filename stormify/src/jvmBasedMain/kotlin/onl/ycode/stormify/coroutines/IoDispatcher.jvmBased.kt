// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
