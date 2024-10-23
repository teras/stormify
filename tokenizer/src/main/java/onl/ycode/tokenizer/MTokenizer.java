// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tokenizer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for tokenizing fields.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface MTokenizer {

    /**
     * Default context.
     */
    String DEFAULT_BASE = "base";
    /**
     * Default date format.
     */
    String DEFAULT_DATE_FORMAT = "dd.MM.yyyy";

    /**
     * Index of the field. By default, count the position in the vlass file and use it as index.
     *
     * @return index of the field
     */
    int index() default -1;

    /**
     * Start position of the field. By default, the start position is the end position of the previous field.
     * It is not possible to use the index and start together.
     *
     * @return start position of the field
     */
    int start() default -1;

    /**
     * Gap between the field and the next field. By default, the gap is 0.
     *
     * @return gap between the field and the next field
     */
    int gap() default 0;

    /**
     * Size of the field. It can be defined either here or by using the @Size annotation. It is important one of
     * them to be defined.
     *
     * @return size of the field
     */
    int size() default -1;

    /**
     * Number of decimal places. By default, the number of decimal places is 2.
     * If this number is positive, then the number of decimal places is required to be exactly the value of
     * the number. If it is negative, then the number of decimal places is the absolute value of the number and is
     * only used as a reference, not strictly required. This number cannot be zero.
     *
     * @return number of decimal places
     */
    int decimals() default 1;

    /**
     * The context of the field. Sometimes the same target object can be used for different tokenizing purposes.
     * By defining a context, it is possible to have different tokenizing for the same object.
     *
     * @return the desired context. By default, the context is "base".
     */
    String context() default DEFAULT_BASE;

    /**
     * Date format. By default, the date format is "dd.MM.yyyy".
     *
     * @return date format
     */
    String dateFormat() default DEFAULT_DATE_FORMAT;

    /**
     * How a field should be indented, when an object is converted to a string. By default, the indent is AUTO, i.e.
     * based on the field type.
     *
     * @return indent of the field
     */
    TokenizerIdent indent() default TokenizerIdent.AUTO;
}
