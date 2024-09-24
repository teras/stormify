// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

import java.util.function.Function

/**
 * A list of available logging frameworks.
 */
enum class LogFramework(
    private val byClass: Function<Class<*>?, Logger>,
    private val byName: Function<String?, Logger>
) {
    /**
     * Use SLF4J for logging.
     */
    SLF4J(
        Function<Class<*>?, Logger> { clazz: Class<*>? -> SLF4JLogger(clazz) },
        Function<String?, Logger> { name: String? -> SLF4JLogger(name) }),

    /**
     * Use legacy Log4J for logging.
     */
    LOG4J(
        Function<Class<*>?, Logger> { clazz: Class<*>? -> Log4JLogger(clazz) },
        Function<String?, Logger> { name: String? -> Log4JLogger(name) }),

    /**
     * Use Log4J2 for logging.
     */
    LOG4J2(
        Function<Class<*>?, Logger> { clazz: Class<*>? -> Log4J2Logger(clazz) },
        Function<String?, Logger> { name: String? -> Log4J2Logger(name) }),

    /**
     * Use Apache Commons Logging for logging.
     */
    APACHE_COMMONS(
        Function<Class<*>?, Logger> { clazz: Class<*>? -> ApacheCommonsLogger(clazz) },
        Function<String?, Logger> { name: String? -> ApacheCommonsLogger(name) }),

    /**
     * Use System.out for logging.
     */
    SYSTEM_OUT(
        Function<Class<*>?, Logger> { clazz: Class<*>? -> SystemOutLogger(clazz?.name ?: "") },
        Function<String?, Logger> { name: String? -> SystemOutLogger(name ?: "") });

    fun getLogger(clazz: Class<*>?): Logger? {
        return try {
            byClass.apply(clazz)
        } catch (ignored: Throwable) {
            println("Unable to create logger for ${this.name}")
            null
        }
    }

    fun getLogger(name: String?): Logger? {
        return try {
            byName.apply(name)
        } catch (ignored: Throwable) {
            null
        }
    }
}
