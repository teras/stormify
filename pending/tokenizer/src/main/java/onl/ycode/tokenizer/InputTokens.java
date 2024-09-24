// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tokenizer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import java.util.List;

/**
 * Use this class to parse an input string into tokens.
 */
public class InputTokens {

    private final List<Token<?>> tokens;
    private final String input;
    int idx = 0;

    InputTokens(List<Token<?>> tokens, String input) {
        this.tokens = tokens;
        this.input = input;
    }

    @SuppressWarnings("unchecked")
    private <T> T get(Class<T> type) throws TokenizerException {
        if (idx >= tokens.size())
            throw new TokenizerException("Tried to parse past a defined token");
        Object item = tokens.get(idx++).get(input);
        if (item == null)
            return null;
        if (!type.isAssignableFrom(item.getClass()))
            throw new TokenizerException("The token type is invalid, " + item.getClass().getName() + " found while expecting " + type.getName());
        return (T) item;
    }

    /**
     * Returns the next token as any type.
     *
     * @return the next token
     * @throws TokenizerException if the token is invalid
     */
    @SuppressWarnings("unchecked")
    public <T> T any() throws TokenizerException {
        return (T) get(Object.class);
    }

    /**
     * Returns the next token as a string.
     *
     * @return the next token
     * @throws TokenizerException if the token is invalid
     */
    public String text() throws TokenizerException {
        return get(String.class);
    }

    /**
     * Returns the next token as an integral number.
     *
     * @return the next token
     * @throws TokenizerException if the token is invalid
     */
    public BigInteger integral() throws TokenizerException {
        return get(BigInteger.class);
    }

    /**
     * Returns the next token as a decimal number.
     *
     * @return the next token
     * @throws TokenizerException if the token is invalid
     */
    public BigDecimal decimal() throws TokenizerException {
        return get(BigDecimal.class);
    }

    /**
     * Returns the next token as a date.
     *
     * @return the next token
     * @throws TokenizerException if the token is invalid
     */
    public Date date() throws TokenizerException {
        return get(Date.class);
    }
}
