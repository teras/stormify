// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse.dbfile;

import onl.ycode.fuse.meta.TableMetaData;
import ru.serce.jnrfuse.struct.FuseContext;

public class TableMetaDataEntry extends InfoEntry {

    private final TableMetaData table;

    public TableMetaDataEntry(TableMetaData table, FuseContext context) {
        super(table.getTableName() + ".meta.json", context);
        this.table = table;
    }

    @Override
    String getInfo() {
        return "{\n" +
                "  \"table\": \"" + table.getTableName() + "\",\n" +
                "  \"type\": \"" + table.getTableType() + "\",\n" +
                "  \"remarks\": \"" + table.getRemarks() + "\",\n" +
                "}\n";
    }
}
