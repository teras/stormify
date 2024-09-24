// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

import org.apache.log4j.LogManager
import org.apache.log4j.Logger

/**
 * A logger based on Log4J2.
 */
internal class Log4JLogger : onl.ycode.logger.Logger {
    private val logger: Logger

    /**
     * Create a logger for the given class.
     *
     * @param clazz the class to create a logger for
     */
    internal constructor(clazz: Class<*>?) {
        logger = LogManager.getLogger(clazz)
    }

    /**
     * Create a logger for the given name.
     *
     * @param name the name to create a logger for
     */
    internal constructor(name: String?) {
        logger = LogManager.getLogger(name)
    }

    override fun debug(message: String, throwable: Throwable?, vararg args: Any?) =
        logger.debug(format(message, args), throwable)

    override fun info(message: String, throwable: Throwable?, vararg args: Any?) =
        logger.info(format(message, args), throwable)

    override fun warn(message: String, throwable: Throwable?, vararg args: Any?) =
        logger.warn(format(message, args), throwable)

    override fun error(message: String, throwable: Throwable?, vararg args: Any?) =
        logger.error(format(message, args), throwable)

    override fun fatal(message: String, throwable: Throwable?, vararg args: Any?) =
        logger.fatal(format(message, args), throwable)
}
