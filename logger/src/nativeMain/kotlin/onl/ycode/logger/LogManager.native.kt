@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package onl.ycode.logger

import kotlin.reflect.KClass

/**
 * LogManager is a factory class for creating Logger instances. Use this class to create a new Logger.
 */

actual object LogManager {
    actual fun getLogger(name: String?): Logger = SystemOutLogger(name ?: "")
    actual fun getLogger(kclass: KClass<*>?): Logger = getLogger(kclass?.qualifiedName)
}