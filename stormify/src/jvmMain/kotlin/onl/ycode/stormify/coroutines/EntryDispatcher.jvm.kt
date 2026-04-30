// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package onl.ycode.stormify.coroutines

import kotlinx.coroutines.CloseableCoroutineDispatcher

internal actual fun createEntryDispatcher(name: String): CloseableCoroutineDispatcher? = null
