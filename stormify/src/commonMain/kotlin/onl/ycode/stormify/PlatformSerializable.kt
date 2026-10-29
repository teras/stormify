// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * Marks a class whose instances the platform can serialize: `java.io.Serializable`
 * on JVM and Android, an empty marker on native, which has no built-in object
 * serialization.
 *
 * [StormifyEntity] (and so [AutoTable]) and the [db] delegate implement it, so an
 * entity can be kept where the platform serializes objects, such as an HTTP session
 * or an Android `Bundle`. A deserialized entity is detached from any [Stormify]
 * instance: an [AutoTable] that had not been loaded yet loads its row on first
 * access through [Stormify.defaultInstance].
 */
expect interface PlatformSerializable
