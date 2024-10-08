// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse.dbfile;

import onl.ycode.fuse.meta.ColumnMetaData;
import onl.ycode.fuse.meta.TableMetaData;
import ru.serce.jnrfuse.struct.FuseContext;

import java.util.List;
import java.util.stream.Collectors;

import static onl.ycode.fuse.TypeUtils.snakeToCamel;

public class TableJavaEntry extends InfoEntry {
    private final TableMetaData table;
    private final List<ColumnJava> columns;
    private final boolean isView;
    private final String packg;
    private final String className;

    public TableJavaEntry(TableMetaData table, List<ColumnMetaData> columns, FuseContext context, String packg) {
        super(snakeToCamel(table.getTableName(), true) + ".java", context);
        this.table = table;
        this.columns = columns.stream().map(ColumnJava::new).collect(Collectors.toList());
        this.isView = table.getTableType().contains("VIEW");
        this.packg = packg;
        className = snakeToCamel(table.getTableName(), true);
    }

    @Override
    String getInfo() {
        StringBuilder out = new StringBuilder();
        out.append("package ").append(packg).append(";\n\n");
        out.append("import java.sql.*;\n\n");
        out.append("@Entity\n");
        out.append("@Table(name = \"").append(table.getTableName()).append("\")\n");
        out.append("public class ").append(className).append(" {\n");
        for (ColumnJava column : columns) {
            out.append("    @Column(name = \"").append(column.name).append("\")\n");
            out.append("    private ").append(column.type).append(" ").append(column.name).append(";\n");
        }
        for (ColumnJava column : columns) {
            out.append("\n");
            out.append("    public ").append(column.type).append(" ").append(column.getter).append("() {\n");
            out.append("        return ").append(column.name).append(";\n");
            out.append("    }\n");
            out.append("\n");
            out.append("    public void ").append(column.setter).append("(").append(column.type).append(" ").append(column.name).append(") {\n");
            out.append("        this.").append(column.name).append(" = ").append(column.name).append(";\n");
            out.append("    }\n");
        }
        out.append("    public String toString() {\n");
        out.append("        return \"").append(className).append("[").append("]\";\n");
        out.append("    }\n");

        out.append("}\n");
        return out.toString();
    }
}
