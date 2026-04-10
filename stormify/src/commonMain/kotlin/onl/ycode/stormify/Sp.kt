// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:JvmName("SpKt")
@file:JvmMultifileClass

package onl.ycode.stormify

import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlin.jvm.JvmStatic
import kotlin.reflect.KClass

/**
 * Stored-procedure parameter. The three modes (IN / OUT / INOUT) are distinct
 * subclasses so each carries exactly the API it needs:
 *
 * - [In] — sends a value to the procedure. No post-execute state.
 * - [Out] — receives a typed value from the procedure; exposes [Out.value].
 * - [InOut] — sends a value and receives a (possibly modified) typed value back.
 *
 * Parameters are designed to be held as references at the call site so the
 * caller can read the post-execute values directly without tracking positional
 * indices or parameter names:
 *
 * ```
 * val count = spOut<Int>()
 * val msg = spOut<String>()
 * stormify.procedure("tally", 42, count, msg)
 * println("${count.value}, ${msg.value}")  // Int?, String? — fully typed
 * ```
 *
 * From Java, use the companion factories that accept `Class<T>`:
 *
 * ```java
 * Sp.Out<Integer> count = Sp.outParam(Integer.class);
 * Sp.Out<String>  msg   = Sp.outParam(String.class);
 * stormify.procedure("tally", 42, count, msg);
 * Integer v = count.getValue();
 * ```
 *
 * Raw non-[Sp] arguments passed to `procedure(...)` are automatically wrapped
 * as [In] parameters, so the common case needs no factory calls:
 *
 * ```
 * stormify.procedure("greet", "hello", 42, outRef)  // two IN + one OUT
 * ```
 */
sealed class Sp {
    /** IN parameter. The value is sent to the procedure as-is. */
    class In(
        /** The value to send to the procedure. */
        val value: Any?
    ) : Sp() {
        /** Debug representation: `IN:<value>`. */
        override fun toString() = "IN:$value"
    }

    /**
     * OUT parameter. After `procedure(...)` returns, [value] holds the typed
     * value produced by the procedure, or `null` if it was not populated.
     */
    class Out<T : Any>(
        /** The declared Kotlin type of the returned value. */
        val type: KClass<T>
    ) : Sp() {
        private var _value: T? = null

        @Suppress("UNCHECKED_CAST")
        internal fun assign(v: Any?) {
            _value = v as T?
        }

        /** The returned value, or `null` if the procedure did not set it. */
        val value: T? get() = _value

        /** Non-null accessor; throws if the procedure did not populate this OUT. */
        val required: T
            get() = _value
                ?: error("Sp.Out<${type.simpleName}> was not populated by the procedure")

        /** Debug representation: `OUT<Type>:<value>`. */
        override fun toString() = "OUT<${type.simpleName}>:${_value}"
    }

    /**
     * INOUT parameter. The [input] value is sent to the procedure. After the
     * call returns, [value] holds whatever the procedure left there (which may
     * differ from [input]).
     */
    class InOut<T : Any>(
        /** The declared Kotlin type of the parameter. */
        val type: KClass<T>,
        /** The initial value sent to the procedure. Retained for reference after execute. */
        val input: T
    ) : Sp() {
        private var _value: T? = input

        @Suppress("UNCHECKED_CAST")
        internal fun assign(v: Any?) {
            _value = v as T?
        }

        /** The current value — initially [input], updated after execute. */
        val value: T? get() = _value

        /** Non-null accessor; throws if the procedure cleared the value to NULL. */
        val required: T
            get() = _value
                ?: error("Sp.InOut<${type.simpleName}> was cleared to NULL by the procedure")

        /** Debug representation: `INOUT<Type>:<value>`. */
        override fun toString() = "INOUT<${type.simpleName}>:${_value}"
    }

    /** Factory entry points for stored-procedure parameters. */
    companion object {
        /**
         * Factory for an IN parameter, equivalent to `Sp.In(value)`. Also the
         * entry point for Java callers — in Kotlin this is usually unnecessary
         * because raw values passed to `procedure(...)` are auto-wrapped.
         */
        @JvmStatic
        fun inParam(value: Any?): In = In(value)

        // JVM-friendly `Class<T>` factories for OUT / INOUT live in jvmBasedMain
        // (`Sp.jvmBased.kt`) — `Class<T>` is not available in commonMain.
    }
}

/** Kotlin helper for an IN parameter. Equivalent to `Sp.In(value)`.
 *  Note: raw values passed to `procedure(...)` are auto-wrapped as IN, so
 *  explicit wrapping is only needed for clarity or when the caller wants to
 *  differentiate an intended IN from a potential [Sp] instance being passed. */
fun spIn(value: Any?): Sp.In = Sp.In(value)

/** Reified Kotlin helper for an OUT parameter. Equivalent to `Sp.Out(T::class)`. */
inline fun <reified T : Any> spOut(): Sp.Out<T> = Sp.Out(T::class)

/** Reified Kotlin helper for an INOUT parameter. Equivalent to `Sp.InOut(T::class, value)`. */
inline fun <reified T : Any> spInOut(value: T): Sp.InOut<T> = Sp.InOut(T::class, value)
