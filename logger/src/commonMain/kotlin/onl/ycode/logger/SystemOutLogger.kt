// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger


/**
 * A logger that logs to the system output.
 */
internal class SystemOutLogger(name: String) : Logger() {
    private val name = if (name.isBlank()) "" else "${name.trim()}: "

    override fun log(
        level: LogLevel,
        message: String,
        throwable: Throwable?,
        vararg args: Any?
    ) {
        if (level < this.level) return
        println("${nowFormat()} [$level] $name${format(message, *args)}")
        throwable?.printStackTrace()
    }
}
