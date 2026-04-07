// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger


/**
 * A logger that logs to the system output.
 */
internal class SystemOutLogger(name: String) : Logger {
    private val name = if (name.isEmpty()) name else "$name: "

    override fun debug(message: String, throwable: Throwable?, vararg args: Any?) =
        log(LogLevel.DEBUG.name, message, throwable, *args)

    override fun info(message: String, throwable: Throwable?, vararg args: Any?) =
        log(LogLevel.INFO.name, message, throwable, *args)

    override fun warn(message: String, throwable: Throwable?, vararg args: Any?) =
        log(LogLevel.WARN.name, message, throwable, *args)

    override fun error(message: String, throwable: Throwable?, vararg args: Any?) =
        log(LogLevel.ERROR.name, message, throwable, *args)

    override fun fatal(message: String, throwable: Throwable?, vararg args: Any?) =
        log(LogLevel.FATAL.name, message, throwable, *args)

    private fun log(level: String, message: String, th: Throwable?, vararg args: Any?) {
        println("${nowFormat()} [$level] $name${format(message, *args)}")
        th?.printStackTrace()
    }
}
