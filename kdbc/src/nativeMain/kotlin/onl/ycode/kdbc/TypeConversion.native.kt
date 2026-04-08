// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

import kotlin.reflect.KClass

internal actual fun registerPlatformConverters(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
    // No Java-specific converters on native.
    // IonspinConverters and KotlinxTimeConverters are registered from commonMain init.
}
