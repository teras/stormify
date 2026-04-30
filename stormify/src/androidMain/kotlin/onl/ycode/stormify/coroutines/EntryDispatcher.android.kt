// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package onl.ycode.stormify.coroutines

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.newSingleThreadContext
internal actual fun createEntryDispatcher(name: String): CloseableCoroutineDispatcher? =
    newSingleThreadContext(name)
