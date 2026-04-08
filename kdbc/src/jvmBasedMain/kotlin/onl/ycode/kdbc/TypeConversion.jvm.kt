// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

import onl.ycode.kdbc.converters.IonspinJvmConverters
import onl.ycode.kdbc.converters.JavaTypeConverters
import kotlin.reflect.KClass

internal actual fun registerPlatformConverters(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
    JavaTypeConverters.register(registry)
    try { IonspinJvmConverters.register(registry) } catch (_: Throwable) {}
}
