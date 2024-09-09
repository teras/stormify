// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse.dbfile;

import onl.ycode.fuse.meta.ColumnMetaData;
import onl.ycode.fuse.TypeUtils;
import ru.serce.jnrfuse.struct.FuseContext;

class ColumnEntry extends InfoEntry {
    private final ColumnMetaData column;

    public ColumnEntry(ColumnMetaData column, FuseContext context) {
        super(column.getColumnName() + ".json", context);
        this.column = column;
    }


    @Override
    String getInfo() {
        return "{\n" +
                "  \"column\": \"" + column.getColumnName() + "\",\n" +
                "  \"type\": \"" + column.getTypeName() + "\",\n" +
                "  \"javaType\": \"" + TypeUtils.getJavaType(column.getColumnDataType()).getName() + "\",\n" +
                "  \"size\": " + column.getColumnSize() + ",\n" +
                "  \"nullable\": " + column.isNullable() + ",\n" +
                "  \"remarks\": \"" + column.getRemarks() + "\",\n" +
                "  \"default\": \"" + column.getColumnDef() + "\",\n" +
                "  \"autoIncrement\": " + column.isAutoIncrement() + ",\n" +
                "  \"ordinalPosition\": " + column.getOrdinalPosition() + "\n" +
                "}\n";
    }
}
