// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tokenizer;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;

class DateToken extends Token<Date> {

    private final SimpleDateFormat formatter;

    DateToken(int start, int end, String format, String name, TokenizerIdent indent) {
        super(start, end, name, indent == TokenizerIdent.AUTO ? RIGHT : indent.indent);
        formatter = new SimpleDateFormat(format);
    }

    @Override
    protected Date asData(String data) throws TokenizerException {
        try {
            return formatter.parse(data.trim());
        } catch (ParseException ex) {
            throw new TokenizerException("Unable to parse " + data + " as date", ex);
        }
    }

    @Override
    protected String asString(Object data) throws TokenizerException {
        try {
            Date date = data instanceof LocalDateTime ? Date.from(((LocalDateTime) data).atZone(ZoneId.systemDefault()).toInstant()) :
                    data instanceof LocalDate ? Date.from(((LocalDate) data).atStartOfDay(ZoneId.systemDefault()).toInstant()) :
                            data instanceof LocalTime ? Date.from(((LocalTime) data).atDate(LocalDate.now()).atZone(ZoneId.systemDefault()).toInstant()) :
                                    data instanceof Date ? (Date) data :
                                            null;
            return data == null ? null : formatter.format(date);
        } catch (Exception ex) {
            throw new TokenizerException("Unable to convert field '" + getName() + "' to date");
        }
    }

}
