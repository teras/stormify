// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import static onl.ycode.stormify.Utils.camelToSnake;

/**
 * Naming policy for converting class names to table names and field names to column names.
 * It is possible to create custom policies or use the predefined ones.
 */
public interface NamingPolicy {
    /**
     * Convert a class or field name to a table or column name.
     * By using this method, it is possible to define rules in a central location
     * and free the objects of Annotating manually.
     *
     * @param s The given Java name
     * @return The converted Database name
     */
    String convert(String s);

    /**
     * Use the name as is.
     */
    NamingPolicy camelCase = s -> s;
    /**
     * Convert the name to lower case with underscores (snake_case).
     */
    NamingPolicy lowerCaseWithUnderscores = s -> camelToSnake(s, false);
    /**
     * Convert the name to upper case with underscores (SCREAMING_SNAKE_CASE).
     */
    NamingPolicy upperCaseWithUnderscores = s -> camelToSnake(s, true);
}
