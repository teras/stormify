@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package onl.ycode.logger

import kotlin.reflect.KClass

/**
 * LogManager is a factory class for creating Logger instances. Use this class to create a new Logger.
 */

actual object LogManager {

    @JvmStatic
    actual fun getLogger(kclass: KClass<*>?): Logger = getLogger(kclass?.java, null)

    /**
     * Create a new Logger instance for the given class.
     *
     * @param clazz The class for which the Logger should be created.
     * @return A new Logger instance.
     */
    @JvmStatic
    fun getLogger(clazz: Class<*>?) = getLogger(clazz, null)

    /**
     * Create a new Logger instance for the given class.
     *
     * @param clazz              The class for which the Logger should be created.
     * @param preferredFramework The preferred logging framework to use.
     * @return A new Logger instance.
     */
    @JvmStatic
    fun getLogger(clazz: Class<*>?, preferredFramework: LogFramework?): Logger {
        if (preferredFramework != null)
            preferredFramework.getLogger(clazz)?.let { return it }
        for (framework in LogFramework.entries)
            framework.getLogger(clazz)?.let { return it }
        // We should never reach this point, since SystemOutLogger should always be available.
        throw java.lang.IllegalStateException("No logging framework available.")
    }

    /**
     * Create a new Logger instance for the given name.
     *
     * @param kclass The name for which the Logger should be created.
     * @return A new Logger instance.
     */
    @JvmStatic
    actual fun getLogger(name: String?): Logger = getLogger(name, null)

    /**
     * Create a new Logger instance for the given name.
     *
     * @param name               The name for which the Logger should be created.
     * @param preferredFramework The preferred logging framework to use.
     * @return A new Logger instance.
     */
    @JvmStatic
    fun getLogger(name: String?, preferredFramework: LogFramework?): Logger {
        if (preferredFramework != null)
            preferredFramework.getLogger(name)?.let { return it }
        for (framework in LogFramework.entries)
            framework.getLogger(name)?.let { return it }
        // We should never reach this point, since SystemOutLogger should always be available.
        throw IllegalStateException("No logging framework available.")
    }
}

