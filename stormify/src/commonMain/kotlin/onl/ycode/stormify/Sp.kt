// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

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
 * From Java, the companion factories accept a raw `Class<T>` on JVM and Android:
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
expect sealed class Sp() {
    /** IN parameter. The value is sent to the procedure as-is. */
    class In(value: Any?) : Sp {
        /** The value to send to the procedure. */
        val value: Any?
    }

    /**
     * OUT parameter. After `procedure(...)` returns, [value] holds the typed
     * value produced by the procedure, or `null` if it was not populated.
     */
    class Out<T : Any>(type: KClass<T>) : Sp {
        /** The declared Kotlin type of the returned value. */
        val type: KClass<T>

        /** The returned value, or `null` if the procedure did not set it. */
        val value: T?

        /** Non-null accessor; throws if the procedure did not populate this OUT. */
        val required: T

        internal fun assign(v: Any?)
    }

    /**
     * INOUT parameter. The [input] value is sent to the procedure. After the
     * call returns, [value] holds whatever the procedure left there (which may
     * differ from [input]).
     */
    class InOut<T : Any>(type: KClass<T>, input: T) : Sp {
        /** The declared Kotlin type of the parameter. */
        val type: KClass<T>

        /** The initial value sent to the procedure. Retained for reference after execute. */
        val input: T

        /** The current value — initially [input], updated after execute. */
        val value: T?

        /** Non-null accessor; throws if the procedure cleared the value to NULL. */
        val required: T

        internal fun assign(v: Any?)
    }

    /** Factory entry points for stored-procedure parameters. */
    companion object {
        /**
         * Factory for an IN parameter, equivalent to `Sp.In(value)`. Also the
         * entry point for Java callers — in Kotlin this is usually unnecessary
         * because raw values passed to `procedure(...)` are auto-wrapped.
         */
        fun inParam(value: Any?): In

        /**
         * Factory for an OUT parameter, equivalent to `Sp.Out(type)`. Kotlin
         * callers typically prefer the reified [spOut] helper. On JVM and
         * Android a `java.lang.Class<T>` overload is also available.
         */
        fun <T : Any> outParam(type: KClass<T>): Out<T>

        /**
         * Factory for an INOUT parameter, equivalent to `Sp.InOut(type, value)`.
         * Kotlin callers typically prefer the reified [spInOut] helper. On JVM
         * and Android a `java.lang.Class<T>` overload is also available.
         */
        fun <T : Any> inOutParam(type: KClass<T>, value: T): InOut<T>
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
