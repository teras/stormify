// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlin.reflect.KClass

actual sealed class Sp actual constructor() {
    actual class In actual constructor(actual val value: Any?) : Sp() {
        override fun toString() = "IN:$value"
    }

    actual class Out<T : Any> actual constructor(actual val type: KClass<T>) : Sp() {
        private var _value: T? = null

        @Suppress("UNCHECKED_CAST")
        internal actual fun assign(v: Any?) {
            _value = v as T?
        }

        actual val value: T? get() = _value
        actual val required: T
            get() = _value
                ?: error("Sp.Out<${type.simpleName}> was not populated by the procedure")

        override fun toString() = "OUT<${type.simpleName}>:${_value}"
    }

    actual class InOut<T : Any> actual constructor(actual val type: KClass<T>, actual val input: T) : Sp() {
        private var _value: T? = input

        @Suppress("UNCHECKED_CAST")
        internal actual fun assign(v: Any?) {
            _value = v as T?
        }

        actual val value: T? get() = _value
        actual val required: T
            get() = _value
                ?: error("Sp.InOut<${type.simpleName}> was cleared to NULL by the procedure")

        override fun toString() = "INOUT<${type.simpleName}>:${_value}"
    }

    actual companion object {
        actual fun inParam(value: Any?): In = In(value)
        actual fun <T : Any> outParam(type: KClass<T>): Out<T> = Out(type)
        actual fun <T : Any> inOutParam(type: KClass<T>, value: T): InOut<T> = InOut(type, value)
    }
}
