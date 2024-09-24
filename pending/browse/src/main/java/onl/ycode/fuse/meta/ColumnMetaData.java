// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse.meta;

public class ColumnMetaData {
    private final String columnName;
    private final int dataType;
    private final String typeName;
    private final int columnSize;
    private final boolean nullable;
    private final String remarks;
    private final String columnDef;
    private final boolean equals;
    private final int ordinalPosition;
    private final boolean primaryKey;
    private final ForeignTable foreignKeyTable;

    public ColumnMetaData(String columnName, int dataType, String typeName, int columnSize, boolean nullable, String remarks, String columnDef, boolean equals, int ordinalPosition, boolean primaryKey, ForeignTable foreignKeyTable) {
        this.columnName = columnName;
        this.dataType = dataType;
        this.typeName = typeName;
        this.columnSize = columnSize;
        this.nullable = nullable;
        this.remarks = remarks;
        this.columnDef = columnDef;
        this.equals = equals;
        this.ordinalPosition = ordinalPosition;
        this.primaryKey = primaryKey;
        this.foreignKeyTable = foreignKeyTable;
    }

    public String getColumnName() {
        return columnName;
    }

    public int getColumnDataType() {
        return dataType;
    }

    public String getTypeName() {
        return typeName;
    }

    public int getColumnSize() {
        return columnSize;
    }

    public boolean isNullable() {
        return nullable;
    }

    public String getRemarks() {
        return remarks;
    }

    public String getColumnDef() {
        return columnDef;
    }

    public boolean isAutoIncrement() {
        return equals;
    }

    public int getOrdinalPosition() {
        return ordinalPosition;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public ForeignTable getForeignKeyTable() {
        return foreignKeyTable;
    }

}
