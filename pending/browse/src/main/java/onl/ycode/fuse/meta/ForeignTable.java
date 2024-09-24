// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse.meta;

import static onl.ycode.fuse.TypeUtils.snakeToCamel;

public class ForeignTable {
    private final String dbTable;
    private final String dbColumn;
    private final String javaTable;
    private final String javaColumn;

    ForeignTable(String table, String column) {
        this.dbTable = table;
        this.dbColumn = column;
        this.javaTable = snakeToCamel(table, true);
        this.javaColumn = snakeToCamel(column, false);
    }

    public String getDbTable() {
        return dbTable;
    }

    public String getDbColumn() {
        return dbColumn;
    }

    public String getJavaTable() {
        return javaTable;
    }

    public String getJavaColumn() {
        return javaColumn;
    }
}
