// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tmaker;

import java.util.function.Consumer;

/**
 * Representation of the value of a filter, as it should be injected and represented in the SQL query.
 * On each filter, the textual value is converted to a database value, while the query is continuously built.
 * <p>
 * All arguments required for the query are passed to the object consumer.
 */
public interface QueryValue {
    /**
     * Construct the fragment of the query that corresponds to the filter value.
     *
     * @param column The column name
     * @param input  The textual value of the filter
     * @param args   The consumer that will receive the arguments for the query, if any.
     * @return The fragment of the query that corresponds to the filter value.
     * @throws DataConverterException If the input cannot be converted to a database value. The exception should contain
     *                                a meaningful message. It is important to wrap any exception that may occur during the conversion process into
     *                                a DataConverterException, so that the user can be informed about the error, by the FilterReference.
     */
    String getQuery(String column, String input, Consumer<Object> args) throws DataConverterException;
}
