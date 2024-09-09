// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tokenizer;

import java.io.IOException;

/**
 * Thrown when a tokenizer error occurs.
 */
public final class TokenizerException extends IOException {

    /**
     * Constructs a new tokenizer exception with the specified detail message.
     *
     * @param message the detail message
     */
    public TokenizerException(String message) {
        super(message);
    }

    /**
     * Constructs a new tokenizer exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public TokenizerException(String message, Throwable cause) {
        super(message, cause);
    }
}
