// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tokenizer;

import java.lang.reflect.Field;

final class TPart {

    static final char COLON = ':';

    final int from;
    final int gap;
    final int size;
    final String dateformat;
    final Class<?> type;
    final String name;
    final TokenizerIdent indent;
    final int decimals;

    TPart(Field f, int from, int gap, int size, String dateformat, int decimals, TokenizerIdent indent) {
        this.from = from;
        this.gap = gap;
        this.size = size;
        this.dateformat = dateformat;
        this.type = f.getType();
        this.name = f.getName();
        this.indent = indent;
        this.decimals = decimals;
    }
}
