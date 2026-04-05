// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:JvmName("SpKt")
@file:JvmMultifileClass

package onl.ycode.stormify

/**
 * JVM / Android factories for [Sp.Out] and [Sp.InOut] using `Class<T>`.
 *
 * Kotlin/Native has no `java.lang.Class`, so these helpers live in the
 * JVM-based source set. Kotlin/Native callers use [spOut] / [spInOut] from
 * `commonMain`; Kotlin/JVM callers can use either form.
 *
 * Java callers reach these through the static methods:
 *
 * ```java
 * Sp.Out<Integer> y = SpKt.outParam(Integer.class);
 * Sp.InOut<String> z = SpKt.inOutParam(String.class, "hi");
 * ```
 */

/** Factory for an OUT parameter — Java-friendly, uses `Class<T>`. */
fun <T : Any> outParam(type: Class<T>): Sp.Out<T> = Sp.Out(type.kotlin)

/** Factory for an INOUT parameter — Java-friendly, uses `Class<T>`. */
fun <T : Any> inOutParam(type: Class<T>, value: T): Sp.InOut<T> = Sp.InOut(type.kotlin, value)
