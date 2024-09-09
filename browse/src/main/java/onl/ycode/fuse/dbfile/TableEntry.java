// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse.dbfile;

import onl.ycode.fuse.DirEntry;
import onl.ycode.fuse.PathEntry;
import onl.ycode.fuse.meta.ColumnMetaData;
import onl.ycode.fuse.meta.TableMetaData;
import ru.serce.jnrfuse.struct.FuseContext;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import static onl.ycode.stormify.StormifyManager.stormify;

class TableEntry extends DirEntry {
    private final String catalog;
    private final String schema;
    private Collection<PathEntry> entries;
    private final TableMetaData table;
    private final String packg;

    TableEntry(TableMetaData table, FuseContext context, String catalog, String schema, String packg) {
        super("[" + table.getTableName() + "]", context);
        this.catalog = catalog;
        this.schema = schema;
        this.table = table;
        this.packg = packg;
    }

    @Override
    public Iterable<PathEntry> getEntries() {
        if (entries == null) {
            entries = new ArrayList<>();
            List<ColumnMetaData> columns = table.getColumns();
            entries.add(new TableColumnsEntry(columns, getContext()));
            entries.add(new TableMetaDataEntry(table, getContext()));
            entries.add(new TableJavaEntry(table, columns, getContext(), packg));
        }
        return entries;
    }
}

