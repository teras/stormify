// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * An annotation to mark a class as a database table. This annotation is not required.
 * It is only used to provide additional information to Stormify.
 *
 *
 * If the name of the class is camel case, it will be converted to snake case.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class DbTable(
    /**
     * The name of the table in the database. If not provided, the name of the class will be used.
     *
     * @return The name of the table in the database.
     */
    val name: String = ""
)
