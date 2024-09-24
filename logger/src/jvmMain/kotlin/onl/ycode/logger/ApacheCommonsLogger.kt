// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory

/**
 * A logger based on Apache Commons Logging.
 */
internal class ApacheCommonsLogger : Logger {
    private val logger: Log

    /**
     * Create a logger for the given class.
     *
     * @param clazz the class to create a logger for
     */
    internal constructor(clazz: Class<*>?) {
        logger = LogFactory.getLog(clazz)
    }

    /**
     * Create a logger for the given name.
     *
     * @param name the name to create a logger for
     */
    internal constructor(name: String?) {
        logger = LogFactory.getLog(name)
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
