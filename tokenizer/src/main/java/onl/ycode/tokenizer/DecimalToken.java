// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tokenizer;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.regex.Pattern;

class DecimalToken extends Token<BigDecimal> {

    private final DecimalFormat format;

    private final Pattern checkPattern;

    DecimalToken(int decimals, int start, int end, String name, TokenizerIdent indent) {
        super(start, end, name, indent == TokenizerIdent.AUTO ? RIGHT : indent.indent);
        if (decimals == 0)
            throw new IllegalArgumentException("Decimal token must have at least one decimal");
        else if (decimals < 0) {
            decimals = -decimals;
            checkPattern = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");
        } else
            checkPattern = Pattern.compile("^[+-]?\\d+\\.\\d{" + decimals + "}$");

        StringBuilder pattern = new StringBuilder("#.");
        for (int i = 0; i < decimals; i++)
            pattern.append("#");
        format = new DecimalFormat(pattern.toString());
        format.setMinimumFractionDigits(decimals);
        format.setMaximumFractionDigits(decimals);
        format.setParseBigDecimal(true);
    }

    @Override
    protected BigDecimal asData(String data) throws TokenizerException {
        if (data == null || data.trim().isEmpty())
            return null;
        try {
            data = data.trim();
            if (!checkPattern.matcher(data).matches())
                throw new TokenizerException("Pattern doesn't match");
            Number parsed = format.parse(data);
            return parsed instanceof BigDecimal ? (BigDecimal) parsed : new BigDecimal(parsed.toString());
        } catch (Exception ex) {
            throw new TokenizerException("Unable to convert to integral number: " + ex.getMessage());
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