// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tokenizer;

/**
 * Tokenizer identifier.
 */
public enum TokenizerIdent {
    /**
     * Automatic inden based on type.
     */
    AUTO(null),
    /**
     * Left indent.
     */
    LEFT(Token.LEFT),
    /**
     * Right indent.
     */
    RIGHT(Token.RIGHT);
    final Boolean indent;

    TokenizerIdent(Boolean indent) {
        this.indent = indent;
    }
}
