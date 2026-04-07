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

private class AndroidLogger(private val tag: String) : Logger {
    override fun debug(message: String, throwable: Throwable?, vararg args: Any?) {
        if (args.isNotEmpty()) Log.d(tag, messageFormat(message, args), throwable)
        else Log.d(tag, message, throwable)
    }

    override fun info(message: String, throwable: Throwable?, vararg args: Any?) {
        if (args.isNotEmpty()) Log.i(tag, messageFormat(message, args), throwable)
        else Log.i(tag, message, throwable)
    }

    override fun warn(message: String, throwable: Throwable?, vararg args: Any?) {
        if (args.isNotEmpty()) Log.w(tag, messageFormat(message, args), throwable)
        else Log.w(tag, message, throwable)
    }

    override fun error(message: String, throwable: Throwable?, vararg args: Any?) {
        if (args.isNotEmpty()) Log.e(tag, messageFormat(message, args), throwable)
        else Log.e(tag, message, throwable)
    }

    override fun fatal(message: String, throwable: Throwable?, vararg args: Any?) {
        if (args.isNotEmpty()) Log.wtf(tag, messageFormat(message, args), throwable)
        else Log.wtf(tag, message, throwable)
    }
}
