// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tokenizer;

class TextToken extends Token<String> {

    TextToken(int start, int end, String name, TokenizerIdent indent) {
        super(start, end, name, indent == TokenizerIdent.AUTO ? LEFT : indent.indent);
    }

    @Override
    protected String asData(String data) {
        return data;
    }

    @Override
    protected String asString(Object data) throws TokenizerException {
        try {
            return data == null ? null : data.toString();
        } catch (Exception ex) {
            throw new TokenizerException("Unable to convert field '" + getName() + "' to text");
        }
    }

}
