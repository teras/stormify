// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.io.PrintStream

internal class SLF4JLogger : onl.ycode.logger.Logger {
    private val logger: Logger

    internal constructor(clazz: Class<*>?) : super() {
        logger = LoggerFactory.getLogger(clazz)
    }

    internal constructor(name: String?) : super() {
        logger = LoggerFactory.getLogger(name)
    }


    override fun log(
        level: LogLevel,
        message: String,
        throwable: Throwable?,
        vararg args: Any?
    ) {
        if (level < this.level) return
        when (level) {
            LogLevel.DEBUG -> logger.debug(format(message, *args), throwable)
            LogLevel.INFO -> logger.info(format(message, *args), throwable)
            LogLevel.WARN -> logger.warn(format(message, *args), throwable)
            LogLevel.ERROR -> logger.error(format(message, *args), throwable)
            LogLevel.FATAL -> logger.error(format(message, *args), throwable)
        }
    }

    private class DummyPrintStream : PrintStream(object : OutputStream() {
        override fun write(b: Int) {}
    })

    companion object {
        init {
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
