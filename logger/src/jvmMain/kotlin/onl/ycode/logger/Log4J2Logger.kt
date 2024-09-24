// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * A logger based on Log4J2.
 */
internal class Log4J2Logger : onl.ycode.logger.Logger {
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
        logger.debug(message, throwable, args)

    override fun info(message: String, throwable: Throwable?, vararg args: Any?) =
        logger.info(message, throwable, args)

    override fun warn(message: String, throwable: Throwable?, vararg args: Any?) =
        logger.warn(message, throwable, args)

    override fun error(message: String, throwable: Throwable?, vararg args: Any?) =
        logger.error(message, throwable, args)

    override fun fatal(message: String, throwable: Throwable?, vararg args: Any?) =
        logger.fatal(message, throwable, args)
}
