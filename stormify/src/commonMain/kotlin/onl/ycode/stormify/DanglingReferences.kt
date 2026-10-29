// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * What a reference does when its foreign key points at a row that does not exist (a
 * dangling reference, common in legacy databases without foreign key constraints).
 * Set per instance through [Stormify.danglingReferences].
 */
enum class DanglingReferences {
    /**
     * Default. The reference is a lazy entity like any other; loading it (the first
     * access to one of its fields) throws `SQLException` "No data found", on every
     * access. Other references loaded in the same batch are unaffected.
     */
    THROW,

    /**
     * The reference reads as `null`, as with JPA. When a read completes, the keys its
     * rows refer to are checked with one query per referenced type, and every property
     * that points at a missing row is set to `null`; the existing references stay lazy.
     * A reference held some other way (a non-nullable property, a streamed row already
     * handed out) loads as an entity with default field values instead of throwing.
     */
    NULL,
}
