// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tmaker;

/**
 * Exception thrown when a data conversion error occurs.
 */
public class DataConverterException extends Exception {
    /**
     * Create a new DataConverterException with a message.
     *
     * @param message The message of the exception.
     */
    public DataConverterException(String message) {
        super(message);
    }

    /**
     * Create a new DataConverterException with a message and a cause.
     *
     * @param message The message of the exception.
     * @param cause   The cause of the exception.
     */
    public DataConverterException(String message, Throwable cause) {
        super(message, cause);
    }
}

