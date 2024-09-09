// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation to mark a class as a database table. This annotation is not required.
 * It is only used to provide additional information to Stormify.
 * <p>
 * If the name of the class is camel case, it will be converted to snake case.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DbTable {
    /**
     * The name of the table in the database. If not provided, the name of the class will be used.
     *
     * @return The name of the table in the database.
     */
    String name() default "";
}
