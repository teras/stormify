// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tokenizer;

import java.math.BigInteger;

class IntegralToken extends Token<BigInteger> {

    IntegralToken(int start, int end, String name, TokenizerIdent indent) {
        super(start, end, name, indent == TokenizerIdent.AUTO ? RIGHT : indent.indent);
    }

    @Override
    protected BigInteger asData(String data) throws TokenizerException {
        try {
            return new BigInteger(data.trim());
        } catch (Exception ex) {
            throw new TokenizerException("Unable to convert to integral number");
        }
    }

    @Override
    protected String asString(Object data) throws TokenizerException {
        try {
            return data == null ? null : ((Number) data).toString();
        } catch (Exception ex) {
            throw new TokenizerException("Unable to convert field '" + getName() + "' to integral number");
        }
    }
}
