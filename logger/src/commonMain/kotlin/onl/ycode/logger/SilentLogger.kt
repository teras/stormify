// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.logger

/**
 * A logger that does not log anything.
 */
class SilentLogger : Logger() {
    override fun log(level: LogLevel, message: String, throwable: Throwable?, vararg args: Any?) = Unit
}
