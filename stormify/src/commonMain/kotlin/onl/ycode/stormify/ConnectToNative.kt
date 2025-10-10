@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package onl.ycode.stormify

import kotlin.reflect.KClass

expect class NativeBigInteger

expect val Any.isOtherPrimitive: Boolean

internal expect fun systemMillis(): Long
internal expect fun getNativeAllPrimitives(): Collection<KClass<*>>
