// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlin.reflect.KClass

/**
 * This class is used to pass parameters to stored procedures.
 *
 * @param <T> The type of the parameter.
</T> */
class SPParam<T : Any> internal constructor(
    val type: KClass<T>,
    val value: Any?,
    val mode: Mode
) {
    var result: Any? = null
        /**
         * Get the result of the stored procedure.
         *
         * @return The result of the stored procedure.
         */
        set(result) {
            field = if (result == null || result.toString() == "null") null else result
        }


    enum class Mode {
        IN, OUT, INOUT
    }

    override fun toString(): String {
        return mode.name + (if (mode == Mode.OUT) "" else ":$value")
    }

    companion object {
        /**
         * Create a new IN parameter.
         *
         * @param type  The type of the parameter.
         * @param value The value of the parameter.
         * @param <T>   The type of the parameter.
         * @return The new parameter.
        </T> */
        fun <T : Any> `in`(type: KClass<T>, value: T): SPParam<T> {
            return SPParam(type, value, Mode.IN)
        }

        /**
         * Create a new OUT parameter.
         *
         * @param type The type of the parameter.
         * @param <T>  The type of the parameter.
         * @return The new parameter.
        </T> */
        fun <T : Any> out(type: KClass<T>): SPParam<T> {
            return SPParam(type, null, Mode.OUT)
        }

        /**
         * Create a new INOUT parameter.
         *
         * @param type  The type of the parameter.
         * @param value The value of the parameter.
         * @param <T>   The type of the parameter.
         * @return The new parameter.
        </T> */
        fun <T : Any> inout(type: KClass<T>, value: T): SPParam<T> {
            return SPParam(type, value, Mode.INOUT)
        }
    }
}