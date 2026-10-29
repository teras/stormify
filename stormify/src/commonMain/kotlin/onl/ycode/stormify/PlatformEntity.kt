// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * The platform part of [StormifyEntity]. On the JVM and Android it gives Java
 * subclasses `details(Class)`, the counterpart of the Kotlin `by lazyDetails()`
 * delegate; elsewhere it is empty.
 */
expect abstract class PlatformEntity()
