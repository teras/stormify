// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * An annotation to mark a field as a database field. This annotation is not required.
 * It is only used to provide additional information to Stormify.
 *
 *
 * If the name of the field is camel case, it will be converted to snake case.
 */
@Retention(AnnotationRetention.RUNTIME)
annotation class DbField(
    /**
     * The name of the field in the database. If not provided, the name of the field in the class will be used.
     *
     * @return The name of the field in the database.
     */
    val name: String = "",
    /**
     * Whether the field is a primary key.
     *
     * @return true if the field is a primary key, false otherwise.
     */
    val primaryKey: Boolean = false,
    /**
     * The name of the primary key sequence in the database. If not provided, no sequence will be used, and will rely
     * on the database to generate the value of the primary key.
     *
     * @return The name of the primary key sequence in the database.
     */
    val primarySequence: String = "",
    /**
     * Whether the field can be used when creating a new record. This is useful when more than one Java field
     * has the same database field name, to distinguish which field will be used.
     *
     *
     * The default value is true.
     *
     * @return true if the field can be used when creating a new record, false otherwise.
     */
    val creatable: Boolean = true,
    /**
     * Whether the field can be used when updating a record. This is useful when more than one Java field
     * has the same database field name, to distinguish which field will be used.
     *
     *
     * The default value is true.
     *
     * @return true if the field can be used when updating a record, false otherwise.
     */
    val updatable: Boolean = true
)
