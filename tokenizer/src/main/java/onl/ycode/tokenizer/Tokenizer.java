// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tokenizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizer is a class that describes how to parse a string into an object and vice versa.
 */
public class Tokenizer {

    private final List<Token<?>> tokens = new ArrayList<>();
    //   private int cursor = 0;
    private boolean isFixed = false;

    Tokenizer fix() {
        isFixed = true;
        return this;
    }

    /**
     * Returns how many tokens are defined in the tokenizer.
     *
     * @return the number of tokens
     */
    public int size() {
        int max = 0;
        for (Token<?> t : tokens)
            if (max < t.end)
                max = t.end;
        return max;
    }

    private Tokenizer add(Token<?> t) throws TokenizerException {
        if (isFixed)
            throw new RuntimeException("Unable to modify fixed Tokenizer");
        for (Token<?> o : tokens)
            if ((o.end < t.end && o.end > t.start) || (o.start > t.start && o.start < t.end))
                throw new TokenizerException("Tokens should not overlap, found overlapping tokens " + o.toString() + " with " + t.toString());
        tokens.add(t);
//        cursor = t.end;
        return this;
    }

    /**
     * Returns the tokens defined in the tokenizer.
     *
     * @return the tokens
     */
    public Iterable<Token<?>> getTokens() {
        return tokens;
    }

    /**
     * Defines an integral token.
     *
     * @param from the start position of the token
     * @param to   the end position of the token
     * @return the tokenizer
     * @throws TokenizerException if the token is overlapping with another token
     */
    public Tokenizer integral(int from, int to) throws TokenizerException {
        return integral(from, to, null);
    }

    /**
     * Defines an integral token.
     *
     * @param from the start position of the token
     * @param to   the end position of the token
     * @param name the name of the token
     * @return the tokenizer
     * @throws TokenizerException if the token is overlapping with another token
     */
    public Tokenizer integral(int from, int to, String name) throws TokenizerException {
        return integral(from, to, name, TokenizerIdent.AUTO);
    }

    /**
     * Defines an integral token.
     *
     * @param from   the start position of the token
     * @param to     the end position of the token
     * @param name   the name of the token
     * @param indent the indent of the token
     * @return the tokenizer
     * @throws TokenizerException if the token is overlapping with another token
     */
    public Tokenizer integral(int from, int to, String name, TokenizerIdent indent) throws TokenizerException {
        return add(new IntegralToken(from, to, name, indent));
    }

    /**
     * Defines a decimal token.
     *
     * @param decimals the number of decimal places
     * @param from     the start position of the token
     * @param to       the end position of the token
     * @return the tokenizer
     * @throws TokenizerException if the token is overlapping with another token
     */
    public Tokenizer decimal(int decimals, int from, int to) throws TokenizerException {
        return decimal(decimals, from, to, null);
    }

    /**
     * Defines a decimal token.
     *
     * @param decimals the number of decimal places
     * @param from     the start position of the token
     * @param to       the end position of the token
     * @param name     the name of the token
     * @return the tokenizer
     * @throws TokenizerException if the token is overlapping with another token
     */
    public Tokenizer decimal(int decimals, int from, int to, String name) throws TokenizerException {
        return decimal(decimals, from, to, name, TokenizerIdent.AUTO);
    }

    /**
     * Defines a decimal token.
     *
     * @param decimals the number of decimal places
     * @param from     the start position of the token
     * @param to       the end position of the token
     * @param name     the name of the token
     * @param indent   the indent of the token
     * @return the tokenizer
     * @throws TokenizerException if the token is overlapping with another token
     */
    public Tokenizer decimal(int decimals, int from, int to, String name, TokenizerIdent indent) throws TokenizerException {
        return add(new DecimalToken(decimals, from, to, name, indent));
    }

    /**
     * Defines a text token.
     *
     * @param from the start position of the token
     * @param to   the end position of the token
     * @return the tokenizer
     * @throws TokenizerException if the token is overlapping with another token
     */
    public Tokenizer text(int from, int to) throws TokenizerException {
        return text(from, to, null);
    }

    /**
     * Defines a text token.
     *
     * @param from the start position of the token
     * @param to   the end position of the token
     * @param name the name of the token
     * @return the tokenizer
     * @throws TokenizerException if the token is overlapping with another token
     */
    public Tokenizer text(int from, int to, String name) throws TokenizerException {
        return text(from, to, name, TokenizerIdent.AUTO);
    }

    /**
     * Defines a text token.
     *
     * @param from   the start position of the token
     * @param to     the end position of the token
     * @param name   the name of the token
     * @param indent the indent of the token
     * @return the tokenizer
     * @throws TokenizerException if the token is overlapping with another token
     */
    public Tokenizer text(int from, int to, String name, TokenizerIdent indent) throws TokenizerException {
        return add(new TextToken(from, to, name, indent));
    }

    /**
     * Defines a date token.
     *
     * @param from   the start position of the token
     * @param to     the end position of the token
     * @param format the date format
     * @return the tokenizer
     * @throws TokenizerException if the token is overlapping with another token
     */
    public Tokenizer date(int from, int to, String format) throws TokenizerException {
        return date(from, to, format, null);
    }

    /**
     * Defines a date token.
     *
     * @param from   the start position of the token
     * @param to     the end position of the token
     * @param format the date format
     * @param name   the name of the token
     * @return the tokenizer
     * @throws TokenizerException if the token is overlapping with another token
     */
    public Tokenizer date(int from, int to, String format, String name) throws TokenizerException {
        return date(from, to, format, name, TokenizerIdent.AUTO);
    }

    /**
     * Defines a date token.
     *
     * @param from   the start position of the token
     * @param to     the end position of the token
     * @param format the date format
     * @param name   the name of the token
     * @param indent the indent of the token
     * @return the tokenizer
     * @throws TokenizerException if the token is overlapping with another token
     */
    public Tokenizer date(int from, int to, String format, String name, TokenizerIdent indent) throws TokenizerException {
        return add(new DateToken(from, to, format, name, indent));
    }

    /**
     * Generates a string from the input data.
     *
     * @param data the input data
     * @return the generated string
     * @throws TokenizerException if the number of input parameters differs from the tokens size
     */
    public String output(Object[] data) throws TokenizerException {
        if (data == null || data.length != tokens.size())
            throw new TokenizerException("The number of input parameters differs from the tokens size");
        StringBuilder out = new StringBuilder(TokenUtils.repetitions(' ', size()));
        for (int i = 0; i < data.length; i++) {
            Token<?> token = tokens.get(i);
            String entry = token.toString(data[i]);
            out.replace(token.start, token.end, entry);
        }
        return out.toString();
    }

    /**
     * Parses the input string.
     *
     * @param input the input string
     * @return the parsed tokens
     * @throws TokenizerException if the input string does not match the tokens
     */
    public InputTokens parse(String input) throws TokenizerException {
        return new InputTokens(tokens, input);
    }
}
