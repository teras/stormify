// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

/**
 * A logger that does not log anything.
 */
class SilentLogger : Logger {
    override fun debug(message: String, throwable: Throwable?, vararg args: Any?) = Unit

    override fun info(message: String, throwable: Throwable?, vararg args: Any?) = Unit

    override fun warn(message: String, throwable: Throwable?, vararg args: Any?) = Unit

    override fun error(message: String, throwable: Throwable?, vararg args: Any?) = Unit

    override fun fatal(message: String, throwable: Throwable?, vararg args: Any?) = Unit
}
