// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An annotation to mark a field as a database field. This annotation is not required.
 * It is only used to provide additional information to Stormify.
 * <p>
 * If the name of the field is camel case, it will be converted to snake case.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface DbField {
    /**
     * The name of the field in the database. If not provided, the name of the field in the class will be used.
     *
     * @return The name of the field in the database.
     */
    String name() default "";

    /**
     * Whether the field is a primary key.
     *
     * @return true if the field is a primary key, false otherwise.
     */
    boolean primaryKey() default false;

    /**
     * The name of the primary key sequence in the database. If not provided, no sequence will be used, and will rely
     * on the database to generate the value of the primary key.
     *
     * @return The name of the primary key sequence in the database.
     */
    String primarySequence() default "";

    /**
     * Whether the field can be used when creating a new record. This is useful when more than one Java field
     * has the same database field name, to distinguish which field will be used.
     * <p>
     * The default value is true.
     *
     * @return true if the field can be used when creating a new record, false otherwise.
     */
    boolean creatable() default true;

    /**
     * Whether the field can be used when updating a record. This is useful when more than one Java field
     * has the same database field name, to distinguish which field will be used.
     * <p>
     * The default value is true.
     *
     * @return true if the field can be used when updating a record, false otherwise.
     */
    boolean updatable() default true;
}
