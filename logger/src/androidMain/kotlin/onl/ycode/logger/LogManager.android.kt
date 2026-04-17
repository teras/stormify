// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package onl.ycode.logger

import android.util.Log
import kotlin.reflect.KClass

actual object LogManager {
    actual fun getLogger(name: String?): Logger = AndroidLogger(name ?: "App")
    actual fun getLogger(kclass: KClass<*>?): Logger = AndroidLogger(kclass?.simpleName ?: "App")
}

private class AndroidLogger(private val tag: String) : Logger() {
    override fun log(level: LogLevel, message: String, throwable: Throwable?, vararg args: Any?) {
        if (level < this.level) return
        val msg = if (args.isNotEmpty()) format(message, *args) else message
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, msg, throwable)
            LogLevel.INFO -> Log.i(tag, msg, throwable)
            LogLevel.WARN -> Log.w(tag, msg, throwable)
            LogLevel.ERROR -> Log.e(tag, msg, throwable)
            LogLevel.FATAL -> Log.wtf(tag, msg, throwable)
        }
    }
}
