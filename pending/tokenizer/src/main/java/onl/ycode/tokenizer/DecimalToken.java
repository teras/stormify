// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tokenizer;

import java.math.BigDecimal;
import java.text.DecimalFormat;

class DecimalToken extends Token<BigDecimal> {

    private final DecimalFormat format;

    DecimalToken(int decimals, int start, int end, String name, TokenizerIdent indent) {
        super(start, end, name, indent == TokenizerIdent.AUTO ? RIGHT : indent.indent);

        StringBuilder pattern = new StringBuilder("#.");
        for (int i = 0; i < decimals; i++)
            pattern.append("#");
        format = new DecimalFormat(pattern.toString());
        format.setMinimumFractionDigits(decimals);
        format.setMaximumFractionDigits(decimals);
    }

    @Override
    protected BigDecimal asData(String data) throws TokenizerException {
        try {
            return new BigDecimal(data.trim());
        } catch (Exception ex) {
            throw new TokenizerException("Unable to convert to integral number");
        }
    }

    @Override
    protected String asString(Object data) throws TokenizerException {
        try {
            return data == null ? null : format.format(data);
        } catch (Exception ex) {
            throw new TokenizerException("Unable to convert field '" + getName() + "' to decimal number");
        }
    }
}