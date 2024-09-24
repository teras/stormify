// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tmaker;

/**
 * Represents a simple value that can be converted from a string.
 * <p>
 * The value is used to convert a string input to a value that can be used in the query.
 * This is simplified version of the {@link QueryValue} interface, when we care only
 * for equality with the database field.
 */
public interface SimpleValue {

    /**
     * Converts the input string to a value that can be used in the query.
     *
     * @param input The input string
     * @return The converted value
     * @throws DataConverterException If the input cannot be converted to a database value. The exception should contain
     *                                a meaningful message. It is important to wrap any exception that may occur during the conversion process into
     *                                a DataConverterException, so that the user can be informed about the error, by the FilterReference.
     */
    Object convert(String input) throws DataConverterException;
}
