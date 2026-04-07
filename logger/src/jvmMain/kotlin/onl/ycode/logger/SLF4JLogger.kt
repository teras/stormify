// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.io.PrintStream

/**
 * A logger implementation that uses SLF4J.
 */
internal class SLF4JLogger : onl.ycode.logger.Logger {
    private val logger: Logger

    internal constructor(clazz: Class<*>?) {
        logger = LoggerFactory.getLogger(clazz)
    }

    internal constructor(name: String?) {
        logger = LoggerFactory.getLogger(name)
    }

    override fun debug(message: String, throwable: Throwable?, vararg args: Any?) =
        logger.debug(format(message, *args), throwable)

    override fun info(message: String, throwable: Throwable?, vararg args: Any?) =
        logger.info(format(message, *args), throwable)

    override fun warn(message: String, throwable: Throwable?, vararg args: Any?) =
        logger.warn(format(message, *args), throwable)

    override fun error(message: String, throwable: Throwable?, vararg args: Any?) =
        logger.error(format(message, *args), throwable)

    override fun fatal(message: String, throwable: Throwable?, vararg args: Any?) =
        logger.error(format(message, *args), throwable)

    private class DummyPrintStream : PrintStream(object : OutputStream() {
        override fun write(b: Int) {
            // Do nothing - it's a dummy stream.
        }
    })

    companion object {
        init {
            // Check that an actual logger is properly loaded.
            val originalErr = System.err
            try {
                DummyPrintStream().use { dummy ->
                    System.setErr(dummy)
                    check(LoggerFactory.getLogger("test").name != "NOP") { "No actual SLF4J logger found." }
                }
            } finally {
                System.setErr(originalErr)
            }
        }
    }
}
