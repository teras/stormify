// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package onl.ycode.logger

import kotlin.reflect.KClass

/**
 * LogManager is a factory class for creating Logger instances. Use this class to create a new Logger.
 */

expect object LogManager {
    fun getLogger(name: String?): Logger
    fun getLogger(kclass: KClass<*>?): Logger
}
