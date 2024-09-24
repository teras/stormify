// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse.dbfile;

import onl.ycode.fuse.meta.ColumnMetaData;

import static onl.ycode.fuse.TypeUtils.*;

class ColumnJava {
    private final ColumnMetaData column;
    final String name;
    final String type;
    final String getter;
    final String setter;

    public ColumnJava(ColumnMetaData column) {
        this.column = column;
        name = snakeToCamel(column.getColumnName(), false);
        type = getTypeName(column);
        getter = "get" + name.substring(0, 1).toUpperCase() + name.substring(1);
        setter = "set" + name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    private static String getTypeName(ColumnMetaData column) {
        Class<?> javaType = getJavaType(column.getColumnDataType());
        if (!column.isNullable())
            javaType = unbox(javaType);
        String name = javaType.getName();
        if (name.startsWith("java.lang."))
            name = name.substring("java.lang.".length());
        return name;
    }
}
